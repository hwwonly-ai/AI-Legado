@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudProgress
import io.legado.app.model.ReadAloudSegment
import io.legado.app.model.ReadBook
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var timeMinute: Int = 0
            private set

        @JvmStatic
        var service: BaseReadAloudService? = null
            private set

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        private const val TAG = "BaseReadAloudService"

    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val prepareWakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudPrepare")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private var prepareWakeLockAcquireCount = 0
    private var prepareWifiLockAcquireCount = 0
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat: MediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    internal var contentList = emptyList<String>()
    internal var readAloudSegments = emptyList<ReadAloudSegment>()
    internal var contentStartPositions = emptyList<Int>()
    internal var contentPlainLengths = emptyList<Int>()
    internal var nowSpeak: Int = 0
    internal var contentParagraphOffset: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var chapterIndex = 0
    internal var pageIndex = 0
    @Volatile
    private var lastProgress: ReadAloudProgress? = null
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private var dsJob: Job? = null
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set
    private var waitNewReadAloud = true

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isRun = true
        pause = false
        service = this
        observeLiveBus()
        initMediaSession()
        initBroadcastReceiver()
        initPhoneStateListener()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        setTimer(AppConfig.ttsTimer)
        if (AppConfig.ttsTimer > 0) {
            toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
        }
        execute {
            ImageLoader
                .loadBitmap(this@BaseReadAloudService, ReadBook.book?.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                upReadAloudNotification()
            }
        }
    }

    fun observeLiveBus() {
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val chapterIndex = it.getInt("chapterIndex", ReadBook.durChapterIndex)
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            newReadAloud(play, chapterIndex, pageIndex, startPos)
        }
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.safeRelease()
            wifiLock?.safeRelease()
        }
        releaseReadAloudPrepareWakeLock(force = true)
        isRun = false
        pause = true
        service = null
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        ReadBook.uploadProgress()
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> newReadAloud(
                intent.getBooleanExtra("play", true),
                intent.getIntExtra("chapterIndex", ReadBook.durChapterIndex),
                intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                intent.getIntExtra("startPos", 0)
            )

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun newReadAloud(play: Boolean, chapterIndex: Int, pageIndex: Int, startPos: Int) {
        execute(executeContext = IO) {
            withContext(Main) {
                playStop()
            }
            this@BaseReadAloudService.chapterIndex = chapterIndex
            textChapter = if (chapterIndex == ReadBook.durChapterIndex) {
                ReadBook.curTextChapter
            } else {
                ReadBook.loadReadAloudChapter(chapterIndex, lifecycleScope)
            }
            val textChapter = textChapter ?: return@execute
            if (!textChapter.isCompleted) {
                return@execute
            }
            val actualPageIndex = if (pageIndex < 0) textChapter.lastIndex else pageIndex
            this@BaseReadAloudService.pageIndex = actualPageIndex
            var actualStartPos = startPos
            readAloudNumber = textChapter.getReadLength(actualPageIndex) + actualStartPos
            lastProgress = ReadAloudProgress(
                chapterIndex,
                readAloudNumber + 1,
                actualPageIndex
            )
            readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            // 从当前页面开始获取内容，确保索引匹配
            var readAloudText = textChapter.getNeedReadAloud(
                actualPageIndex,
                readAloudByPage,
                actualStartPos
            )
            if (readAloudText.isBlank() && actualStartPos > 0) {
                AppLog.putDebug(
                    "朗读起始位置超出当前页内容，回退到页首重试: " +
                            "chapter=$chapterIndex page=$actualPageIndex startPos=$actualStartPos"
                )
                actualStartPos = 0
                readAloudNumber = textChapter.getReadLength(actualPageIndex)
                lastProgress = ReadAloudProgress(
                    chapterIndex,
                    readAloudNumber + 1,
                    actualPageIndex
                )
                readAloudText = textChapter.getNeedReadAloud(actualPageIndex, readAloudByPage, 0)
            }
            if (readAloudText.isBlank() && textChapter.readAloudContent.isNotBlank()) {
                readAloudText = textChapter.getContent()
                AppLog.putDebug(
                    "朗读当前页文本为空，使用章节纯文本辅助 SSML 定位: " +
                            "chapter=$chapterIndex page=$actualPageIndex"
                )
            }
            contentList = readAloudText.split("\n")
                .filter { it.isNotEmpty() }
            var pos = actualStartPos
            val page = textChapter.getPage(actualPageIndex)!!
            if (pos > 0) {
                for (paragraph in page.paragraphs) {
                    val tmp = pos - paragraph.length - 1
                    if (tmp < 0) break
                    pos = tmp
                }
            }
            // 计算当前页面在章节中的起始段落索引
            val pageStartParagraphNum = textChapter.getParagraphNum(
                textChapter.getReadLength(actualPageIndex) + 1,
                readAloudByPage
            ) - 1
            contentParagraphOffset = pageStartParagraphNum

            // nowSpeak 是相对于 contentList 的索引
            val chapterParagraphNum = textChapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1
            nowSpeak = (chapterParagraphNum - pageStartParagraphNum).coerceAtLeast(0)

            if (!readAloudByPage && actualStartPos == 0 && !toLast) {
                // 使用相对于页面的段落索引
                val relativeParagraphNum = nowSpeak + pageStartParagraphNum
                pos = page.chapterPosition -
                        textChapter.paragraphs[relativeParagraphNum].chapterPosition
            }
            if (toLast) {
                toLast = false
                readAloudNumber = textChapter.getLastParagraphPosition()
                nowSpeak = contentList.lastIndex
                if (page.paragraphs.size == 1) {
                    val relativeParagraphNum = nowSpeak + pageStartParagraphNum
                    pos = page.chapterPosition -
                            textChapter.paragraphs[relativeParagraphNum].chapterPosition
                }
            }
            paragraphStartPos = pos
            rebuildContentPositionMap()
            waitNewReadAloud = false
            launch(Main) {
                if (play) play() else pageChanged = true
            }
        }.onError {
            AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    @SuppressLint("WakelockTimeout")
    open fun play() {
        if (useWakeLock) {
            wakeLock.safeAcquire()
            wifiLock?.safeAcquire()
        }
        isRun = true
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        if (useWakeLock) {
            wakeLock.safeRelease()
            wifiLock?.safeRelease()
        }
        releaseReadAloudPrepareWakeLock(force = true)
        pause = true
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        ReadBook.uploadProgress()
        doDs()
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        if (useWakeLock) {
            wakeLock.safeAcquire()
            wifiLock?.safeAcquire()
        }
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    @SuppressLint("WakelockTimeout")
    protected fun acquireReadAloudPrepareWakeLock() {
        val serviceWakeHeld = useWakeLock && wakeLock.isHeld
        val wifiHeldBefore = wifiLock?.isHeld == true
        prepareWakeLockAcquireCount++
        prepareWifiLockAcquireCount++
        AppLog.putDebug(
            "aiChapterParse wakeLockAcquire beforeHeld=${prepareWakeLock.isHeld} " +
                "serviceWakeHeld=$serviceWakeHeld wifiHeld=$wifiHeldBefore " +
                "prepareCount=$prepareWakeLockAcquireCount wifiCount=$prepareWifiLockAcquireCount"
        )
        if (!serviceWakeHeld && !prepareWakeLock.isHeld) {
            prepareWakeLock.safeAcquire()
        }
        if (!wifiHeldBefore && wifiLock?.isHeld != true) {
            wifiLock?.safeAcquire()
        }
        AppLog.putDebug(
            "aiChapterParse wakeLockAcquire afterHeld=${prepareWakeLock.isHeld} " +
                "prepareCount=$prepareWakeLockAcquireCount wifiHeld=${wifiLock?.isHeld} " +
                "wifiCount=$prepareWifiLockAcquireCount"
        )
    }

    protected fun releaseReadAloudPrepareWakeLock(force: Boolean = false) {
        if (force) {
            prepareWakeLockAcquireCount = 0
            prepareWifiLockAcquireCount = 0
        } else {
            prepareWakeLockAcquireCount = (prepareWakeLockAcquireCount - 1).coerceAtLeast(0)
            prepareWifiLockAcquireCount = (prepareWifiLockAcquireCount - 1).coerceAtLeast(0)
        }
        AppLog.putDebug(
            "aiChapterParse wakeLockRelease held=${prepareWakeLock.isHeld} " +
                "prepareCount=$prepareWakeLockAcquireCount wifiHeld=${wifiLock?.isHeld} " +
                "wifiCount=$prepareWifiLockAcquireCount force=$force"
        )
        if (prepareWakeLockAcquireCount == 0) {
            prepareWakeLock.safeRelease()
        }
        if (prepareWifiLockAcquireCount == 0) {
            wifiLock?.safeRelease()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun PowerManager.WakeLock.safeAcquire(timeoutMillis: Long? = null) {
        if (!isHeld) {
            if (timeoutMillis != null) {
                acquire(timeoutMillis)
            } else {
                acquire()
            }
        }
    }

    private fun PowerManager.WakeLock.safeRelease() {
        if (isHeld) {
            release()
        }
    }

    private fun WifiManager.WifiLock.safeAcquire() {
        if (!isHeld) {
            acquire()
        }
    }

    private fun WifiManager.WifiLock.safeRelease() {
        if (isHeld) {
            release()
        }
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        val chapter = textChapter ?: return
        val currentPage = chapter.getPageIndexByCharIndex(progress)
        pageIndex = currentPage
        val readAloudProgress = ReadAloudProgress(chapterIndex, progress, currentPage)
        lastProgress = readAloudProgress
        postEvent(EventBus.TTS_PROGRESS, readAloudProgress)
    }

    fun currentProgress(): ReadAloudProgress? {
        return lastProgress
    }

    internal fun stripReadAloudTags(content: String): String {
        return content.replace(AppPattern.ssmlTagRegex, "")
            .replace(Regex("<[^>]+>"), "")
    }

    private fun isReadAloudIgnoredChar(char: Char): Boolean {
        return char.isWhitespace() || char == '　'
    }

    internal fun findPlainTextStartInChapter(
        chapterContent: String,
        plainText: String,
        startPos: Int
    ): Int? {
        val normalizedNeedle = buildString {
            plainText.forEach { char ->
                if (!isReadAloudIgnoredChar(char)) {
                    append(char)
                }
            }
        }
        if (normalizedNeedle.isBlank()) return null
        val originalPositions = arrayListOf<Int>()
        val normalizedChapter = buildString {
            chapterContent.forEachIndexed { index, char ->
                if (!isReadAloudIgnoredChar(char)) {
                    append(char)
                    originalPositions.add(index)
                }
            }
        }
        val normalizedStart = originalPositions
            .indexOfFirst { it >= startPos.coerceAtLeast(0) }
            .let { if (it >= 0) it else originalPositions.size }
        val foundIndex = normalizedChapter.indexOf(normalizedNeedle, normalizedStart)
        return if (foundIndex >= 0) originalPositions.getOrNull(foundIndex) else null
    }

    internal fun contentPlainLength(index: Int): Int {
        return readAloudSegments.getOrNull(index)?.plainLength
            ?: contentPlainLengths.getOrNull(index)
            ?: stripReadAloudTags(contentList.getOrNull(index).orEmpty()).length
    }

    internal fun contentStartPosition(index: Int): Int? {
        return readAloudSegments.getOrNull(index)?.startPosition
            ?: contentStartPositions.getOrNull(index)
    }

    internal fun rebuildContentPositionMap() {
        val chapter = textChapter ?: run {
            readAloudSegments = emptyList()
            contentStartPositions = emptyList()
            contentPlainLengths = emptyList()
            return
        }
        val paragraphs = chapter.getParagraphs(readAloudByPage && chapter.readAloudContent.isBlank())
        val mapFromChapterStart = chapter.readAloudContent.isNotBlank() &&
                contentList.any { it.contains('<') }
        var runningPos = if (mapFromChapterStart) 0 else readAloudNumber
        var searchFrom = if (mapFromChapterStart) 0 else readAloudNumber.coerceAtLeast(0)
        val chapterContent = chapter.getContent()
        readAloudSegments = contentList.mapIndexed { index, content ->
            val paragraphIndex = contentParagraphOffset + index
            val plainText = stripReadAloudTags(content)
            val foundIndex = if (plainText.isNotBlank()) {
                findPlainTextStartInChapter(chapterContent, plainText, searchFrom)
            } else {
                null
            }
            val start = when {
                foundIndex != null -> foundIndex
                index == nowSpeak -> readAloudNumber
                paragraphIndex in paragraphs.indices -> paragraphs[paragraphIndex].chapterPosition
                else -> runningPos
            }
            val plainLength = plainText.length
            runningPos = start + plainLength + 1
            searchFrom = (start + plainLength).coerceAtLeast(searchFrom)
            ReadAloudSegment(
                text = plainText,
                startPosition = start,
                plainLength = plainLength,
                emotion = readAloudTagAttribute(content, "emotion", "category")
                    .ifBlank { "neutral" },
                emotionIntensity = readAloudTagAttribute(content, "emotion", "intensity")
                    .toFloatOrNull()
                    ?: 1.0f
            )
        }
        contentStartPositions = readAloudSegments.map { it.startPosition }
        contentPlainLengths = readAloudSegments.map { it.plainLength }
    }

    private fun readAloudTagAttribute(content: String, tagName: String, attrName: String): String {
        val tagRegex = Regex("<\\s*$tagName\\b[^>]*>", RegexOption.IGNORE_CASE)
        val attrRegex = Regex("""\b$attrName\s*=\s*(['"])(.*?)\1""", RegexOption.IGNORE_CASE)
        return tagRegex.find(content)
            ?.value
            ?.let { attrRegex.find(it)?.groupValues?.getOrNull(2) }
            .orEmpty()
    }

    private fun prevP() {
        if (waitNewReadAloud) {
            return
        }
        if (nowSpeak > 0) {
            playStop()
            do {
                nowSpeak--
                readAloudNumber = contentStartPosition(nowSpeak)
                    ?: (readAloudNumber - contentPlainLength(nowSpeak) - 1 + paragraphStartPos)
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    val paragraphIndex = contentParagraphOffset + nowSpeak
                    if (paragraphIndex in paragraphs.indices &&
                        !paragraphs[paragraphIndex].isParagraphEnd
                    ) {
                        readAloudNumber++
                    }
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                }
            }
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            if (chapterIndex <= 0) return
            toLast = true
            waitNewReadAloud = true
            newReadAloud(true, chapterIndex - 1, -1, 0)
        }
    }

    private fun nextP() {
        if (waitNewReadAloud) {
            return
        }
        if (nowSpeak < contentList.size - 1) {
            playStop()
            val currentIndex = nowSpeak
            paragraphStartPos = 0
            nowSpeak++
            readAloudNumber = contentStartPosition(nowSpeak)
                ?: (readAloudNumber + contentPlainLength(currentIndex) + 1)
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    val paragraphIndex = contentParagraphOffset + nowSpeak
                    if (paragraphIndex in paragraphs.indices &&
                        !paragraphs[paragraphIndex].isParagraphEnd
                    ) {
                        readAloudNumber--
                    }
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                }
            }
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            waitNewReadAloud = true
            nextChapter()
        }
    }

    private fun setTimer(minute: Int) {
        timeMinute = minute
        doDs()
    }

    private fun addTimer() {
        if (timeMinute == 180) {
            timeMinute = 0
        } else {
            timeMinute += 10
            if (timeMinute > 180) timeMinute = 180
        }
        doDs()
    }

    /**
     * 定时
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute >= 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                // 为系统媒体控件添加定时按钮
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        "ACTION_ADD_TIMER",
                        getString(R.string.set_timer),
                        R.drawable.ic_time_add_24dp
                    ).build()
                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        if (getPrefBoolean("systemMediaControlCompatibilityChange")) {
            mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumeReadAloud()
                }

                override fun onPause() {
                    pauseReadAloud()
                }

                override fun onSkipToNext() {
                    if (getPrefBoolean("mediaButtonPerNext", false)) {
                        nextChapter()
                    } else {
                        nextP()
                    }
                }

                override fun onSkipToPrevious() {
                    if (getPrefBoolean("mediaButtonPerNext", false)) {
                        prevChapter()
                    } else {
                        prevP()
                    }
                }

                override fun onStop() {
                    stopSelf()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == "ACTION_ADD_TIMER") addTimer()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    return MediaButtonReceiver.handleIntent(
                        this@BaseReadAloudService, mediaButtonEvent
                    )
                }
            })
        } else {
            mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    return MediaButtonReceiver.handleIntent(
                        this@BaseReadAloudService, mediaButtonEvent
                    )
                }
            })
        }
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumeReadAloud()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun choiceMediaStyle(): androidx.media.app.NotificationCompat.MediaStyle {
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(1, 2, 4)
        if (getPrefBoolean("systemMediaControlCompatibilityChange")) {
            //fix #4090 android 14 can not show play control in lock screen
            mediaStyle.setMediaSession(mediaSessionCompat.sessionToken)
        }
        return mediaStyle
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        var nSubtitle = textChapter?.title
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<ReadBookActivity>("activity")
            )
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(choiceMediaStyle())
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NotificationId.ReadAloudService,
                        notification.build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NotificationId.ReadAloudService, notification.build())
                }
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    open fun prevChapter() {
        if (chapterIndex <= 0) return
        toLast = false
        waitNewReadAloud = true
        newReadAloud(true, chapterIndex - 1, 0, 0)
    }

    open fun nextChapter() {
        ReadBook.upReadTime()
        AppLog.putDebug("${textChapter?.chapter?.title} 朗读结束后加载下一章")
        if (chapterIndex + 1 >= ReadBook.simulatedChapterSize) {
            stopSelf()
            return
        }
        waitNewReadAloud = true
        newReadAloud(true, chapterIndex + 1, 0, 0)
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumeReadAloud()
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}
