package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import android.os.Looper
import android.util.Base64
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudProviderSegment
import io.legado.app.model.ReadAloudRuleSegmenter
import io.legado.app.model.ReadAloudRole
import io.legado.app.model.ReadAloudSegment
import io.legado.app.model.ReadAloudSegmentCacheRepository
import io.legado.app.model.ReadAloudSegmentCacheKey
import io.legado.app.model.ReadBook
import io.legado.app.model.selectSegments
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.tts.AliyunSsmlRenderer
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.jsonPath
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.readString
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import org.json.JSONObject
import splitties.init.appCtx
import splitties.systemservices.powerManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

private const val MAX_SSML_PLAIN_TEXT_LENGTH = 280
private const val PRE_DOWNLOAD_SEGMENT_LIMIT = 3
private const val CURRENT_CHAPTER_PREFETCH_COUNT = 3
private const val NEXT_CHAPTER_PREPARE_REMAINING_COUNT = 3
private const val SSML_LOG_TAG = "SSML_TTS"
private const val AUDIO_PREFETCH_TAG = "audioPrefetch"
private const val AUDIO_PLAYBACK_TAG = "audioPlayback"
private const val NEXT_CHAPTER_PREPARE_TAG = "nextChapterPrepare"
private const val AI_DIALOG_LABEL_TAG = "aiDialogLabel"
private const val LOG_PREVIEW_LENGTH = 80
private const val ALIYUN_PROVIDER = "aliyun"
private const val DOUBAO_PROVIDER = "doubao"
private const val HTTP_PROVIDER = "http"
private const val DEFAULT_ALIYUN_NARRATOR_VOICE = "zhifeng_emo"
private const val DEFAULT_ALIYUN_DIALOG_VOICE = "zhixiaoxia"
private const val DEFAULT_DOUBAO_SPEAKER = "zh_female_vv_uranus_bigtts"

private fun logInfo(message: String) {
    android.util.Log.i(SSML_LOG_TAG, message)
}

private fun logWarn(message: String) {
    android.util.Log.w(SSML_LOG_TAG, message)
}

private fun logAiChapterParseDebug(message: String) {
    android.util.Log.i("Legado", "AppLog $message")
}

private fun audioLogKey(fileName: String): String {
    return fileName.substringAfter("_", fileName).take(8)
}

private fun playbackMediaId(task: ReadAloudAudioTask): String {
    return "${task.generation}:${task.chapterIndex}:${task.segmentIndex}:${audioLogKey(task.fileName)}"
}

private fun logNextChapterPrepare(message: String) {
    android.util.Log.i(NEXT_CHAPTER_PREPARE_TAG, message)
    logInfo("nextChapterPrepare $message")
}

private fun logNextChapterPrepareWarn(message: String) {
    android.util.Log.w(NEXT_CHAPTER_PREPARE_TAG, message)
    logWarn("nextChapterPrepare $message")
}

private fun String.logPreview(limit: Int = LOG_PREVIEW_LENGTH): String {
    val oneLine = replace(Regex("\\s+"), " ").trim()
    return oneLine.take(limit) + if (oneLine.length > limit) "..." else ""
}

private fun logReadAloudStart(
    mode: String,
    title: String?,
    pageIndex: Int,
    readAloudNumber: Int,
    nowSpeak: Int,
    paragraphStartPos: Int,
    contentSize: Int,
    useExistingSSML: Boolean,
    usePlainTextPreprocess: Boolean,
    hasReadAloudContent: Boolean
) {
    logInfo(
        "start mode=$mode title=${title.orEmpty()} page=$pageIndex pos=$readAloudNumber " +
                "nowSpeak=$nowSpeak paragraphStart=$paragraphStartPos contentSize=$contentSize " +
                "existingSSML=$useExistingSSML aliyunPlain=$usePlainTextPreprocess " +
                "readAloudContent=$hasReadAloudContent"
    )
}

private fun logModeDecision(
    mode: String,
    httpTts: HttpTTS,
    chapterContent: String,
    contentList: List<String>,
    useExistingSSML: Boolean,
    usePlainTextPreprocess: Boolean
) {
    val chapterHasSSML = hasSSMLTags(chapterContent)
    val contentListHasSSML = contentList.any(::hasSSMLTags)
    logInfo(
        "modeDecision mode=$mode tts=${httpTts.name} aliyun=${httpTts.isAliyunTTS()} " +
                "chapterHasSSML=$chapterHasSSML contentListHasSSML=$contentListHasSSML " +
                "existingSSML=$useExistingSSML aliyunPlain=$usePlainTextPreprocess " +
                "contentHash=${MD5Utils.md5Encode16(chapterContent)} " +
                "first=${contentList.firstOrNull().orEmpty().logPreview()}"
    )
}

private fun logReadAloudResolved(
    mode: String,
    startIndex: Int,
    readAloudNumber: Int,
    sourceSize: Int,
    resolvedSize: Int
) {
    logInfo(
        "resolved mode=$mode startIndex=$startIndex pos=$readAloudNumber " +
                "sourceSize=$sourceSize resolvedSize=$resolvedSize"
    )
}

private fun logSegmentQueued(
    mode: String,
    index: Int,
    total: Int,
    text: String,
    segment: ReadAloudSegment? = null,
    providerSegment: ReadAloudProviderSegment? = null
) {
    if (index >= 20 && index != total - 1) return
    val role = segment?.role.orEmpty().ifBlank { "-" }
    val name = segment?.roleName.orEmpty().ifBlank { "-" }
    val voice = providerSegment?.voice.orEmpty().ifBlank { "-" }
    val emotion = segment?.emotion.orEmpty().ifBlank { "-" }
    val intensity = segment?.emotionIntensity ?: 1.0f
    logAiChapterParseDebug(
        "aiChapterParse audioTask stage=queued mode=$mode segment=$index " +
            "total=$total " +
            "role=$role name=$name voice=$voice emotion=$emotion intensity=$intensity " +
            "start=${segment?.startPosition ?: -1} len=${segment?.plainLength ?: -1} " +
            "ttsLen=${text.plainTextForLog().length} " +
            "hash=${MD5Utils.md5Encode16(text)} preview=${text.logPreview()}"
    )
}

private fun logTtsRequest(
    speakText: String,
    segment: ReadAloudSegment? = null,
    providerSegment: ReadAloudProviderSegment? = null
) {
    logInfo(
        "request ${speakText.ssmlDebugSummary()} ${segment.debugSummary(providerSegment)} " +
                "textLength=${speakText.length} " +
                "hash=${MD5Utils.md5Encode16(speakText)} " +
                "preview=${speakText.logPreview()}"
    )
}

private fun ReadAloudSegment?.debugSummary(providerSegment: ReadAloudProviderSegment? = null): String {
    if (this == null) {
        return "eventType=- segmentRole=- segmentName=- segmentEmotion=- segmentIntensity=- " +
                providerSegment.debugSummary()
    }
    return "eventType=${eventType.ifBlank { "-" }} " +
            "segmentRole=${role.ifBlank { "-" }} " +
            "segmentName=${roleName.ifBlank { "-" }} " +
            "segmentEmotion=${emotion.ifBlank { "-" }} " +
            "segmentIntensity=$emotionIntensity " +
            providerSegment.debugSummary()
}

private fun ReadAloudProviderSegment?.debugSummary(): String {
    if (this == null) {
        return "provider=- providerVoice=- providerStyle=-"
    }
    return "provider=${provider.ifBlank { "-" }} " +
            "providerVoice=${voice.ifBlank { "-" }} " +
            "providerStyle=${styleTag.ifBlank { "-" }}"
}

private fun String.ssmlDebugSummary(): String {
    val speakVoice = tagAttribute("speak", "voice")
    val voiceTag = tagAttribute("voice", "name")
    val emotion = tagAttribute("emotion", "category")
    return "ssml=${hasSSMLTags(this)} speakVoice=${speakVoice.ifBlank { "-" }} " +
            "voiceTag=${voiceTag.ifBlank { "-" }} emotion=${emotion.ifBlank { "-" }}"
}

private fun String.tagAttribute(tagName: String, attrName: String): String {
    val tag = Regex("<\\s*$tagName\\b[^>]*>", RegexOption.IGNORE_CASE)
        .find(this)
        ?.value
        .orEmpty()
    if (tag.isBlank()) {
        return ""
    }
    return Regex("""\b$attrName\s*=\s*(['"])(.*?)\1""", RegexOption.IGNORE_CASE)
        .find(tag)
        ?.groupValues
        ?.getOrNull(2)
        .orEmpty()
}

private fun String.plainTextForLog(): String {
    return replace(AppPattern.ssmlTagRegex, "")
        .replace(Regex("<[^>]+>"), "")
        .trim()
}

private fun hasSpeakableText(text: String): Boolean {
    val plainText = text.plainTextForLog()
    return plainText.isNotEmpty() && !plainText.matches(AppPattern.notReadAloudRegex)
}

