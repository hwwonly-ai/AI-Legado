package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.util.Base64
import android.net.Uri
import android.os.Looper
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
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

private const val MAX_SSML_PLAIN_TEXT_LENGTH = 280
private const val PRE_DOWNLOAD_SEGMENT_LIMIT = 3
private const val SSML_LOG_TAG = "SSML_TTS"
private const val LOG_PREVIEW_LENGTH = 80

private fun logInfo(message: String) {
    android.util.Log.i(SSML_LOG_TAG, message)
}

private fun logWarn(message: String) {
    android.util.Log.w(SSML_LOG_TAG, message)
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
    text: String
) {
    logInfo(
        "segment mode=$mode index=$index ${text.ssmlDebugSummary()} " +
                "plainLength=${text.plainTextForLog().length} " +
                "hash=${MD5Utils.md5Encode16(text)} preview=${text.logPreview()}"
    )
}

private fun logTtsRequest(speakText: String) {
    logInfo(
        "request ${speakText.ssmlDebugSummary()} textLength=${speakText.length} " +
                "hash=${MD5Utils.md5Encode16(speakText)} " +
                "preview=${speakText.logPreview()}"
    )
}

private fun String.ssmlDebugSummary(): String {
    val speakVoice = Regex("<speak\\b[^>]*\\svoice\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    val voiceTag = Regex("<voice\\b[^>]*(?:\\sname\\s*=\\s*['\"]([^'\"]+)['\"])?", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    val emotion = Regex("<emotion\\b[^>]*\\scategory\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    return "ssml=${hasSSMLTags(this)} speakVoice=${speakVoice.ifBlank { "-" }} " +
            "voiceTag=${voiceTag.ifBlank { "-" }} emotion=${emotion.ifBlank { "-" }}"
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
 * 保留阿里云 SSML 原始标签，只补齐缺失的 speak 外壳。
 * 阿里云支持 <speak voice="xxx">，不能改写成其他音色标签格式。
 */
private fun normalizeAliyunSSML(text: String): String {
    return ensureSpeakTags(text)
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
    private val loadingState = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
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

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val chapterContent = textChapter?.readAloudContent.orEmpty()
                    .ifBlank { textChapter?.getContent().orEmpty() }
                val useExistingSSML = shouldUseExistingSSML(chapterContent, contentList)
                val usePlainTextPreprocess = shouldUseAliyunPlainTextPreprocess(
                    httpTts,
                    chapterContent,
                    contentList
                )
                logModeDecision(
                    mode = "file",
                    httpTts = httpTts,
                    chapterContent = chapterContent,
                    contentList = contentList,
                    useExistingSSML = useExistingSSML,
                    usePlainTextPreprocess = usePlainTextPreprocess
                )
                
                logReadAloudStart(
                    mode = "file",
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
                
                // 获取处理后的内容列表（包含SSML标签或普通文本）
                val processContentList = if (useExistingSSML) {
                    mergeSSMLParagraphs(contentList)
                } else {
                    contentList
                }
                
                // 计算起始段落索引
                val startIndex = if (useExistingSSML && processContentList.isNotEmpty()) {
                    findSSMLStartIndex(processContentList)
                } else {
                    nowSpeak.coerceAtLeast(0)
                }.let { safeStartIndex(it, processContentList.size, "file") }

                if (useExistingSSML) {
                    contentList = processContentList
                    nowSpeak = startIndex
                    // 同步 readAloudNumber 到段落起始位置，确保进度条和定位准确
                    textChapter?.let {
                        val paragraphs = it.getParagraphs(false)
                        val isFullChapter = it.readAloudContent.isNotEmpty()
                        val absoluteIndex = if (isFullChapter) {
                            contentParagraphOffset = 0
                            -1
                        } else {
                            val pageStartPos = it.getReadLength(pageIndex)
                            val pageStartParagraphIndex = it.getParagraphNum(pageStartPos + 1, false) - 1
                            contentParagraphOffset = pageStartParagraphIndex
                            pageStartParagraphIndex + startIndex
                        }
                        
                        if (absoluteIndex in paragraphs.indices) {
                            val targetPos = paragraphs[absoluteIndex].chapterPosition
                            // 只有当偏差较大时才同步，避免小范围内的回弹
                            if (Math.abs(readAloudNumber - targetPos) > 100 || readAloudNumber < targetPos) {
                                readAloudNumber = targetPos
                                logInfo("sync mode=file paragraph=$absoluteIndex pos=$readAloudNumber")
                            }
                        }
                    }
                    // 重置偏移量，因为 SSML 块始终从头朗读
                    paragraphStartPos = 0
                    rebuildContentPositionMap()
                }
                
                logReadAloudResolved(
                    mode = "file",
                    startIndex = startIndex,
                    readAloudNumber = readAloudNumber,
                    sourceSize = contentList.size,
                    resolvedSize = processContentList.size
                )

                processContentList.forEachIndexed { index, content ->
                    ensureActive()
                    
                    if (index < startIndex) {
                        return@forEachIndexed
                    }
                    
                    val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                    
                    val finalSpeakText = when {
                        useExistingSSML -> normalizeAliyunSSML(speakText)
                        usePlainTextPreprocess -> preprocessAliyunPlainText(speakText)
                        else -> speakText
                    }
                    val fileName = md5SpeakFileName(finalSpeakText)
                    
                    logSegmentQueued("file", index, finalSpeakText)
                    
                    if (!hasSpeakableText(finalSpeakText)) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$content")
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        createSpeakFileWithFallback(
                            httpTts = httpTts,
                            fileName = fileName,
                            speakText = finalSpeakText,
                            originalContent = content,
                            useSSMLRequest = useExistingSSML || usePlainTextPreprocess
                        )
                    }
                    val file = getSpeakFileAsMd5(fileName)
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    launch(Main) {
                        exoPlayer.addMediaItem(mediaItem)
                    }
                }
                preDownloadAudios(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun createSpeakFileWithFallback(
        httpTts: HttpTTS,
        fileName: String,
        speakText: String,
        originalContent: String,
        useSSMLRequest: Boolean
    ) {
        runCatching {
            getSpeakStream(httpTts, speakText)
        }.recoverCatching { error ->
            if (error is CancellationException) {
                throw error
            }
            if (useSSMLRequest) {
                val plainText = stripReadAloudTags(speakText)
                    .replace(AppPattern.notReadAloudRegex, "")
                    .trim()
                if (hasSpeakableText(plainText)) {
                    AppLog.put("SSML合成失败，回退普通文本朗读。\n${error.localizedMessage}")
                    return@recoverCatching getSpeakStream(httpTts, plainText)
                }
            }
            throw error
        }.onSuccess { inputStream ->
            if (inputStream != null) {
                createSpeakFile(fileName, inputStream)
            } else {
                createSilentSound(fileName)
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$originalContent")
            createSilentSound(fileName)
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

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val chapterContent = textChapter.readAloudContent.ifBlank { textChapter.getContent() }
        val chapterHasSSML = shouldUseExistingSSML(chapterContent)
        val contentList = getPreDownloadContentList(textChapter, chapterHasSSML)
            .take(PRE_DOWNLOAD_SEGMENT_LIMIT)
        val useExistingSSML = shouldUseExistingSSML(chapterContent, contentList)
        val usePlainTextPreprocess = shouldUseAliyunPlainTextPreprocess(
            httpTts,
            chapterContent,
            contentList
        )
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val finalSpeakText = when {
                useExistingSSML -> normalizeAliyunSSML(speakText)
                usePlainTextPreprocess -> preprocessAliyunPlainText(speakText)
                else -> speakText
            }
            val fileName = md5SpeakFileName(finalSpeakText, textChapter)
            if (!hasSpeakableText(finalSpeakText)) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                createSpeakFileWithFallback(
                    httpTts = httpTts,
                    fileName = fileName,
                    speakText = finalSpeakText,
                    originalContent = content,
                    useSSMLRequest = useExistingSSML || usePlainTextPreprocess
                )
            }
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
        return textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val chapterContent = textChapter?.readAloudContent.orEmpty()
                    .ifBlank { textChapter?.getContent().orEmpty() }
                val useExistingSSML = shouldUseExistingSSML(chapterContent, contentList)
                val usePlainTextPreprocess = shouldUseAliyunPlainTextPreprocess(
                    httpTts,
                    chapterContent,
                    contentList
                )
                logModeDecision(
                    mode = "stream",
                    httpTts = httpTts,
                    chapterContent = chapterContent,
                    contentList = contentList,
                    useExistingSSML = useExistingSSML,
                    usePlainTextPreprocess = usePlainTextPreprocess
                )
                
                logReadAloudStart(
                    mode = "stream",
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
                
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                
                // 获取处理后的内容列表（包含SSML标签或普通文本）
                val processContentList = if (useExistingSSML) {
                    mergeSSMLParagraphs(contentList)
                } else {
                    contentList
                }
                
                // 计算起始段落索引
                val startIndex = if (useExistingSSML && processContentList.isNotEmpty()) {
                    findSSMLStartIndex(processContentList)
                } else {
                    nowSpeak.coerceAtLeast(0)
                }.let { safeStartIndex(it, processContentList.size, "stream") }
                logReadAloudResolved(
                    mode = "stream",
                    startIndex = startIndex,
                    readAloudNumber = readAloudNumber,
                    sourceSize = contentList.size,
                    resolvedSize = processContentList.size
                )

                if (useExistingSSML) {
                    contentList = processContentList
                    nowSpeak = startIndex
                    // 同步 readAloudNumber 到段落起始位置
                    textChapter?.let {
                        val paragraphs = it.getParagraphs(false)
                        val isFullChapter = it.readAloudContent.isNotEmpty()
                        val absoluteIndex = if (isFullChapter) {
                            contentParagraphOffset = 0
                            -1
                        } else {
                            val pageStartPos = it.getReadLength(pageIndex)
                            val pageStartParagraphIndex = it.getParagraphNum(pageStartPos + 1, false) - 1
                            contentParagraphOffset = pageStartParagraphIndex
                            pageStartParagraphIndex + startIndex
                        }
                        
                        if (absoluteIndex in paragraphs.indices) {
                            val targetPos = paragraphs[absoluteIndex].chapterPosition
                            if (Math.abs(readAloudNumber - targetPos) > 100 || readAloudNumber < targetPos) {
                                readAloudNumber = targetPos
                                logInfo("sync mode=stream paragraph=$absoluteIndex pos=$readAloudNumber")
                            }
                        }
                    }
                    paragraphStartPos = 0
                    rebuildContentPositionMap()
                }

                processContentList.forEachIndexed { index, content ->
                    ensureActive()
                    
                    if (index < startIndex) {
                        return@forEachIndexed
                    }
                    
                    val speakText = content.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
                    }
                    
                    val finalSpeakText = when {
                        useExistingSSML -> normalizeAliyunSSML(speakText)
                        usePlainTextPreprocess -> preprocessAliyunPlainText(speakText)
                        else -> speakText
                    }
                    
                    logSegmentQueued("stream", index, finalSpeakText)
                    
                    if (!hasSpeakableText(finalSpeakText)) {
                        AppLog.put("阅读段落无可朗读文字，使用无声音频代替。\n朗读文本：$content")
                    }
                    val fileName = md5SpeakFileName(finalSpeakText)
                    val dataSourceFactory = createDataSourceFactory(
                        httpTts,
                        finalSpeakText.takeIf { hasSpeakableText(it) }.orEmpty()
                    )
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) {
                        exoPlayer.addMediaSource(mediaSource)
                    }
                }
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val chapterContent = textChapter.readAloudContent.ifBlank { textChapter.getContent() }
        val chapterHasSSML = shouldUseExistingSSML(chapterContent)
        val contentList = getPreDownloadContentList(textChapter, chapterHasSSML)
            .take(PRE_DOWNLOAD_SEGMENT_LIMIT)
        val useExistingSSML = shouldUseExistingSSML(chapterContent, contentList)
        val usePlainTextPreprocess = shouldUseAliyunPlainTextPreprocess(
            httpTts,
            chapterContent,
            contentList
        )
        val flow = loadingState.debounce(1.seconds)
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val finalSpeakText = when {
                useExistingSSML -> normalizeAliyunSSML(speakText)
                usePlainTextPreprocess -> preprocessAliyunPlainText(speakText)
                else -> speakText
            }
            val fileName = md5SpeakFileName(finalSpeakText, textChapter)
            val dataSourceFactory = createDataSourceFactory(httpTts, finalSpeakText)
            val downloader = createDownloader(dataSourceFactory, fileName)
            downloaderChannel.send(downloader)
            flow.first { !it }
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (!hasSpeakableText(speakText)) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText)
                        }
                    }.recoverCatching { error ->
                        if (error is InterruptedException || error is CancellationException) {
                            throw error
                        }
                        val plainText = stripReadAloudTags(speakText)
                            .replace(AppPattern.notReadAloudRegex, "")
                            .trim()
                        if (plainText != speakText && hasSpeakableText(plainText)) {
                            AppLog.put("SSML流式合成失败，回退普通文本朗读。\n${error.localizedMessage}")
                            runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                                getSpeakStream(httpTts, plainText)
                            }
                        } else {
                            throw error
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit
                            else -> AppLog.put("流式TTS下载音频出错，使用无声音频代替。\n${it.localizedMessage}")
                        }
                    }.getOrNull()
                } ?: resources.openRawResource(R.raw.silent_sound)
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

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        while (true) {
            try {
                logTtsRequest(speakText)
                
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                logInfo(
                    "response contentType=${response.headers["Content-Type"].orEmpty()} " +
                            "code=${response.code} message=${response.message}"
                )
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        val inputStream = getAudioStreamFromTextResponse(httpTts, response)
                        if (inputStream != null) {
                            downloadErrorNo = 0
                            return inputStream
                        }
                        throw NoStackTraceException(response.body.string())
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
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
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
        response: Response
    ): InputStream? {
        val responseType = httpTts.responseType?.trim().orEmpty()
        if (responseType.isBlank() || responseType.equals("raw", true)) {
            return null
        }
        val bodyText = response.body.string()
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
        textChapter: TextChapter? = this.textChapter
    ): String {
        val httpTts = ReadAloud.httpTTS
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16(
                    "${httpTts?.url}-|-${httpTts?.contentType}-|-${httpTts?.responseType}" +
                            "-|-${httpTts?.audioPath}-|-${httpTts?.getLoginInfo()}" +
                            "-|-${httpTts?.getLoginHeader()}-|-$speechRate-|-$content"
                )
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
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
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
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
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
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
                updateNextPos()
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
