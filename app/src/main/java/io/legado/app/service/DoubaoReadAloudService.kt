package io.legado.app.service

import android.app.PendingIntent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadAloudRuleSegmenter
import io.legado.app.model.ReadAloudSegment
import io.legado.app.model.tts.DoubaoTtsClient
import io.legado.app.model.tts.DoubaoTtsEngine
import io.legado.app.model.tts.DoubaoTtsRenderer
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 豆包朗读。
 *
 * MVP3.1 先使用 HTTP V1 非流式合成到本地缓存再播放，后续可替换为 V3 流式。
 */
@OptIn(UnstableApi::class)
class DoubaoReadAloudService : BaseReadAloudService(), Player.Listener {

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().apply {
            addListener(this@DoubaoReadAloudService)
        }
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "doubaoTTS" + File.separator
    }
    private var speakJob: Coroutine<*>? = null
    private var doubaoSegmentsPrepared = false

    override fun onDestroy() {
        super.onDestroy()
        speakJob?.cancel()
        exoPlayer.release()
    }

    override fun play() {
        if (!requestFocus()) return
        if (!prepareDoubaoSegments()) {
            nextChapter()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@DoubaoReadAloudService)
        playCurrentSegment()
    }

    override fun playStop() {
        speakJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    override fun upSpeechRate(reset: Boolean) {
        // 豆包语速由 DoubaoTtsSettings.speedRatio 控制，运行中修改配置后下一段生效。
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        exoPlayer.pause()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (exoPlayer.mediaItemCount > 0) {
            exoPlayer.play()
        } else {
            play()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            nextSegment()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        AppLog.put("豆包音频播放出错\n${error.localizedMessage}", error)
        nextSegment()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<DoubaoReadAloudService>(actionStr)
    }

    private fun prepareDoubaoSegments(): Boolean {
        if (doubaoSegmentsPrepared) {
            return contentList.isNotEmpty()
        }
        val sourceList = contentList
        if (sourceList.isEmpty()) {
            return false
        }
        val generatedSegments = mutableListOf<ReadAloudSegment>()
        var runningStart = readAloudNumber
        sourceList.drop(nowSpeak).forEachIndexed { offset, content ->
            val sourceIndex = nowSpeak + offset
            val startPosition = contentStartPosition(sourceIndex) ?: runningStart
            var plainText = stripReadAloudTags(content)
            var segmentStart = startPosition
            if (offset == 0 && paragraphStartPos > 0 && paragraphStartPos < plainText.length) {
                plainText = plainText.substring(paragraphStartPos)
                segmentStart += paragraphStartPos
            }
            if (plainText.isNotBlank()) {
                generatedSegments.addAll(
                    ReadAloudRuleSegmenter.segment(
                        text = plainText,
                        startPosition = segmentStart,
                        renderAliyunSsml = false
                    )
                )
            }
            runningStart = startPosition + stripReadAloudTags(content).length + 1
        }
        if (generatedSegments.isEmpty()) {
            return false
        }
        contentList = generatedSegments.map { it.text }
        readAloudSegments = generatedSegments
        contentStartPositions = generatedSegments.map { it.startPosition }
        contentPlainLengths = generatedSegments.map { it.plainLength }
        nowSpeak = 0
        paragraphStartPos = 0
        readAloudNumber = generatedSegments.first().startPosition
        doubaoSegmentsPrepared = true
        return true
    }

    private fun playCurrentSegment() {
        speakJob?.cancel()
        speakJob = execute {
            val settings = DoubaoTtsEngine.getSettings()
            if (!settings.isConfigured()) {
                withContext(Main) {
                    toastOnUi("请先配置豆包 AppID 和 AccessToken")
                    stopSelf()
                }
                return@execute
            }
            val segment = readAloudSegments.getOrNull(nowSpeak)
                ?: throw NoStackTraceException("豆包朗读段为空")
            val speakText = segment.text.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isBlank()) {
                nextSegmentOnMain()
                return@execute
            }
            val request = DoubaoTtsRenderer(settings.toVoiceConfig()).render(
                segment = segment.copy(
                    text = speakText,
                    emotion = settings.emotion.ifBlank { segment.emotion }
                ),
                previousSegments = readAloudSegments.take(nowSpeak)
            )
            val file = getSpeakFile(request.cacheKeySeed)
            if (!file.exists()) {
                ensureActive()
                val audio = DoubaoTtsClient(settings.toApiConfig()).synthesize(request)
                file.parentFile?.mkdirs()
                file.writeBytes(audio.bytes)
            }
            ensureActive()
            withContext(Main) {
                upTtsProgress(segment.startPosition + 1)
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }.onError {
            AppLog.put("豆包 TTS 合成出错\n${it.localizedMessage}", it, true)
            nextSegmentOnMain()
        }
    }

    private fun nextSegmentOnMain() {
        execute {
            withContext(Main) {
                nextSegment()
            }
        }
    }

    private fun nextSegment() {
        if (nowSpeak < contentList.size - 1) {
            val currentIndex = nowSpeak
            nowSpeak++
            readAloudNumber = contentStartPosition(nowSpeak)
                ?: (readAloudNumber + contentPlainLength(currentIndex) + 1)
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            nextChapter()
        }
    }

    private fun getSpeakFile(cacheKeySeed: String): File {
        val name = MD5Utils.md5Encode16(cacheKeySeed) + ".mp3"
        return File(ttsFolderPath, name)
    }
}