private fun hasSSMLTags(text: String): Boolean {
    return Regex(
        "</?(speak|voice|emotion|break|prosody|p|s|sub|phoneme|say-as)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
}

private fun HttpTTS.isAliyunTTS(): Boolean {
    return name.contains("阿里云", ignoreCase = true)
            || url.contains("nls-gateway", ignoreCase = true)
            || url.contains("aliyuncs.com", ignoreCase = true)
}

private fun HttpTTS.isDoubaoTTS(): Boolean {
    return name.contains("豆包", ignoreCase = true)
            || url.contains("openspeech.bytedance.com", ignoreCase = true)
            || url.contains("volcengine", ignoreCase = true)
}

private fun HttpTTS.providerName(): String {
    return when {
        isAliyunTTS() -> ALIYUN_PROVIDER
        isDoubaoTTS() -> DOUBAO_PROVIDER
        else -> HTTP_PROVIDER
    }
}

private fun HttpTTS.providerNarratorVoice(): String {
    ReadBook.book?.getNarratorVoice(id.toString())?.let {
        return it
    }
    val loginInfo = getLoginInfoMap()
    if (isDoubaoTTS()) {
        return loginInfo?.get("NarratorSpeaker")?.takeIf { it.isNotBlank() }
            ?: loginInfo?.get("Speaker")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DOUBAO_SPEAKER
    }
    return loginInfo?.get("NarratorVoice")?.takeIf { it.isNotBlank() }
        ?: loginInfo?.get("VoiceName")?.takeIf { it.isNotBlank() }
        ?: DEFAULT_ALIYUN_NARRATOR_VOICE
}

private fun HttpTTS.providerDialogVoice(): String {
    ReadBook.book?.getDialogVoice(id.toString())?.let {
        return it
    }
    val loginInfo = getLoginInfoMap()
    if (isDoubaoTTS()) {
        return loginInfo?.get("DialogSpeaker")?.takeIf { it.isNotBlank() }
            ?: loginInfo?.get("Speaker")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DOUBAO_SPEAKER
    }
    return loginInfo?.get("DialogVoice")?.takeIf { it.isNotBlank() }
        ?: DEFAULT_ALIYUN_DIALOG_VOICE
}

private fun HttpTTS.providerSegment(segment: ReadAloudSegment?): ReadAloudProviderSegment? {
    if (segment == null) {
        return null
    }
    val voice = if (segment.role == ReadAloudRole.DIALOG) {
        providerDialogVoice()
    } else {
        providerNarratorVoice()
    }
    return ReadAloudProviderSegment(
        provider = providerName(),
        voice = voice
    )
}

private fun shouldUseExistingSSML(text: String): Boolean {
    return hasSSMLTags(text)
}

private fun shouldUseExistingSSML(text: String, contentList: List<String>): Boolean {
    return shouldUseExistingSSML(text) || contentList.any(::hasSSMLTags)
}

private fun shouldUseAliyunPlainTextPreprocess(httpTts: HttpTTS, text: String): Boolean {
    return httpTts.isAliyunTTS() && !hasSSMLTags(text)
}

private fun shouldUseAliyunPlainTextPreprocess(
    httpTts: HttpTTS,
    text: String,
    contentList: List<String>
): Boolean {
    return httpTts.isAliyunTTS() && !shouldUseExistingSSML(text, contentList)
}

private fun shouldUseRuleGeneratedSegments(
    httpTts: HttpTTS,
    useExistingSSML: Boolean
): Boolean {
    return !useExistingSSML && (httpTts.isAliyunTTS() || httpTts.isDoubaoTTS())
}

private fun escapeSSMLText(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private fun preprocessAliyunPlainText(text: String): String {
    return ensureSpeakTags(escapeSSMLText(text.trim()))
}

/**
 * 统一为阿里云 SSML 推荐结构。
 * 兼容测试文本里常见的 <speak voice="xxx">...</speak>，
 * 发送前转换为 <speak><voice name="xxx">...</voice></speak>。
 */
private fun normalizeAliyunSSML(text: String): String {
    val trimmedText = text.trim()
    val speakVoiceRegex = Regex(
        "<speak([^>]*)\\svoice\\s*=\\s*['\"]([^'\"]+)['\"]([^>]*)>(.*?)</speak>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    val normalizedText = speakVoiceRegex.replace(trimmedText) { match ->
        val voiceName = match.groupValues[2].trim()
        val innerContent = match.groupValues[4].trim()
        val speakAttrs = (match.groupValues[1] + match.groupValues[3])
            .replace(Regex("\\s+"), " ")
            .trim()
        val speakOpen = if (speakAttrs.isBlank()) "<speak>" else "<speak $speakAttrs>"
        if (innerContent.contains(Regex("<voice\\b", RegexOption.IGNORE_CASE))) {
            "$speakOpen$innerContent</speak>"
        } else {
            "$speakOpen<voice name=\"$voiceName\">$innerContent</voice></speak>"
        }
    }
    return ensureSpeakTags(normalizedText)
}

/**
 * 确保朗读内容正确包裹在 speak 标签中。
 */
private fun ensureSpeakTags(text: String): String {
    val trimmedText = text.trim()
    
    // 检查是否已有完整的 speak 标签对
    val startTagMatch = Regex("^<speak[^>]*>", RegexOption.IGNORE_CASE).find(trimmedText)
    val hasStartTag = startTagMatch != null
    
    // 检查是否有结束标签
    val endTagMatch = Regex("</speak>$", RegexOption.IGNORE_CASE).find(trimmedText)
    val hasEndTag = endTagMatch != null
    
    val content = when {
        hasStartTag && hasEndTag -> {
            // 已有完整标签，保留原始 speak 属性，例如阿里云的 voice。
            trimmedText
        }
        hasStartTag && !hasEndTag -> {
            // 只有开始标签，保留原始 speak 属性并补结束标签。
            "$trimmedText</speak>"
        }
        !hasStartTag && hasEndTag -> {
            // 只有结束标签，补开始标签。
            val endStart = endTagMatch!!.range.first
            "<speak>${trimmedText.substring(0, endStart).trim()}</speak>"
        }
        else -> {
            // 没有标签，添加标签
            "<speak>$trimmedText</speak>"
        }
    }
    
    return content
}

/**
 * 转义JSON字符串中的特殊字符
 */
private fun escapeJsonString(text: String): String {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

private fun safeStartIndex(
    preferredIndex: Int,
    contentSize: Int,
    mode: String
): Int {
    if (contentSize <= 0) {
        return 0
    }
    val safeIndex = preferredIndex.coerceIn(0, contentSize - 1)
    if (safeIndex != preferredIndex) {
        logWarn("startIndex adjusted mode=$mode from=$preferredIndex to=$safeIndex size=$contentSize")
    }
    return safeIndex
}

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
private data class ReadAloudAudioTask(
    val generation: Int,
    val chapterIndex: Int,
    val segmentIndex: Int,
    val speakText: String,
    val originalContent: String,
    val fileName: String,
    val useSSMLRequest: Boolean,
    val segment: ReadAloudSegment?,
    val providerSegment: ReadAloudProviderSegment?
)

class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                1800_000_000,
                1800_000_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            ).build()
        ).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    private val prefetchRequestLock = Mutex()
    private val loadingState = MutableStateFlow(false)
    private val segmentCacheRepository by lazy {
        ReadAloudSegmentCacheRepository(appDb.readAloudSegmentCacheDao)
    }

    @Volatile
    private var audioGeneration: Int = 0
    private var currentChapterTasks: List<ReadAloudAudioTask> = emptyList()
    private var prefetchWindowStart: Int = 0
    private var prefetchEnabled: Boolean = false
    private val prefetchJobs = mutableMapOf<Int, Coroutine<*>>()
    private val mediaIdToSegmentIndex = mutableMapOf<String, Int>()
    private val queuedSegmentIndices = mutableSetOf<Int>()
    private var lastLoggedPlaybackMediaId: String? = null
    private var nextChapterPrepareGeneration: Int = 0
    private var nextChapterPrepareJob: Coroutine<*>? = null
    private var nextChapterDownloader: Downloader? = null
    private var nextChapterPrepareKey: String? = null
    private var nextChapterPreparedKey: String? = null

    private fun registerPlaybackMedia(task: ReadAloudAudioTask): String {
        val mediaId = playbackMediaId(task)
        mediaIdToSegmentIndex[mediaId] = task.segmentIndex
        return mediaId
    }

    private fun playbackFileKey(mediaId: String?): String {
        val task = mediaId
            ?.let { mediaIdToSegmentIndex[it] }
            ?.let { currentChapterTasks.getOrNull(it) }
        return task?.fileName?.let(::audioLogKey).orEmpty()
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        cancelAllPrefetchTasks()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            nextChapter()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        releaseReadAloudPrepareWakeLock()
        cancelAllPrefetchTasks()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer.stop()
            playIndexJob?.cancel()
        } else {
            lifecycleScope.launch(Main) {
                exoPlayer.stop()
                playIndexJob?.cancel()
            }
        }
    }

    private fun updateNextPos() {
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            readAloudNumber = contentStartPosition(nowSpeak)
                ?: (readAloudNumber + contentPlainLength(nowSpeak - 1) + 1)
        } else {
            nextChapter()
        }
    }

    private suspend fun buildAudioTasks(httpTts: HttpTTS, mode: String): List<ReadAloudAudioTask> {
        val chapterContent = textChapter?.readAloudContent.orEmpty()
            .ifBlank { textChapter?.getContent().orEmpty() }
        val useExistingSSML = shouldUseExistingSSML(chapterContent, contentList)
        val usePlainTextPreprocess = shouldUseAliyunPlainTextPreprocess(
            httpTts,
            chapterContent,
            contentList
        )
        val useRuleSegments = shouldUseRuleGeneratedSegments(httpTts, useExistingSSML)
        logModeDecision(
            mode = mode,
            httpTts = httpTts,
            chapterContent = chapterContent,
            contentList = contentList,
            useExistingSSML = useExistingSSML,
            usePlainTextPreprocess = usePlainTextPreprocess
        )
        logReadAloudStart(
            mode = mode,
            title = textChapter?.title,
            pageIndex = pageIndex,
            readAloudNumber = readAloudNumber,
            nowSpeak = nowSpeak,
            paragraphStartPos = paragraphStartPos,
            contentSize = contentList.size,
            useExistingSSML = useExistingSSML,
            usePlainTextPreprocess = usePlainTextPreprocess,
            hasReadAloudContent = textChapter?.readAloudContent?.isNotBlank() == true
        )

        val processContentList = when {
            useExistingSSML -> mergeSSMLParagraphs(contentList)
            useRuleSegments -> buildRuleContentList(mode = mode, httpTts = httpTts)
            else -> contentList
        }

        if (useExistingSSML || useRuleSegments) {
            contentList = processContentList
            if (useExistingSSML) {
                syncSSMLContentParagraphOffset()
            }
            paragraphStartPos = 0
            if (useExistingSSML) {
                rebuildContentPositionMap()
            }
        }

        val startIndex = if ((useExistingSSML || useRuleSegments) && processContentList.isNotEmpty()) {
            findSegmentStartIndex(mode)
        } else {
            nowSpeak.coerceAtLeast(0)
        }.let { safeStartIndex(it, processContentList.size, mode) }

        if (useExistingSSML || useRuleSegments) {
            nowSpeak = startIndex
            readAloudNumber = contentStartPosition(startIndex) ?: readAloudNumber
            logInfo("sync mode=$mode segment=$startIndex pos=$readAloudNumber")
        }
        logReadAloudResolved(
            mode = mode,
            startIndex = startIndex,
            readAloudNumber = readAloudNumber,
            sourceSize = contentList.size,
            resolvedSize = processContentList.size
        )

        val generation = audioGeneration
        logAiChapterParseDebug(
            "aiChapterParse audioTaskBatch plan mode=$mode generation=$generation " +
                "sourceSize=${processContentList.size} segmentMap=${readAloudSegments.size} " +
                "start=$startIndex"
        )
        val tasks = mutableListOf<ReadAloudAudioTask>()
        var builtCount = 0
        var completed = false
        try {
            processContentList.forEachIndexed { index, content ->
                currentCoroutineContext().ensureActive()
                val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                val finalSpeakText = when {
                    useExistingSSML || (useRuleSegments && httpTts.isAliyunTTS()) -> normalizeAliyunSSML(speakText)
                    usePlainTextPreprocess -> preprocessAliyunPlainText(speakText)
                    else -> speakText
                }
                val segment = readAloudSegments.getOrNull(index)
                val providerSegment = httpTts.providerSegment(segment)
                val fileName = md5SpeakFileName(
                    finalSpeakText,
                    readAloudSegment = segment,
                    readAloudProviderSegment = providerSegment
                )
                logSegmentQueued(mode, index, processContentList.size, finalSpeakText, segment, providerSegment)
                tasks.add(
                    ReadAloudAudioTask(
                        generation = generation,
                        chapterIndex = chapterIndex,
                        segmentIndex = index,
                        speakText = finalSpeakText,
                        originalContent = content,
                        fileName = fileName,
                        useSSMLRequest = useExistingSSML || (useRuleSegments && httpTts.isAliyunTTS()) || usePlainTextPreprocess,
                        segment = segment,
                        providerSegment = providerSegment
                    )
                )
                builtCount = tasks.size
                if ((index + 1) % 20 == 0 || index == processContentList.lastIndex) {
                    logAiChapterParseDebug(
                        "aiChapterParse audioTaskBatch progress mode=$mode generation=$generation " +
                            "built=${index + 1}/${processContentList.size}"
                    )
                }
            }
            completed = true
        } catch (e: CancellationException) {
            logAiChapterParseDebug(
                "aiChapterParse audioTaskBuildCancelled mode=$mode generation=$generation " +
                    "built=$builtCount sourceSize=${processContentList.size} " +
                    "segmentMap=${readAloudSegments.size} message=${e.localizedMessage.orEmpty()}"
            )
            throw e
        } catch (e: Exception) {
            AppLog.put(
                "aiChapterParse audioTaskBuildError mode=$mode generation=$generation " +
                    "built=$builtCount sourceSize=${processContentList.size} " +
                    "segmentMap=${readAloudSegments.size} message=${e.localizedMessage}",
                e
            )
            throw e
        } finally {
            logAiChapterParseDebug(
                "aiChapterParse audioTaskBatch exit mode=$mode generation=$generation " +
                    "built=$builtCount/${processContentList.size} completed=$completed"
            )
        }
        logAiChapterParseDebug(
            "aiChapterParse audioTaskBatch mode=$mode generation=$generation " +
                "sourceSize=${processContentList.size} tasks=${tasks.size} " +
                "first=${tasks.firstOrNull()?.segmentIndex ?: -1} " +
                "last=${tasks.lastOrNull()?.segmentIndex ?: -1} " +
                "dialog=${tasks.count { it.segment?.role == ReadAloudRole.DIALOG }} " +
                "narrator=${tasks.count { it.segment?.role == ReadAloudRole.NARRATOR }}"
        )
        android.util.Log.i(
            "Legado",
            "AppLog aiChapterParse audioTaskBatchSummary mode=$mode generation=$generation " +
                "tasks=${tasks.size}/${processContentList.size} start=$startIndex " +
                "last=${tasks.lastOrNull()?.segmentIndex ?: -1}"
        )
        return tasks
    }

    private fun cancelAllPrefetchTasks() {
        prefetchEnabled = false
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        mediaIdToSegmentIndex.clear()
        queuedSegmentIndices.clear()
        lastLoggedPlaybackMediaId = null
        currentChapterTasks = emptyList()
        cancelNextChapterPrepare("currentPlaybackReset")
    }

    private fun cancelNextChapterPrepare(reason: String) {
        val job = nextChapterPrepareJob
        if (job?.isActive == true || nextChapterDownloader != null) {
            logNextChapterPrepare(
                "generation=$nextChapterPrepareGeneration cancel reason=$reason"
            )
        }
        nextChapterPrepareGeneration++
        nextChapterDownloader?.cancel()
        nextChapterDownloader = null
        job?.cancel()
        nextChapterPrepareJob = null
        nextChapterPrepareKey = null
    }

    private fun maybePrepareNextChapter(chapterPosition: Int, mode: String) {
        val currentChapter = textChapter ?: run {
            logNextChapterPrepare("skip reason=currentChapterMissing mode=$mode")
            return
        }
        logNextChapterPrepare(
            "check currentChapter=$chapterIndex position=$chapterPosition page=$pageIndex/" +
                    "${currentChapter.lastIndex} byPage=$readAloudByPage mode=$mode"
        )
        if (readAloudByPage && pageIndex < currentChapter.lastIndex) {
            logNextChapterPrepare(
                "skip reason=notLastPage currentChapter=$chapterIndex " +
                        "page=$pageIndex/${currentChapter.lastIndex} mode=$mode"
            )
            return
        }
        if (nextChapterPrepareJob?.isActive == true) {
            logNextChapterPrepare("skip reason=jobRunning currentChapter=$chapterIndex mode=$mode")
            return
        }
        val book = ReadBook.book ?: run {
            logNextChapterPrepare("skip reason=bookMissing currentChapter=$chapterIndex mode=$mode")
            return
        }
        val httpTts = ReadAloud.httpTTS ?: run {
            logNextChapterPrepare("skip reason=ttsMissing currentChapter=$chapterIndex mode=$mode")
            return
        }
        val chapterContent = currentChapter.getContent()
        if (chapterContent.isBlank()) {
            logNextChapterPrepare("skip reason=currentContentEmpty currentChapter=$chapterIndex mode=$mode")
            return
        }
        nextChapterPrepareGeneration++
        val generation = nextChapterPrepareGeneration
        val prepareJob = execute {
            val chapterSegments = segmentCacheRepository.getOrBuildRuleSegments(
                book = book,
                chapter = currentChapter.chapter,
                chapterContent = chapterContent
            ).segments
            if (chapterSegments.isEmpty()) {
                logNextChapterPrepare("skip reason=currentSegmentsEmpty currentChapter=$chapterIndex mode=$mode")
                return@execute
            }
            val currentIndex = chapterSegments.indexOfLast {
                it.startPosition <= chapterPosition
            }.coerceAtLeast(0)
            val remaining = chapterSegments.lastIndex - currentIndex
            if (remaining > NEXT_CHAPTER_PREPARE_REMAINING_COUNT) {
                logNextChapterPrepare(
                    "skip reason=notNearEnd currentChapter=$chapterIndex " +
                            "segment=$currentIndex/${chapterSegments.lastIndex} remaining=$remaining mode=$mode"
                )
                return@execute
            }

            val nextChapter = ReadBook.nextTextChapter
            if (nextChapter == null || !nextChapter.isCompleted) {
                logNextChapterPrepare(
                    "skip reason=nextChapterNotReady currentChapter=$chapterIndex remaining=$remaining"
                )
                return@execute
            }
            val nextContent = nextChapter.readAloudContent.ifBlank { nextChapter.getContent() }
            if (nextContent.isBlank()) {
                logNextChapterPrepare(
                    "skip reason=nextContentEmpty targetChapter=${nextChapter.chapter.index} mode=$mode"
                )
                return@execute
            }
            val prepareKey = MD5Utils.md5Encode(
                "${book.bookUrl}-|-${nextChapter.chapter.index}-|-${nextChapter.chapter.url}" +
                        "-|-${ReadAloudSegmentCacheKey.contentHash(nextContent)}-|-${httpTts.id}" +
                        "-|-${httpTts.url}-|-${httpTts.getLoginInfo()}-|-${httpTts.getLoginHeader()}" +
                        "-|-$speechRate-|-$mode"
            )
            if (prepareKey == nextChapterPreparedKey) {
                logNextChapterPrepare(
                    "skip reason=alreadyPrepared targetChapter=${nextChapter.chapter.index} mode=$mode"
                )
                return@execute
            }
            nextChapterPrepareKey = prepareKey
            logNextChapterPrepare(
                "generation=$generation trigger currentChapter=$chapterIndex " +
                        "targetChapter=${nextChapter.chapter.index} remaining=$remaining mode=$mode"
            )

            while (prefetchJobs.values.any { it.isActive }) {
                ensureActive()
                delay(100)
            }
            while (mode == "stream" && loadingState.value) {
                ensureActive()
                delay(100)
            }
            if (generation != nextChapterPrepareGeneration) return@execute
            val tasks = buildNextChapterAudioTasks(
                httpTts = httpTts,
                nextTextChapter = nextChapter,
                generation = generation,
                mode = mode
            )
            var allPrepared = tasks.isNotEmpty()
            tasks.forEach { task ->
                ensureActive()
                if (generation != nextChapterPrepareGeneration) return@execute
                if (mode == "file") {
                    if (hasSpeakFile(task.fileName)) {
                        logNextChapterPrepare(
                            "generation=$generation targetChapter=${task.chapterIndex} " +
                                    "segment=${task.segmentIndex} mode=$mode cacheHit"
                        )
                    } else {
                        prefetchRequestLock.withLock {
                            downloadSingleSegmentFile(httpTts, task)
                        }
                        if (!hasSpeakFile(task.fileName)) {
                            allPrepared = false
                        }
                    }
                } else if (hasSpeakableText(task.speakText)) {
                    val dataSourceFactory = createDataSourceFactory(
                        httpTts,
                        task.speakText,
                        task.segment,
                        task.providerSegment,
                        fallbackToSilent = false
                    )
                    val downloader = createDownloader(dataSourceFactory, task.fileName)
                    nextChapterDownloader = downloader
                    prefetchRequestLock.withLock {
                        downloader.download(null)
                    }
                    nextChapterDownloader = null
                }
            }
            if (generation == nextChapterPrepareGeneration && allPrepared) {
                nextChapterPreparedKey = prepareKey
                logNextChapterPrepare(
                    "generation=$generation targetChapter=${nextChapter.chapter.index} " +
                            "success count=${tasks.size} mode=$mode"
                )
            } else if (generation == nextChapterPrepareGeneration) {
                logNextChapterPrepareWarn(
                    "generation=$generation targetChapter=${nextChapter.chapter.index} " +
                            "incomplete count=${tasks.size} mode=$mode"
                )
            }
        }.onError { error ->
            if (error !is CancellationException) {
                logNextChapterPrepareWarn(
                    "generation=$generation currentChapter=$chapterIndex " +
                            "failed=${error.javaClass.simpleName} mode=$mode"
                )
            }
        }
        nextChapterPrepareJob = prepareJob
        prepareJob.invokeOnCompletion {
            if (generation == nextChapterPrepareGeneration) {
                nextChapterDownloader = null
                nextChapterPrepareJob = null
                nextChapterPrepareKey = null
            }
        }
    }

    private fun advancePrefetchWindow(newCurrentIndex: Int, generation: Int, mode: String) {
        if (!prefetchEnabled) return
        val oldStart = prefetchWindowStart
        prefetchWindowStart = newCurrentIndex
        if (oldStart != newCurrentIndex && nextChapterPrepareJob?.isActive == true) {
            cancelNextChapterPrepare("currentChapterWindowAdvanced")
        }

        // Cancel jobs that are now behind the window
        prefetchJobs.entries.removeAll { (index, job) ->
            if (index < newCurrentIndex) {
                job.cancel()
                true
            } else false
        }

        // Launch prefetch for segments AFTER current (current is already in foreground path)
        val httpTts = ReadAloud.httpTTS ?: return
        val windowEnd = minOf(newCurrentIndex + CURRENT_CHAPTER_PREFETCH_COUNT, currentChapterTasks.lastIndex)
        for (i in (newCurrentIndex + 1)..windowEnd) {
            if (i in queuedSegmentIndices) continue
            if (prefetchJobs.containsKey(i)) continue
            if (i >= currentChapterTasks.size) break
            val task = currentChapterTasks[i]
            val distance = i - newCurrentIndex
            android.util.Log.i(
                AUDIO_PREFETCH_TAG,
                "generation=$generation segment=${task.segmentIndex} distance=$distance mode=$mode " +
                        "textHash=${MD5Utils.md5Encode16(task.speakText)} key=${audioLogKey(task.fileName)}"
            )
            if (mode == "stream" && queuedSegmentIndices.add(task.segmentIndex)) {
                val dataSourceFactory = createDataSourceFactory(
                    httpTts,
                    task.speakText.takeIf { hasSpeakableText(it) }.orEmpty(),
                    task.segment,
                    task.providerSegment
                )
                val mediaId = registerPlaybackMedia(task)
                val mediaSource = createMediaSource(dataSourceFactory, task.fileName, mediaId)
                lifecycleScope.launch(Main) {
                    if (audioGeneration == generation) {
                        while (exoPlayer.mediaItemCount > CURRENT_CHAPTER_PREFETCH_COUNT + 2) {
                            exoPlayer.removeMediaItem(0)
                        }
                        exoPlayer.addMediaSource(mediaSource)
                    }
                }
            }
            val job = execute {
                if (audioGeneration != generation) return@execute
                ensureActive()
                if (mode == "file") {
                    if (hasSpeakFile(task.fileName)) {
                        android.util.Log.i(AUDIO_PREFETCH_TAG,
                            "generation=$generation segment=${task.segmentIndex} distance=$distance mode=$mode cacheHit")
                        if (audioGeneration == generation) {
                            lifecycleScope.launch(Main) {
                                if (audioGeneration == generation) {
                                    enqueuePreparedFileTasks(generation)
                                }
                            }
                        }
                        return@execute
                    }
                    runCatching {
                        prefetchRequestLock.withLock {
                            downloadSingleSegmentFile(httpTts, task)
                        }
                    }.onSuccess {
                        if (audioGeneration == generation) {
                            lifecycleScope.launch(Main) {
                                if (audioGeneration == generation) {
                                    enqueuePreparedFileTasks(generation)
                                }
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        android.util.Log.w(AUDIO_PREFETCH_TAG,
                            "generation=$generation segment=${task.segmentIndex} distance=$distance mode=$mode " +
                                    "prefetchFailed=${error.javaClass.simpleName}")
                    }
                } else {
                    // Stream mode: use Downloader to warm cache
                    if (hasSpeakableText(task.speakText)) {
                        val dataSourceFactory = createDataSourceFactory(
                            httpTts,
                            task.speakText,
                            task.segment,
                            task.providerSegment
                        )
                        val downloader = createDownloader(dataSourceFactory, task.fileName)
                        runCatching {
                            prefetchRequestLock.withLock {
                                downloader.download(null)
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            android.util.Log.w(AUDIO_PREFETCH_TAG,
                                "generation=$generation segment=${task.segmentIndex} distance=$distance mode=$mode " +
                                        "prefetchFailed=${error.javaClass.simpleName}")
                        }
                    }
                }
            }
            prefetchJobs[i] = job
        }
        if (oldStart != newCurrentIndex) {
            android.util.Log.i(AUDIO_PREFETCH_TAG,
                "generation=$generation advance from=$oldStart to=$newCurrentIndex mode=$mode")
        }
    }

    private fun enqueuePreparedFileTasks(generation: Int) {
        if (audioGeneration != generation || currentChapterTasks.isEmpty()) return
        var nextIndex = (nowSpeak + 1).coerceAtLeast(prefetchWindowStart)
        while (nextIndex < currentChapterTasks.size) {
            if (nextIndex in queuedSegmentIndices) {
                nextIndex++
                continue
            }
            val task = currentChapterTasks[nextIndex]
            val file = getSpeakFileAsMd5(task.fileName)
            if (!file.exists()) break
            if (queuedSegmentIndices.add(task.segmentIndex)) {
                val mediaId = registerPlaybackMedia(task)
                exoPlayer.addMediaItem(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(file))
                        .setMediaId(mediaId)
                        .build()
                )
            }
            nextIndex++
        }
    }

    private fun continueCurrentChapterFromIndex(index: Int) {
        if (index > currentChapterTasks.lastIndex) {
            nextChapter()
            return
        }
        val httpTts = ReadAloud.httpTTS ?: return
        val mode = if (AppConfig.streamReadAloudAudio) "stream" else "file"
        val generation = audioGeneration
        acquireReadAloudPrepareWakeLock()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                if (audioGeneration != generation) return@withLock
                val task = currentChapterTasks.getOrNull(index) ?: return@withLock
                logAiPlaybackTask("continuePrepare", task)
                nowSpeak = index
                readAloudNumber = contentStartPosition(index) ?: readAloudNumber
                if (mode == "file") {
                    if (!hasSpeakableText(task.speakText)) {
                        AppLog.putDebug(
                            "aiChapterParse audioSilent reason=emptyText segment=${task.segmentIndex} " +
                                "key=${audioLogKey(task.fileName)}"
                        )
                        createSilentSound(task.fileName)
                    } else if (!hasSpeakFile(task.fileName)) {
                        createSpeakFileWithFallback(
                            httpTts = httpTts,
                            fileName = task.fileName,
                            speakText = task.speakText,
                            originalContent = task.originalContent,
                            useSSMLRequest = task.useSSMLRequest,
                            readAloudSegment = task.segment,
                            readAloudProviderSegment = task.providerSegment
                        )
                    }
                    val file = getSpeakFileAsMd5(task.fileName)
                    logAiPlaybackFile("continueFile", task, file)
                    lifecycleScope.launch(Main) {
                        if (audioGeneration == generation) {
                            queuedSegmentIndices.removeAll { it >= index }
                            val mediaId = registerPlaybackMedia(task)
                            queuedSegmentIndices.add(task.segmentIndex)
                            exoPlayer.clearMediaItems()
                            exoPlayer.addMediaItem(
                                MediaItem.Builder()
                                    .setUri(Uri.fromFile(file))
                                    .setMediaId(mediaId)
                                    .build()
                            )
                            exoPlayer.prepare()
                            advancePrefetchWindow(index, generation, mode)
                            maybePrepareNextChapter(task.segment?.startPosition ?: readAloudNumber, mode)
                        }
                    }
                } else {
                    val dataSourceFactory = createDataSourceFactory(
                        httpTts,
                        task.speakText.takeIf { hasSpeakableText(it) }.orEmpty(),
                        task.segment,
                        task.providerSegment
                    )
                    lifecycleScope.launch(Main) {
                        if (audioGeneration == generation) {
                            queuedSegmentIndices.removeAll { it >= index }
                            val mediaId = registerPlaybackMedia(task)
                            val mediaSource = createMediaSource(dataSourceFactory, task.fileName, mediaId)
                            queuedSegmentIndices.add(task.segmentIndex)
                            exoPlayer.clearMediaItems()
                            exoPlayer.addMediaSource(mediaSource)
                            exoPlayer.prepare()
                            advancePrefetchWindow(index, generation, mode)
                            maybePrepareNextChapter(task.segment?.startPosition ?: readAloudNumber, mode)
                        }
                    }
                }
            }
        }.onError {
            releaseReadAloudPrepareWakeLock()
            AppLog.put("朗读续播出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun downloadSingleSegmentFile(httpTts: HttpTTS, task: ReadAloudAudioTask) {
        if (!hasSpeakableText(task.speakText)) return
        if (hasSpeakFile(task.fileName)) return
        // Background prefetch: do NOT use createSpeakFileWithFallback because
        // it creates silent audio on failure, which would cause false cache hits
        // when the segment is actually played later.
        var speakText = task.speakText
        var useSSML = task.useSSMLRequest
        runCatching {
            getSpeakStream(httpTts, speakText, task.segment, task.providerSegment)
        }.recoverCatching { error ->
            if (error is CancellationException) throw error
            if (useSSML) {
                val plainText = stripReadAloudTags(speakText)
                    .replace(AppPattern.notReadAloudRegex, "")
                    .trim()
                if (hasSpeakableText(plainText)) {
                    speakText = plainText
                    useSSML = false
                    return@recoverCatching getSpeakStream(
                        httpTts, plainText, task.segment, task.providerSegment
                    )
                }
            }
            throw error
        }.onSuccess { inputStream ->
            if (inputStream != null) {
                createSpeakFile(task.fileName, inputStream)
            }
            // Do NOT create silent sound on null stream — let foreground path handle it
        }.onFailure { error ->
            if (error is CancellationException) throw error
            // Do NOT create silent sound — let foreground path handle it on retry
            android.util.Log.w(AUDIO_PREFETCH_TAG,
                "generation=${task.generation} segment=${task.segmentIndex} mode=file " +
                        "prefetchFailed=${error.javaClass.simpleName}")
        }
    }

    private fun logAiPlaybackTask(stage: String, task: ReadAloudAudioTask) {
        val segment = task.segment
        AppLog.putDebug(
            "aiChapterParse audioTask stage=$stage generation=${task.generation} " +
                "chapter=${task.chapterIndex} segment=${task.segmentIndex} " +
                "textLen=${task.speakText.length} originalLen=${task.originalContent.length} " +
                "plainLen=${segment?.plainLength ?: -1} start=${segment?.startPosition ?: -1} " +
                "role=${segment?.role.orEmpty().ifBlank { "-" }} " +
                "emotion=${segment?.emotion.orEmpty().ifBlank { "-" }} " +
                "ssml=${task.useSSMLRequest} key=${audioLogKey(task.fileName)} " +
                "preview=${task.speakText.logPreview(40)}"
        )
    }

    private fun logAiPlaybackFile(stage: String, task: ReadAloudAudioTask, file: File) {
        AppLog.putDebug(
            "aiChapterParse audioFile stage=$stage segment=${task.segmentIndex} " +
                "exists=${file.exists()} size=${file.length()} key=${audioLogKey(task.fileName)}"
        )
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        cancelAllPrefetchTasks()
        audioGeneration++
        val generation = audioGeneration
        acquireReadAloudPrepareWakeLock()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")

                currentChapterTasks = buildAudioTasks(httpTts, "file")
                val startIndex = nowSpeak.coerceIn(0, maxOf(0, currentChapterTasks.lastIndex))
                if (currentChapterTasks.isEmpty()) {
                    releaseReadAloudPrepareWakeLock()
                    nextChapter()
                    return@withLock
                }
                prefetchWindowStart = startIndex
                AppLog.putDebug(
                    "aiChapterParse audioTaskBatch selected mode=file generation=$generation " +
                        "tasks=${currentChapterTasks.size} start=$startIndex " +
                        "last=${currentChapterTasks.lastIndex}"
                )

                android.util.Log.i(AUDIO_PREFETCH_TAG,
                    "generation=$generation chapter=$chapterIndex tasks=${currentChapterTasks.size} " +
                            "start=$startIndex window=$startIndex..${minOf(startIndex + CURRENT_CHAPTER_PREFETCH_COUNT, currentChapterTasks.lastIndex)} mode=file")

                // Current segment: foreground download + immediate queue
                val currentTask = currentChapterTasks[startIndex]
                logAiPlaybackTask("currentPrepare", currentTask)
                if (!hasSpeakableText(currentTask.speakText)) {
                    AppLog.putDebug(
                        "aiChapterParse audioSilent reason=emptyText segment=${currentTask.segmentIndex} " +
                            "key=${audioLogKey(currentTask.fileName)}"
                    )
                    AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：${currentTask.originalContent}")
                    createSilentSound(currentTask.fileName)
                } else if (!hasSpeakFile(currentTask.fileName)) {
                    createSpeakFileWithFallback(
                        httpTts = httpTts,
                        fileName = currentTask.fileName,
                        speakText = currentTask.speakText,
                        originalContent = currentTask.originalContent,
                        useSSMLRequest = currentTask.useSSMLRequest,
                        readAloudSegment = currentTask.segment,
                        readAloudProviderSegment = currentTask.providerSegment
                    )
                }
                val file = getSpeakFileAsMd5(currentTask.fileName)
                logAiPlaybackFile("currentFile", currentTask, file)
                val mediaId = registerPlaybackMedia(currentTask)
                queuedSegmentIndices.add(currentTask.segmentIndex)
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMediaId(mediaId)
                    .build()
                lifecycleScope.launch(Main) {
                    if (audioGeneration == generation) {
                        exoPlayer.addMediaItem(mediaItem)
                        AppLog.putDebug(
                            "aiChapterParse playbackPrepare stage=initial segment=${currentTask.segmentIndex} " +
                                "mediaKey=${audioLogKey(currentTask.fileName)} state=${exoPlayer.playbackState} " +
                                "items=${exoPlayer.mediaItemCount}"
                        )
                        exoPlayer.prepare()
                    }
                }

                // Background prefetch for next segments
                prefetchEnabled = true
                advancePrefetchWindow(startIndex, generation, "file")
                maybePrepareNextChapter(
                    currentTask.segment?.startPosition ?: readAloudNumber,
                    "file"
                )
            }
        }.onError {
            releaseReadAloudPrepareWakeLock()
            AppLog.put(
                "aiChapterParse ttsDownloadError stage=filePrepare message=${it.localizedMessage.orEmpty()}\n" +
                    "朗读下载出错\n${it.localizedMessage}",
                it,
                true
            )
        }
    }

    private suspend fun createSpeakFileWithFallback(
        httpTts: HttpTTS,
        fileName: String,
        speakText: String,
        originalContent: String,
        useSSMLRequest: Boolean,
        readAloudSegment: ReadAloudSegment? = null,
        readAloudProviderSegment: ReadAloudProviderSegment? = null
    ) {
        runCatching {
            getSpeakStream(httpTts, speakText, readAloudSegment, readAloudProviderSegment)
        }.recoverCatching { error ->
            if (error is CancellationException) {
                throw error
            }
            if (useSSMLRequest) {
                val plainText = stripReadAloudTags(speakText)
                    .replace(AppPattern.notReadAloudRegex, "")
                    .trim()
                if (hasSpeakableText(plainText)) {
                    AppLog.put(
                        "aiChapterParse ttsFallback reason=ssmlFailed " +
                            "message=${error.localizedMessage.orEmpty()}\n" +
                            "SSML合成失败，回退普通文本朗读。\n${error.localizedMessage}",
                        error
                    )
                    return@recoverCatching getSpeakStream(
                        httpTts,
                        plainText,
                        readAloudSegment,
                        readAloudProviderSegment
                    )
                }
            }
            throw error
        }.onSuccess { inputStream ->
            if (inputStream != null) {
                createSpeakFile(fileName, inputStream)
                AppLog.putDebug(
                    "aiChapterParse audioCreate success key=${audioLogKey(fileName)} " +
                        "fileSize=${getSpeakFileAsMd5(fileName).length()} textLen=${speakText.length}"
                )
            } else {
                AppLog.putDebug(
                    "aiChapterParse audioFailed reason=nullStream key=${audioLogKey(fileName)} " +
                        "textLen=${speakText.length}"
                )
                throw NoStackTraceException("TTS未返回有效音频流")
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            if (error is NoStackTraceException && error.localizedMessage == "TTS未返回有效音频流") {
                throw error
            }
            AppLog.putDebug(
                "aiChapterParse audioFailed reason=ttsFailed key=${audioLogKey(fileName)} " +
                    "textLen=${speakText.length} exception=${error.javaClass.simpleName} " +
                    "message=${error.localizedMessage.orEmpty().logPreview(120)}"
            )
            throw error
        }
    }

    private fun mergeSSMLParagraphs(contentList: List<String>): List<String> {
        val currentChapter = textChapter
        
        if (currentChapter?.readAloudContent?.isNotEmpty() == true) {
            // 使用readAloudContent中的完整SSML内容，按完整的speak块分割
            val ssmlBlocks = parseSSMLContent(
                ssmlContent = currentChapter.readAloudContent,
                chapterTitle = currentChapter.title,
                logSource = "play:${currentChapter.title}"
            )
            if (ssmlBlocks.isNotEmpty()) {
                return ssmlBlocks
            }
            logWarn("merge fallback title=${currentChapter.title} reason=no-speak-block")
        }
        
        // 回退逻辑：处理contentList中的普通文本或跨行SSML标签
        val mergedList = mutableListOf<String>()
        var i = 0
        
        while (i < contentList.size) {
            val content = contentList[i]
            val trimmed = content.trim()
            if (trimmed.startsWith("<speak") && trimmed.endsWith("</speak>")) {
                mergedList.add(content)
                i++
            } else if (trimmed.startsWith("<speak")) {
                var mergedSSML = content
                var j = i + 1
                while (j < contentList.size) {
                    val nextContent = contentList[j]
                    mergedSSML += "\n" + nextContent
                    if (nextContent.trim().endsWith("</speak>")) {
                        break
                    }
                    j++
                }
                mergedList.add(mergedSSML)
                i = j + 1
            } else {
                mergedList.add(content)
                i++
            }
        }
        logInfo("merge sourceSize=${contentList.size} resolvedSize=${mergedList.size}")
        return mergedList
    }
    
    /**
     * 解析SSML内容，按完整的<speak>块分割
     */
    private fun parseSSMLContent(
        ssmlContent: String,
        chapterTitle: String? = null,
        logSource: String = "chapter"
    ): List<String> {
        val result = mutableListOf<String>()
        
        // 匹配完整的<speak>...</speak>块
        val regex = Regex("<speak[^>]*>.*?</speak>", RegexOption.DOT_MATCHES_ALL)
        var lastEnd = 0
        fun addPlainContent(content: String) {
            val plainText = stripReadAloudTags(content).trim()
            if (plainText.isBlank()) {
                return
            }
            splitPlainText(plainText, MAX_SSML_PLAIN_TEXT_LENGTH).forEach {
                result.add(ensureSpeakTags(escapeSSMLText(it.trim())))
            }
        }
        regex.findAll(ssmlContent).forEach { matchResult ->
            addPlainContent(ssmlContent.substring(lastEnd, matchResult.range.first))
            result.addAll(splitLongSSMLBlock(matchResult.value.trim()))
            lastEnd = matchResult.range.last + 1
        }
        addPlainContent(ssmlContent.substring(lastEnd))
        val title = chapterTitle?.trim().orEmpty()
        if (title.isNotBlank()) {
            val firstPlainText = result.firstOrNull()?.let { stripReadAloudTags(it).trim() }.orEmpty()
            if (!firstPlainText.startsWith(title)) {
                result.add(0, ensureSpeakTags(escapeSSMLText(title)))
            }
        }
        
        logInfo("parse source=$logSource segments=${result.size}")
        if (result.isNotEmpty()) {
            logInfo("parse source=$logSource first=${result[0].logPreview()}")
        }
        
        return result
    }

    private fun splitLongSSMLBlock(ssmlBlock: String): List<String> {
        val plainLength = stripReadAloudTags(ssmlBlock).length
        if (plainLength <= MAX_SSML_PLAIN_TEXT_LENGTH) {
            return listOf(ssmlBlock)
        }
        val innerContent = ssmlBlock
            .replace(Regex("^<speak[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</speak>$", RegexOption.IGNORE_CASE), "")
        val chunks = splitSSMLInnerContent(innerContent, MAX_SSML_PLAIN_TEXT_LENGTH)
        logInfo("split plainLength=$plainLength chunks=${chunks.size}")
        return chunks.map { "<speak>${it.trim()}</speak>" }
    }

    private fun splitSSMLInnerContent(content: String, maxPlainLength: Int): List<String> {
        val result = mutableListOf<String>()
        val builder = StringBuilder()
        val activeTags = mutableListOf<Pair<String, String>>()
        var plainLength = 0
        fun tagName(token: String): String? {
            return Regex("^</?\\s*([\\w-]+)").find(token)?.groupValues?.getOrNull(1)
        }
        fun isClosingTag(token: String) = token.startsWith("</")
        fun isSelfClosingTag(token: String): Boolean {
            val name = tagName(token)
            return token.endsWith("/>") || name in setOf("break", "mark", "audio")
        }
        fun closeActiveTags() {
            activeTags.asReversed().forEach { builder.append("</").append(it.first).append(">") }
        }
        fun reopenActiveTags() {
            activeTags.forEach { builder.append(it.second) }
        }
        fun flushChunk() {
            closeActiveTags()
            result.add(builder.toString())
            builder.clear()
            reopenActiveTags()
            plainLength = 0
        }
        Regex("<[^>]+>|[^<]+").findAll(content).forEach { match ->
            val token = match.value
            if (token.startsWith("<")) {
                builder.append(token)
                val name = tagName(token)
                when {
                    name == null || name.equals("speak", ignoreCase = true) -> Unit
                    isClosingTag(token) -> {
                        val index = activeTags.indexOfLast { it.first.equals(name, ignoreCase = true) }
                        if (index >= 0) {
                            activeTags.removeAt(index)
                        }
                    }
                    !isSelfClosingTag(token) -> activeTags.add(name to token)
                }
            } else {
                splitPlainText(token, maxPlainLength).forEach { part ->
                    if (plainLength > 0 && plainLength + part.length > maxPlainLength) {
                        flushChunk()
                    }
                    builder.append(part)
                    plainLength += part.length
                }
            }
        }
        if (builder.isNotBlank()) {
            result.add(builder.toString())
        }
        return result.ifEmpty { listOf(content) }
    }

    private fun splitPlainText(text: String, maxLength: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val endLimit = (start + maxLength).coerceAtMost(text.length)
            val splitEnd = if (endLimit < text.length) {
                val punctuationIndex = text
                    .lastIndexOfAny(charArrayOf('。', '！', '？', '；', '，', '.', '!', '?', ';', ','), endLimit - 1)
                if (punctuationIndex >= start + maxLength / 2) punctuationIndex + 1 else endLimit
            } else {
                endLimit
            }
            result.add(text.substring(start, splitEnd))
            start = splitEnd
        }
        return result
    }

    private suspend fun buildRuleContentList(
        mode: String,
        httpTts: HttpTTS
    ): List<String> {
        val book = ReadBook.book ?: return contentList
        val chapter = textChapter?.chapter ?: return contentList
        val chapterContent = textChapter?.readAloudContent.orEmpty()
            .ifBlank { textChapter?.getContent().orEmpty() }
        if (chapterContent.isBlank()) {
            return contentList
        }

        val cacheResult = segmentCacheRepository.getOrBuildRuleSegments(
            book = book,
            chapter = chapter,
            chapterContent = chapterContent
        )
        if (cacheResult.segments.isEmpty()) {
            return contentList
        }
        logInfo(
            "cache mode=$mode cacheHit=${cacheResult.cacheHit} " +
                "chapterSegments=${cacheResult.segments.size}"
        )
        val aiSegments = tryAiLabelRuleSegments(
            mode = mode,
            book = book,
            chapter = chapter,
            chapterContent = chapterContent,
            ruleSegments = cacheResult.segments
        )
        val chapterSegments = aiSegments ?: cacheResult.segments
        val aiUsed = aiSegments != null

        val windowStart = if (readAloudByPage) {
            textChapter?.getReadLength(pageIndex) ?: 0
        } else {
            0
        }
        val windowEnd = if (readAloudByPage) {
            textChapter?.getReadLength(pageIndex + 1) ?: Int.MAX_VALUE
        } else {
            chapterContent.length
        }
        val windowedSegments = selectSegments(chapterSegments, windowStart, windowEnd)
        if (windowedSegments.isEmpty()) {
            return contentList
        }
        logAiDialogLabels(
            mode = mode,
            chapterIndex = chapter.index,
            aiUsed = aiUsed,
            segments = windowedSegments
        )
        val ttsSegments = windowedSegments.splitLongSegmentsForTts(httpTts)
        readAloudSegments = ttsSegments
        contentStartPositions = ttsSegments.map { it.startPosition }
        contentPlainLengths = ttsSegments.map { it.plainLength }
        AppLog.putDebug(
            "aiChapterParse segments mode=$mode ai=$aiUsed " +
                "chapterSegments=${chapterSegments.size} windowed=${windowedSegments.size} " +
                "ttsSegments=${ttsSegments.size} window=$windowStart..$windowEnd"
        )
        return ttsSegments.renderRuleContents(httpTts)
    }

    private fun logAiDialogLabels(
        mode: String,
        chapterIndex: Int,
        aiUsed: Boolean,
        segments: List<ReadAloudSegment>
    ) {
        if (!aiUsed) return
        val dialogs = segments.withIndex()
            .filter { it.value.role == ReadAloudRole.DIALOG }
        android.util.Log.i(
            AI_DIALOG_LABEL_TAG,
            "chapter=$chapterIndex mode=$mode dialogs=${dialogs.size} segments=${segments.size}"
        )
        dialogs.forEach { (index, segment) ->
            android.util.Log.i(
                AI_DIALOG_LABEL_TAG,
                "chapter=$chapterIndex mode=$mode segment=$index " +
                    "role=${segment.role} name=${segment.roleName.ifBlank { "对白" }} " +
                    "emotion=${segment.emotion.ifBlank { "neutral" }} " +
                    "intensity=${segment.emotionIntensity} " +
                    "start=${segment.startPosition} len=${segment.plainLength} " +
                    "hash=${MD5Utils.md5Encode16(segment.text)} preview=${segment.text.logPreview(40)}"
            )
        }
    }

    /**
     * MVP6C：规则先切分，AI 只标注对白的说话人/情绪。
     *
     * 仅当 AI 服务启用时调用 AiDialogLabeler；任何失败（含未启用）都返回 null，
     * 由上层继续使用规则分段。不在日志输出正文、Prompt、API Key。
     */
    private suspend fun tryAiLabelRuleSegments(
        mode: String,
        book: io.legado.app.data.entities.Book,
        chapter: io.legado.app.data.entities.BookChapter,
        chapterContent: String,
        ruleSegments: List<ReadAloudSegment>
    ): List<ReadAloudSegment>? {
        val config = io.legado.app.model.ai.AiServiceConfig.load()
        AppLog.putDebug(
            "aiChapterParse preCheck mode=$mode enabled=${config.enabled} " +
                "screenInteractive=${powerManager.isInteractive} timeoutSeconds=${config.timeoutSeconds}"
        )
        if (!config.enabled) {
            AppLog.putDebug("aiChapterParse skip mode=$mode reason=disabled")
            return null
        }
        val chapterIndex = chapter.index
        val result = io.legado.app.model.ai.AiDialogLabeler.labelRuleSegments(
            chapterIndex = chapterIndex,
            segments = ruleSegments,
            candidates = buildAiDialogCandidates(book, chapterContent)
        )
        if (result.segments.isEmpty()) return null
        logInfo(
            "aiLabel mode=$mode provider=${result.provider} model=${result.model} " +
                "fallback=${result.fallbackUsed} reason=${result.errorMessage ?: "ok"} " +
                "segments=${result.segments.size} elapsedMs=${result.elapsedMs}"
        )
        return if (result.fallbackUsed) null else result.segments
    }

    private fun buildAiDialogCandidates(
        book: io.legado.app.data.entities.Book,
        chapterContent: String
    ): List<String> {
        val candidates = linkedSetOf<String>()
        book.getReadAloudRoles().forEach { role ->
            role.name.takeIf { it.isNotBlank() }?.let(candidates::add)
            role.aliases.filter { it.isNotBlank() }.forEach(candidates::add)
        }
        Regex("([\\u4e00-\\u9fa5]{1,6})(?:拱手|笑|问|说|道|喊|叫|答|看向|询问)")
            .findAll(chapterContent)
            .map { it.groupValues[1] }
            .filter { it !in setOf("说道", "问道", "笑道", "答道") }
            .take(40)
            .forEach(candidates::add)
        return candidates.take(40)
    }

    private fun buildRuleSegments(
        sourceList: List<String>,
        mode: String,
        initialStart: Int = 0,
        startPositionProvider: (index: Int, runningStart: Int) -> Int = { _, runningStart ->
            runningStart
        }
    ): List<ReadAloudSegment> {
        val generatedSegments = mutableListOf<ReadAloudSegment>()
        var runningStart = initialStart
        sourceList.forEachIndexed { index, content ->
            val startPosition = startPositionProvider(index, runningStart)
            val plainText = stripReadAloudTags(content)
            if (plainText.isNotBlank()) {
                generatedSegments.addAll(
                    ReadAloudRuleSegmenter.segment(
                        text = plainText,
                        startPosition = startPosition
                    )
                )
            }
            runningStart = startPosition + plainText.length + 1
        }
        logInfo(
            "rule mode=$mode sourceSize=${sourceList.size} segments=${generatedSegments.size} " +
                    "dialog=${generatedSegments.count { it.role == ReadAloudRole.DIALOG }} " +
                    "narrator=${generatedSegments.count { it.role == ReadAloudRole.NARRATOR }}"
        )
        return generatedSegments
    }

    private fun buildRuleContentWithSegments(
        sourceList: List<String>,
        mode: String,
        httpTts: HttpTTS
    ): Pair<List<String>, List<ReadAloudSegment>> {
        val segments = buildRuleSegments(
            sourceList = sourceList,
            mode = mode
        )
        return if (segments.isEmpty()) {
            sourceList to emptyList()
        } else {
            val ttsSegments = segments.splitLongSegmentsForTts(httpTts)
            ttsSegments.renderRuleContents(httpTts) to ttsSegments
        }
    }

    private fun buildPreDownloadRuleContent(
        textChapter: TextChapter,
        chapterContent: String,
        httpTts: HttpTTS,
        mode: String
    ): Pair<List<String>, List<ReadAloudSegment>> {
        val book = ReadBook.book ?: return getPreDownloadContentList(textChapter, false) to emptyList()
        val chapter = textChapter.chapter
        val cacheResult = segmentCacheRepository.getOrBuildRuleSegments(
            book = book,
            chapter = chapter,
            chapterContent = chapterContent
        )
        if (cacheResult.segments.isEmpty()) {
            return getPreDownloadContentList(textChapter, false) to emptyList()
        }
        val preDownloadSegments = cacheResult.segments
            .take(PRE_DOWNLOAD_SEGMENT_LIMIT)
            .splitLongSegmentsForTts(httpTts)
        val contentList = preDownloadSegments.renderRuleContents(httpTts)
        logInfo(
            "cache mode=$mode cacheHit=${cacheResult.cacheHit} " +
                    "chapterSegments=${cacheResult.segments.size} preDownload=${preDownloadSegments.size}"
        )
        return contentList to preDownloadSegments
    }

    private fun List<ReadAloudSegment>.renderRuleContents(httpTts: HttpTTS): List<String> {
        return if (httpTts.isAliyunTTS()) {
            map { segment ->
                val providerSegment = httpTts.providerSegment(segment)
                    ?: ReadAloudProviderSegment(
                        provider = httpTts.providerName(),
                        voice = httpTts.providerNarratorVoice()
                    )
                AliyunSsmlRenderer.render(segment, providerSegment)
            }
        } else {
            map { it.text }
        }
    }

    private fun List<ReadAloudSegment>.splitLongSegmentsForTts(httpTts: HttpTTS): List<ReadAloudSegment> {
        val maxLength = when {
            httpTts.isAliyunTTS() -> MAX_SSML_PLAIN_TEXT_LENGTH
            else -> return this
        }
        val result = flatMap { segment -> segment.splitByPlainTextLength(maxLength) }
        val splitCount = result.size - size
        if (splitCount > 0) {
            AppLog.putDebug(
                "aiChapterParse segmentSplit provider=${httpTts.providerName()} " +
                    "before=${size} after=${result.size} splitCount=$splitCount maxPlain=$maxLength"
            )
        }
        return result
    }

    private fun ReadAloudSegment.splitByPlainTextLength(maxLength: Int): List<ReadAloudSegment> {
        if (plainLength <= maxLength || text.length <= maxLength) {
            return listOf(this)
        }
        return splitPlainTextWithOffsets(text, maxLength).map { (start, end) ->
            copy(
                text = text.substring(start, end),
                startPosition = startPosition + start,
                plainLength = end - start
            )
        }
    }

    private fun splitPlainTextWithOffsets(text: String, maxLength: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < text.length) {
            val endLimit = (start + maxLength).coerceAtMost(text.length)
            val splitEnd = if (endLimit < text.length) {
                val punctuationIndex = text
                    .lastIndexOfAny(charArrayOf('。', '！', '？', '；', '，', '.', '!', '?', ';', ','), endLimit - 1)
                if (punctuationIndex >= start + maxLength / 2) punctuationIndex + 1 else endLimit
            } else {
                endLimit
            }
            result.add(start to splitEnd)
            start = splitEnd
        }
        return result
    }

    private fun syncSSMLContentParagraphOffset() {
        val chapter = textChapter ?: return
        contentParagraphOffset = if (chapter.readAloudContent.isNotEmpty()) {
            0
        } else {
            val pageStartPos = chapter.getReadLength(pageIndex)
            chapter.getParagraphNum(pageStartPos + 1, false) - 1
        }
    }

    private fun findSegmentStartIndex(mode: String): Int {
        if (readAloudSegments.isEmpty()) {
            return findSSMLStartIndex(contentList)
        }
        val currentPos = readAloudNumber
        readAloudSegments.forEachIndexed { index, segment ->
            if (segment.plainLength <= 0) {
                return@forEachIndexed
            }
            val segmentEnd = segment.startPosition + segment.plainLength
            if (segmentEnd >= currentPos) {
                logInfo(
                    "find mode=$mode segment=$index start=${segment.startPosition} " +
                            "end=$segmentEnd role=${segment.role} emotion=${segment.emotion} " +
                            "intensity=${segment.emotionIntensity}"
                )
                return index
            }
        }
        val fallbackIndex = textChapter
            ?.getParagraphNum(currentPos + 1, false)
            ?.minus(1)
            ?: 0
        logInfo("find mode=$mode fallback=paragraph pos=$currentPos paragraph=$fallbackIndex")
        return fallbackIndex.coerceIn(0, contentList.lastIndex)
    }
    
    /**
     * 根据当前阅读位置查找SSML内容中的起始索引
     */
    private fun findSSMLStartIndex(ssmlContentList: List<String>): Int {
        val textChapter = this.textChapter ?: return 0
        
        // 检查是否使用了全章节的SSML内容
        val isFullChapter = textChapter.readAloudContent.isNotEmpty()
        val chapterContent = textChapter.getContent()
        var searchFrom = if (isFullChapter) {
            0
        } else {
            textChapter.getReadLength(pageIndex)
        }
        ssmlContentList.forEachIndexed { index, content ->
            val plainText = stripReadAloudTags(content)
            if (plainText.isBlank()) return@forEachIndexed
            val foundIndex = findPlainTextStartInChapter(chapterContent, plainText, searchFrom)
            if (foundIndex != null) {
                val blockEnd = foundIndex + plainText.length
                if (blockEnd >= readAloudNumber) {
                    readAloudNumber = foundIndex
                    logInfo("find matched index=$index start=$foundIndex end=$blockEnd")
                    return index
                }
                searchFrom = blockEnd.coerceAtLeast(searchFrom)
            }
        }
        
        // 获取当前阅读位置在章节中的绝对段落索引
        val absoluteParagraphIndex = textChapter.getParagraphNum(readAloudNumber + 1, false) - 1
        
        if (isFullChapter) {
            logInfo("find fallback=fullChapter pos=$readAloudNumber paragraph=$absoluteParagraphIndex")
            return absoluteParagraphIndex.coerceIn(0, ssmlContentList.lastIndex)
        } else {
            // 非全章节模式（单页模式）
            val pageStartPos = textChapter.getReadLength(pageIndex)
            val pageStartParagraphIndex = textChapter.getParagraphNum(pageStartPos + 1, false) - 1
            
            // 计算相对于当前页面列表的索引
            val relativeIndex = absoluteParagraphIndex - pageStartParagraphIndex
            
            logInfo("find fallback=page pos=$readAloudNumber pageStart=$pageStartPos relative=$relativeIndex")
            return relativeIndex.coerceIn(0, ssmlContentList.lastIndex)
        }
    }

    private fun buildNextChapterAudioTasks(
        httpTts: HttpTTS,
        nextTextChapter: TextChapter,
        generation: Int,
        mode: String
    ): List<ReadAloudAudioTask> {
        val chapterContent = nextTextChapter.readAloudContent.ifBlank { nextTextChapter.getContent() }
        val chapterHasSSML = shouldUseExistingSSML(chapterContent)
        val useRuleSegments = shouldUseRuleGeneratedSegments(httpTts, chapterHasSSML)
        val (contentList, preDownloadSegments) = if (useRuleSegments) {
            buildPreDownloadRuleContent(
                textChapter = nextTextChapter,
                chapterContent = chapterContent,
                httpTts = httpTts,
                mode = "nextChapter:$mode:${nextTextChapter.title}"
            )
        } else {
            getPreDownloadContentList(nextTextChapter, chapterHasSSML) to emptyList()
        }
        val useExistingSSML = shouldUseExistingSSML(chapterContent, contentList)
        val usePlainTextPreprocess = shouldUseAliyunPlainTextPreprocess(
            httpTts,
            chapterContent,
            contentList
        )
        return contentList.take(PRE_DOWNLOAD_SEGMENT_LIMIT).mapIndexed { index, content ->
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val finalSpeakText = when {
                useExistingSSML || (useRuleSegments && httpTts.isAliyunTTS()) -> normalizeAliyunSSML(speakText)
                usePlainTextPreprocess -> preprocessAliyunPlainText(speakText)
                else -> speakText
            }
            val segment = preDownloadSegments.getOrNull(index)
            val providerSegment = httpTts.providerSegment(segment)
            val fileName = md5SpeakFileName(
                finalSpeakText,
                nextTextChapter,
                segment,
                providerSegment
            )
            ReadAloudAudioTask(
                generation = generation,
                chapterIndex = nextTextChapter.chapter.index,
                segmentIndex = index,
                speakText = finalSpeakText,
                originalContent = content,
                fileName = fileName,
                useSSMLRequest = useExistingSSML ||
                        (useRuleSegments && httpTts.isAliyunTTS()) ||
                        usePlainTextPreprocess,
                segment = segment,
                providerSegment = providerSegment
            )
        }
    }

    private fun getPreDownloadContentList(
        textChapter: TextChapter,
        useExistingSSML: Boolean
    ): List<String> {
        if (useExistingSSML && textChapter.readAloudContent.isNotBlank()) {
            parseSSMLContent(
                ssmlContent = textChapter.readAloudContent,
                chapterTitle = textChapter.title,
                logSource = "predownload:${textChapter.title}"
            ).takeIf { it.isNotEmpty() }?.let {
                return it
            }
        }
        return textChapter.getNeedReadAloud(0, false, 0)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(PRE_DOWNLOAD_SEGMENT_LIMIT)
            .toList()
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        cancelAllPrefetchTasks()
        audioGeneration++
        val generation = audioGeneration
        acquireReadAloudPrepareWakeLock()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")

                currentChapterTasks = buildAudioTasks(httpTts, "stream")
                val startIndex = nowSpeak.coerceIn(0, maxOf(0, currentChapterTasks.lastIndex))
                if (currentChapterTasks.isEmpty()) {
                    releaseReadAloudPrepareWakeLock()
                    nextChapter()
                    return@withLock
                }
                prefetchWindowStart = startIndex
                AppLog.putDebug(
                    "aiChapterParse audioTaskBatch selected mode=stream generation=$generation " +
                        "tasks=${currentChapterTasks.size} start=$startIndex " +
                        "last=${currentChapterTasks.lastIndex}"
                )

                android.util.Log.i(AUDIO_PREFETCH_TAG,
                    "generation=$generation chapter=$chapterIndex tasks=${currentChapterTasks.size} " +
                            "start=$startIndex window=$startIndex..${minOf(startIndex + CURRENT_CHAPTER_PREFETCH_COUNT, currentChapterTasks.lastIndex)} mode=stream")

                // Add current + prefetch count MediaSources to ExoPlayer
                val windowEnd = minOf(startIndex + CURRENT_CHAPTER_PREFETCH_COUNT, currentChapterTasks.lastIndex)
                for (i in startIndex..windowEnd) {
                    val task = currentChapterTasks[i]
                    val dataSourceFactory = createDataSourceFactory(
                        httpTts,
                        task.speakText.takeIf { hasSpeakableText(it) }.orEmpty(),
                        task.segment,
                        task.providerSegment
                    )
                    val mediaId = registerPlaybackMedia(task)
                    queuedSegmentIndices.add(task.segmentIndex)
                    val mediaSource = createMediaSource(dataSourceFactory, task.fileName, mediaId)
                    lifecycleScope.launch(Main) {
                        if (audioGeneration == generation) {
                            exoPlayer.addMediaSource(mediaSource)
                        }
                    }
                }

                prefetchEnabled = true
                maybePrepareNextChapter(
                    currentChapterTasks[startIndex].segment?.startPosition ?: readAloudNumber,
                    "stream"
                )
            }
        }.onError {
            releaseReadAloudPrepareWakeLock()
            AppLog.put(
                "aiChapterParse ttsDownloadError stage=streamPrepare message=${it.localizedMessage.orEmpty()}\n" +
                    "朗读下载出错\n${it.localizedMessage}",
                it,
                true
            )
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String,
        readAloudSegment: ReadAloudSegment? = null,
        readAloudProviderSegment: ReadAloudProviderSegment? = null,
        fallbackToSilent: Boolean = true
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                val inputStream = if (!hasSpeakableText(speakText)) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(
                                httpTts,
                                speakText,
                                readAloudSegment,
                                readAloudProviderSegment
                            )
                        }
                    }.recoverCatching { error ->
                        if (error is InterruptedException || error is CancellationException) {
                            throw error
                        }
                        val plainText = stripReadAloudTags(speakText)
                            .replace(AppPattern.notReadAloudRegex, "")
                            .trim()
                        if (plainText != speakText && hasSpeakableText(plainText)) {
                            AppLog.put(
                                "aiChapterParse ttsFallback reason=streamSsmlFailed " +
                                    "message=${error.localizedMessage.orEmpty()}\n" +
                                    "SSML流式合成失败，回退普通文本朗读。\n${error.localizedMessage}",
                                error
                            )
                            runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                                getSpeakStream(
                                    httpTts,
                                    plainText,
                                    readAloudSegment,
                                    readAloudProviderSegment
                                )
                            }
                        } else {
                            throw error
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit
                            else -> if (fallbackToSilent) {
                                AppLog.put(
                                    "aiChapterParse ttsSilentFallback reason=streamDownloadFailed " +
                                        "message=${it.localizedMessage.orEmpty()}\n" +
                                        "流式TTS下载音频出错，使用无声音频代替。\n${it.localizedMessage}",
                                    it
                                )
                            }
                        }
                    }.getOrElse { error ->
                        if (fallbackToSilent) null else throw error
                    }
                }
                inputStream ?: if (fallbackToSilent) {
                    resources.openRawResource(R.raw.silent_sound)
                } else {
                    throw NoStackTraceException("下一章音频预处理未返回有效音频")
                }
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String, mediaId: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(fileName)
                    .setMediaId(mediaId)
                    .build()
            )
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        readAloudSegment: ReadAloudSegment? = null,
        readAloudProviderSegment: ReadAloudProviderSegment? = null
    ): InputStream? {
        while (true) {
            try {
                val providerSegment = readAloudProviderSegment ?: httpTts.providerSegment(readAloudSegment)
                logTtsRequest(speakText, readAloudSegment, providerSegment)
                logInfo(
                    "ttsConfig id=${httpTts.id} name=${httpTts.name} " +
                            "urlHash=${MD5Utils.md5Encode16(httpTts.url.orEmpty())} " +
                            "hasResourceId=${httpTts.url.orEmpty().contains("ResourceId")} " +
                            "contentType=${httpTts.contentType.orEmpty()} " +
                            "responseType=${httpTts.responseType.orEmpty()} " +
                            "audioPath=${httpTts.audioPath.orEmpty()} " +
                            readAloudSegment.debugSummary(providerSegment)
                )
                
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    readAloudSegment = readAloudSegment,
                    readAloudProviderSegment = providerSegment,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                logInfo(
                    "response contentType=${response.headers["Content-Type"].orEmpty()} " +
                            "code=${response.code} message=${response.message}"
                )
                if (!response.isSuccessful) {
                    val errorBody = try {
                        response.body.string()
                    } catch (ex: Exception) {
                        "Unable to read error body: ${ex.message}"
                    }
                    throw NoStackTraceException("TTS服务器返回错误 code=${response.code}, body=$errorBody")
                }
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { contentTypeHeader ->
                    val contentType = contentTypeHeader.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        val bodyText = response.body.string()
                        val inputStream = getAudioStreamFromTextResponse(httpTts, bodyText)
                        if (inputStream != null) {
                            downloadErrorNo = 0
                            return inputStream
                        }
                        throw NoStackTraceException(bodyText)
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put(
                            "aiChapterParse ttsJsError message=${e.localizedMessage.orEmpty()}\n" +
                                "js错误\n${e.localizedMessage}",
                            e,
                            true
                        )
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "aiChapterParse ttsDownloadError reason=timeoutOrConnectOverLimit " +
                                "count=$downloadErrorNo message=${e.localizedMessage.orEmpty()}\n" +
                                "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "aiChapterParse ttsDownloadError reason=requestFailed " +
                            "count=$downloadErrorNo exception=${e.javaClass.simpleName} " +
                            "message=${e.localizedMessage.orEmpty()}\n" +
                            "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "aiChapterParse ttsDownloadError reason=serverErrorOverLimit " +
                                "count=$downloadErrorNo message=${e.localizedMessage.orEmpty()}\n" +
                                "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put(
                                "aiChapterParse ttsSilentFallback reason=downloadFailed " +
                                    "count=$downloadErrorNo message=${e.localizedMessage.orEmpty()}\n" +
                                    "TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText",
                                e
                            )
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun getAudioStreamFromTextResponse(
        httpTts: HttpTTS,
        bodyText: String
    ): InputStream? {
        val responseType = httpTts.responseType?.trim().orEmpty()
        if (responseType.isBlank() || responseType.equals("raw", true)) {
            return null
        }
        if (responseType.equals("jsonLinesBase64", true)
            || responseType.equals("jsonStreamBase64", true)
        ) {
            return ByteArrayInputStream(decodeBase64JsonChunks(bodyText))
        }
        val audioText = httpTts.audioPath
            ?.takeIf { it.isNotBlank() }
            ?.let { jsonPath.parse(bodyText).readString(it) }
            ?: bodyText
        if (audioText.isBlank()) {
            throw NoStackTraceException("TTS服务器返回中未找到音频字段：${httpTts.audioPath}")
        }
        return when {
            responseType.equals("jsonHex", true) || responseType.equals("hex", true) -> {
                ByteArrayInputStream(hexToBytes(audioText))
            }
            responseType.equals("jsonBase64", true) || responseType.equals("base64", true) -> {
                ByteArrayInputStream(Base64.decode(audioText, Base64.DEFAULT))
            }
            responseType.equals("jsonUrl", true) || responseType.equals("url", true) -> {
                downloadAudioUrl(audioText)
            }
            else -> throw NoStackTraceException("不支持的TTS响应类型：$responseType")
        }
    }

    private fun decodeBase64JsonChunks(bodyText: String): ByteArray {
        val out = ByteArrayOutputStream()
        var chunkCount = 0
        for (json in extractJsonObjects(bodyText)) {
            val obj = JSONObject(json)
            val code = obj.optInt("code", 0)
            if (code != 0 && code != 20000000) {
                throw NoStackTraceException(
                    "TTS服务器返回错误 code=$code, message=${obj.optString("message")}"
                )
            }
            if (!obj.isNull("data")) {
                val data = obj.optString("data")
                if (data.isNotBlank()) {
                    out.write(Base64.decode(data, Base64.DEFAULT))
                    chunkCount++
                }
            }
        }
        if (chunkCount == 0 || out.size() == 0) {
            throw NoStackTraceException("TTS服务器返回中未找到可用的Base64音频分片")
        }
        logInfo("decoded jsonLinesBase64 chunks=$chunkCount bytes=${out.size()}")
        return out.toByteArray()
    }

    private fun extractJsonObjects(text: String): List<String> {
        val objects = arrayListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                !inString && char == '}' && depth > 0 -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(text.substring(start, index + 1))
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.trim().removePrefix("0x").filterNot { it.isWhitespace() }
        if (cleanHex.length % 2 != 0) {
            throw NoStackTraceException("TTS音频hex长度不正确")
        }
        return ByteArray(cleanHex.length / 2) { index ->
            cleanHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun downloadAudioUrl(url: String): InputStream {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw NoStackTraceException("TTS音频URL下载失败：${response.code}")
        }
        return response.body.byteStream()
    }

    private fun md5SpeakFileName(
        content: String,
        textChapter: TextChapter? = this.textChapter,
        readAloudSegment: ReadAloudSegment? = null,
        readAloudProviderSegment: ReadAloudProviderSegment? = null
    ): String {
        val httpTts = ReadAloud.httpTTS
        val segmentKey = readAloudSegment?.let {
            "${it.eventType}-|-${it.role}-|-${it.roleName}-|-${it.emotion}-|-${it.emotionIntensity}"
        }.orEmpty()
        val providerKey = readAloudProviderSegment?.let {
            "${it.provider}-|-${it.voice}-|-${it.styleTag}-|-${it.extra}"
        }.orEmpty()
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16(
                    "${httpTts?.url}-|-${httpTts?.contentType}-|-${httpTts?.responseType}" +
                            "-|-${httpTts?.audioPath}-|-${httpTts?.getLoginInfo()}" +
                            "-|-${httpTts?.getLoginHeader()}-|-$speechRate-|-$segmentKey" +
                            "-|-$providerKey-|-$content"
                )
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
        AppLog.putDebug(
            "aiChapterParse audioCreate silent key=${audioLogKey(fileName)} fileSize=${file.length()}"
        )
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            if (exoPlayer.duration <= 0) {
                upTtsProgress(readAloudNumber + 1)
                return@launch
            }
            val content = contentList.getOrNull(nowSpeak) ?: return@launch
            val speakTextLength = contentPlainLength(nowSpeak)
            if (speakTextLength <= 0) {
                upTtsProgress(readAloudNumber + 1)
                return@launch
            }
            
            val duration = exoPlayer.duration
            val sleep = (duration / speakTextLength).coerceAtLeast(10)
            val start = (speakTextLength * exoPlayer.currentPosition / duration).toInt()
            val contentStart = contentStartPosition(nowSpeak) ?: readAloudNumber
            
            var lastUpdateMillis = 0L
            for (i in start..speakTextLength) {
                val currentProgress = contentStart + i
                
                // 仅在跨页或间隔 500ms 以上时上报进度，防止 UI 渲染过载
                val now = System.currentTimeMillis()
                if (now - lastUpdateMillis > 500 || i == start) {
                    upTtsProgress(currentProgress + 1)
                    lastUpdateMillis = now
                }

                if (pageIndex + 1 < textChapter.pageSize
                    && currentProgress > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        cancelAllPrefetchTasks()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                releaseReadAloudPrepareWakeLock()
                AppLog.putDebug(
                    "aiChapterParse playbackReady segment=$nowSpeak " +
                        "durationMs=${exoPlayer.duration} positionMs=${exoPlayer.currentPosition} " +
                        "mediaKey=${playbackFileKey(exoPlayer.currentMediaItem?.mediaId)} " +
                        "contentLen=${contentPlainLength(nowSpeak)}"
                )
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                AppLog.putDebug(
                    "aiChapterParse playbackEnded segment=$nowSpeak " +
                        "durationMs=${exoPlayer.duration} positionMs=${exoPlayer.currentPosition} " +
                        "mediaKey=${playbackFileKey(exoPlayer.currentMediaItem?.mediaId)} " +
                        "contentLen=${contentPlainLength(nowSpeak)}"
                )
                playErrorNo = 0
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                if (nowSpeak < contentList.lastIndex) {
                    continueCurrentChapterFromIndex(nowSpeak + 1)
                } else {
                    nextChapter()
                }
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        loadingState.value = isLoading
        if (isLoading && nextChapterPrepareJob?.isActive == true) {
            cancelNextChapterPrepare("currentChapterLoading")
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val mediaId = mediaItem?.mediaId
        val tagIndex = mediaId?.let { mediaIdToSegmentIndex[it] }
        if (mediaId != null && mediaId != lastLoggedPlaybackMediaId) {
            lastLoggedPlaybackMediaId = mediaId
            val task = tagIndex?.let { currentChapterTasks.getOrNull(it) }
            val message = "chapter=$chapterIndex segment=${tagIndex ?: -1} reason=$reason " +
                    "textHash=${task?.speakText?.let { MD5Utils.md5Encode16(it) }.orEmpty()} " +
                    "key=${task?.fileName?.let(::audioLogKey) ?: mediaId.takeLast(8)} " +
                    "preview=${task?.speakText?.logPreview(40).orEmpty()}"
            android.util.Log.i(AUDIO_PLAYBACK_TAG, message)
            AppLog.putDebug("aiChapterParse playbackTransition $message")
            logInfo("audioPlayback $message")
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        if (tagIndex != null && currentChapterTasks.isNotEmpty()) {
            nowSpeak = tagIndex
            readAloudNumber = contentStartPosition(tagIndex)
                ?: (readAloudNumber + contentPlainLength((tagIndex - 1).coerceAtLeast(0)) + 1)
            val mode = if (AppConfig.streamReadAloudAudio) "stream" else "file"
            advancePrefetchWindow(tagIndex, audioGeneration, mode)
            maybePrepareNextChapter(readAloudNumber, mode)
        } else {
            updateNextPos()
        }
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                continueCurrentChapterFromIndex(nowSpeak + 1)
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
