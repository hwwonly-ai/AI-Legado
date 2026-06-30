package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookReadAloudExportManager
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudAudioCache
import io.legado.app.model.ReadAloudCacheInvalidator
import io.legado.app.model.ReadAloudDebugStore
import io.legado.app.model.BookRoleAutoArchiveManager
import io.legado.app.model.ReadAloudSegmentCacheManager
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = LinearLayout(requireContext())
        view.setBackgroundColor(requireContext().backgroundColor)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readAloudPreferTag)
            .commit()
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        companion object {
            private const val KEY_REFRESH_CHAPTER = "refreshCurrentChapterSegmentCache"
            private const val KEY_CLEAR_BOOK = "clearBookSegmentCache"
            private const val KEY_REFRESH_CHAPTER_AI = "refreshCurrentChapterAiLabelCache"
            private const val KEY_CLEAR_BOOK_AI = "clearBookAiLabelCache"
            private const val KEY_RESET_ROLE_AUTO_ARCHIVE = "resetBookRoleAutoArchive"
            private const val KEY_EXPORT_CHAPTER_SCRIPT = "exportCurrentChapterScript"
            private const val KEY_IMPORT_CHAPTER_SCRIPT = "importCurrentChapterScript"
            private const val KEY_EXPORT_ROLE_PROFILE = "exportBookRoleProfile"
            private const val KEY_IMPORT_ROLE_PROFILE = "importBookRoleProfile"
            private const val KEY_AUDIO_CACHE_STATS = "readAloudAudioCacheStats"
            private const val KEY_CLEAR_CHAPTER_AUDIO = "clearCurrentChapterAudioCache"
            private const val KEY_CLEAR_BOOK_AUDIO = "clearBookAudioCache"
            private const val KEY_CLEAR_ALL_AUDIO = "clearAllReadAloudAudioCache"
            private const val KEY_DEBUG_SEGMENT = "readAloudDebugSegment"
            private const val KEY_DEBUG_TTS = "readAloudDebugTts"
            private const val KEY_DEBUG_AI = "readAloudDebugAi"
            private const val KEY_DEBUG_ROLE = "readAloudDebugRole"
        }

        private val cacheManager by lazy {
            ReadAloudSegmentCacheManager(appDb.readAloudSegmentCacheDao)
        }
        private val exportManager by lazy { BookReadAloudExportManager() }
        private var cacheJob: Job? = null
        private val exportScriptResult = registerForActivityResult(HandleFileContract()) {
            if (it.uri != null) context?.toastOnUi(R.string.export_success)
        }
        private val exportRoleProfileResult = registerForActivityResult(HandleFileContract()) {
            if (it.uri != null) context?.toastOnUi(R.string.export_success)
        }
        private val importScriptResult = registerForActivityResult(HandleFileContract()) {
            it.uri?.let { uri -> importCurrentChapterScript(uri.readText(requireContext())) }
        }
        private val importRoleProfileResult = registerForActivityResult(HandleFileContract()) {
            it.uri?.let { uri -> importBookRoleProfile(uri.readText(requireContext())) }
        }

        private val speakEngineSummary: String
            get() {
                val ttsEngine = ReadAloud.ttsEngine
                    ?: return getString(R.string.system_tts)
                if (StringUtils.isNumeric(ttsEngine)) {
                    return appDb.httpTTSDao.getName(ttsEngine.toLong())
                        ?: getString(R.string.system_tts)
                }
                return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                    ?: getString(R.string.system_tts)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                it.isEnabled = AppConfig.ignoreAudioFocus
            }
            val hasBook = ReadBook.book != null
            setCachePreferencesEnabled(hasBook)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
                "sysTtsConfig" -> IntentHelp.openTTSSetting()
                "aiServiceConfig" -> showDialogFragment(AiServiceConfigDialog())
                KEY_REFRESH_CHAPTER -> handleRefreshChapter()
                KEY_CLEAR_BOOK -> handleClearBook()
                KEY_REFRESH_CHAPTER_AI -> handleRefreshChapterAi()
                KEY_CLEAR_BOOK_AI -> handleClearBookAi()
                KEY_RESET_ROLE_AUTO_ARCHIVE -> handleResetRoleAutoArchive()
                KEY_EXPORT_CHAPTER_SCRIPT -> exportCurrentChapterScript()
                KEY_IMPORT_CHAPTER_SCRIPT -> importScriptResult.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("json")
                }
                KEY_EXPORT_ROLE_PROFILE -> exportBookRoleProfile()
                KEY_IMPORT_ROLE_PROFILE -> importRoleProfileResult.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("json")
                }
                KEY_AUDIO_CACHE_STATS -> showAudioCacheStats()
                KEY_CLEAR_CHAPTER_AUDIO -> handleClearCurrentChapterAudio()
                KEY_CLEAR_BOOK_AUDIO -> handleClearBookAudio()
                KEY_CLEAR_ALL_AUDIO -> handleClearAllAudio()
                KEY_DEBUG_SEGMENT -> showDialogFragment(
                    TextDialog(
                        getString(R.string.read_aloud_debug_segment),
                        ReadAloudDebugStore.currentSegmentJson()
                    )
                )
                KEY_DEBUG_TTS -> showDialogFragment(
                    TextDialog(
                        getString(R.string.read_aloud_debug_tts),
                        ReadAloudDebugStore.currentTtsRequestSummary()
                    )
                )
                KEY_DEBUG_AI -> showDialogFragment(
                    TextDialog(
                        getString(R.string.read_aloud_debug_ai),
                        ReadAloudDebugStore.currentAiSummary()
                    )
                )
                KEY_DEBUG_ROLE -> showDialogFragment(
                    TextDialog(
                        getString(R.string.read_aloud_debug_role),
                        ReadAloudDebugStore.currentRoleMatchSummary()
                    )
                )
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ttsEngine -> {
                    ReadAloudCacheInvalidator.clearCurrentChapterAudio("ttsEngineChanged")
                }

                PreferKey.ignoreAudioFocus -> {
                    findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                        it.isEnabled = AppConfig.ignoreAudioFocus
                    }
                }
            }
        }

        private fun upPreferenceSummary(preference: Preference?, value: String) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(value)
                    preference.summary = if (index >= 0) preference.entries[index] else null
                }

                else -> {
                    preference?.summary = value
                }
            }
        }

        override fun upSpeakEngineSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsEngine),
                speakEngineSummary
            )
        }

        private fun handleRefreshChapter() {
            val book = ReadBook.book
            if (book == null) {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            val progress = BaseReadAloudService.service?.currentProgress()
            val chapterIndex = progress?.chapterIndex ?: ReadBook.durChapterIndex
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            if (chapter == null) {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            val chapterTitle = chapter.title
            alert(getString(R.string.draw), getString(R.string.confirm_refresh_segment_cache, chapterTitle)) {
                positiveButton(R.string.dialog_confirm) {
                    performRefreshChapter(book, chapter, progress)
                }
                cancelButton()
            }
        }

        private fun handleRefreshChapterAi() {
            val book = ReadBook.book
            if (book == null) {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            val progress = BaseReadAloudService.service?.currentProgress()
            val chapterIndex = progress?.chapterIndex ?: ReadBook.durChapterIndex
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            if (chapter == null) {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            val chapterTitle = chapter.title
            alert(getString(R.string.draw), getString(R.string.confirm_refresh_ai_label_cache, chapterTitle)) {
                positiveButton(R.string.dialog_confirm) {
                    performRefreshChapterAi(book, chapter, progress)
                }
                cancelButton()
            }
        }

        private fun handleClearBook() {
            val book = ReadBook.book
            if (book == null) {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            alert(getString(R.string.draw), getString(R.string.confirm_clear_book_segment_cache)) {
                positiveButton(R.string.dialog_confirm) {
                    performClearBook(book)
                }
                cancelButton()
            }
        }

        private fun handleClearBookAi() {
            val book = ReadBook.book
            if (book == null) {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            alert(getString(R.string.draw), getString(R.string.confirm_clear_book_ai_label_cache)) {
                positiveButton(R.string.dialog_confirm) {
                    performClearBookAi(book)
                }
                cancelButton()
            }
        }

        private fun handleResetRoleAutoArchive() {
            val book = ReadBook.book
            if (book == null) {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            alert(getString(R.string.draw), getString(R.string.confirm_reset_book_role_auto_archive)) {
                positiveButton(R.string.dialog_confirm) {
                    val result = BookRoleAutoArchiveManager.resetAutoArchive(book)
                    book.save()
                    context?.toastOnUi(
                        getString(
                            R.string.reset_book_role_auto_archive_success,
                            result.statCount,
                            result.autoMinorCount
                        )
                    )
                }
                cancelButton()
            }
        }

        private fun exportCurrentChapterScript() {
            val (book, chapter) = currentBookAndChapter() ?: return
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    val export = withContext(Dispatchers.IO) {
                        exportManager.exportCurrentChapterScript(book, chapter)
                    }
                    val fileName = safeExportFileName(
                        "${book.name}_${chapter.index + 1}_${chapter.title}_script.json"
                    )
                    exportScriptResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            fileName,
                            exportManager.toJson(export),
                            "application/json"
                        )
                    }
                } catch (e: Exception) {
                    context?.toastOnUi(R.string.book_script_export_no_cache)
                }
            }
        }

        private fun importCurrentChapterScript(json: String) {
            val (book, chapter) = currentBookAndChapter() ?: return
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    val mode = if (AppConfig.streamReadAloudAudio) "stream" else "file"
                    withContext(Dispatchers.IO) {
                        val script = exportManager.parseScript(json)
                        exportManager.importCurrentChapterScript(book, chapter, script, mode)
                        clearCurrentChapterAudioCache()
                    }
                    context?.toastOnUi(R.string.book_script_import_success)
                } catch (e: Exception) {
                    context?.toastOnUi(R.string.book_script_import_failed)
                }
            }
        }

        private fun exportBookRoleProfile() {
            val book = ReadBook.book ?: run {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                val export = withContext(Dispatchers.IO) {
                    exportManager.exportRoleProfile(book)
                }
                val fileName = safeExportFileName("${book.name}_role_profile.json")
                exportRoleProfileResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        fileName,
                        exportManager.toJson(export),
                        "application/json"
                    )
                }
            }
        }

        private fun importBookRoleProfile(json: String) {
            val book = ReadBook.book ?: run {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val profile = exportManager.parseRoleProfile(json)
                        exportManager.importRoleProfile(book, profile)
                        clearCurrentChapterAudioCache()
                    }
                    context?.toastOnUi(R.string.book_role_profile_import_success)
                } catch (e: Exception) {
                    context?.toastOnUi(R.string.book_role_profile_import_failed)
                }
            }
        }

        private fun currentBookAndChapter():
            Pair<io.legado.app.data.entities.Book, io.legado.app.data.entities.BookChapter>? {
            val book = ReadBook.book ?: run {
                context?.toastOnUi(R.string.no_book_available)
                return null
            }
            val progress = BaseReadAloudService.service?.currentProgress()
            val chapterIndex = progress?.chapterIndex ?: ReadBook.durChapterIndex
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: run {
                context?.toastOnUi(R.string.no_book_available)
                return null
            }
            return book to chapter
        }

        private fun clearCurrentChapterAudioCache() {
            ReadAloudCacheInvalidator.clearCurrentChapterAudio("scriptOrProfileChanged")
        }

        private fun showAudioCacheStats() {
            val stats = ReadAloudAudioCache.stats(requireContext().cacheDir)
            showDialogFragment(
                TextDialog(
                    getString(R.string.read_aloud_audio_cache_stats),
                    getString(
                        R.string.read_aloud_audio_cache_stats_content,
                        stats.fileCount,
                        formatBytes(stats.totalBytes)
                    )
                )
            )
        }

        private fun handleClearCurrentChapterAudio() {
            val (_, chapter) = currentBookAndChapter() ?: return
            alert(
                getString(R.string.draw),
                getString(R.string.confirm_clear_current_chapter_audio_cache, chapter.title)
            ) {
                positiveButton(R.string.dialog_confirm) {
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ReadAloudAudioCache.clearChapterAudioCache(
                                requireContext().cacheDir,
                                chapter.title
                            )
                        }
                        context?.toastOnUi(formatAudioClearResult(result))
                    }
                }
                cancelButton()
            }
        }

        private fun handleClearBookAudio() {
            val book = ReadBook.book ?: run {
                context?.toastOnUi(R.string.no_book_available)
                return
            }
            alert(getString(R.string.draw), getString(R.string.confirm_clear_book_audio_cache)) {
                positiveButton(R.string.dialog_confirm) {
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val titles = appDb.bookChapterDao.getChapterList(book.bookUrl)
                                .map { it.title }
                            ReadAloudAudioCache.clearBookAudioCache(
                                requireContext().cacheDir,
                                titles
                            )
                        }
                        context?.toastOnUi(formatAudioClearResult(result))
                    }
                }
                cancelButton()
            }
        }

        private fun handleClearAllAudio() {
            alert(getString(R.string.draw), getString(R.string.confirm_clear_all_audio_cache)) {
                positiveButton(R.string.dialog_confirm) {
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ReadAloudAudioCache.clearAllAudioCache(requireContext().cacheDir)
                        }
                        context?.toastOnUi(formatAudioClearResult(result))
                    }
                }
                cancelButton()
            }
        }

        private fun formatAudioClearResult(result: ReadAloudAudioCache.ClearResult): String {
            return getString(
                R.string.clear_read_aloud_audio_cache_success,
                result.fileCount,
                formatBytes(result.totalBytes)
            )
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "${bytes}B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1fKB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1fMB", mb)
            return String.format(java.util.Locale.ROOT, "%.1fGB", mb / 1024.0)
        }

        private fun safeExportFileName(name: String): String {
            val clean = name.replace(Regex("""[\\/:*?"<>|]+"""), "_")
                .replace(Regex("\\s+"), "_")
                .trim('_')
            return clean.ifBlank { "legado_export.json" }
        }

        private fun performRefreshChapter(
            book: io.legado.app.data.entities.Book,
            chapter: io.legado.app.data.entities.BookChapter,
            savedProgress: io.legado.app.model.ReadAloudProgress?
        ) {
            setCachePreferencesEnabled(false)
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) {
                        cacheManager.refreshChapter(book, chapter).also {
                            ReadAloudCacheInvalidator.clearCurrentChapterAudio("refreshSegmentCache")
                        }
                    }
                    restartReadAloudIfNeeded(savedProgress, "manualRefresh")

                    withContext(Dispatchers.Main) {
                        if (deleted > 0) {
                            context?.toastOnUi(getString(R.string.refresh_segment_cache_success, deleted))
                        } else {
                            context?.toastOnUi(getString(R.string.refresh_segment_cache_no_entry))
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        context?.toastOnUi(R.string.segment_cache_operation_failed)
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        setCachePreferencesEnabled(ReadBook.book != null)
                    }
                }
            }
        }

        private fun performRefreshChapterAi(
            book: io.legado.app.data.entities.Book,
            chapter: io.legado.app.data.entities.BookChapter,
            savedProgress: io.legado.app.model.ReadAloudProgress?
        ) {
            setCachePreferencesEnabled(false)
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) {
                        cacheManager.refreshChapterAiLabels(book, chapter).also {
                            ReadAloudCacheInvalidator.clearCurrentChapterAudio("refreshAiLabelCache")
                        }
                    }
                    restartReadAloudIfNeeded(savedProgress, "manualRefreshAi")

                    withContext(Dispatchers.Main) {
                        if (deleted > 0) {
                            context?.toastOnUi(getString(R.string.refresh_ai_label_cache_success, deleted))
                        } else {
                            context?.toastOnUi(getString(R.string.refresh_ai_label_cache_no_entry))
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        context?.toastOnUi(R.string.segment_cache_operation_failed)
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        setCachePreferencesEnabled(ReadBook.book != null)
                    }
                }
            }
        }

        private fun performClearBook(book: io.legado.app.data.entities.Book) {
            setCachePreferencesEnabled(false)
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) {
                        cacheManager.clearBook(book).also {
                            ReadAloudCacheInvalidator.clearCurrentChapterAudio("clearBookSegmentCache")
                        }
                    }
                    withContext(Dispatchers.Main) {
                        context?.toastOnUi(getString(R.string.clear_book_segment_cache_success, deleted))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        context?.toastOnUi(R.string.segment_cache_operation_failed)
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        setCachePreferencesEnabled(ReadBook.book != null)
                    }
                }
            }
        }

        private fun performClearBookAi(book: io.legado.app.data.entities.Book) {
            setCachePreferencesEnabled(false)
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) {
                        cacheManager.clearBookAiLabels(book).also {
                            ReadAloudCacheInvalidator.clearCurrentChapterAudio("clearBookAiLabelCache")
                        }
                    }
                    withContext(Dispatchers.Main) {
                        context?.toastOnUi(getString(R.string.clear_book_ai_label_cache_success, deleted))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        context?.toastOnUi(R.string.segment_cache_operation_failed)
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        setCachePreferencesEnabled(ReadBook.book != null)
                    }
                }
            }
        }

        private suspend fun restartReadAloudIfNeeded(
            savedProgress: io.legado.app.model.ReadAloudProgress?,
            logAction: String
        ) {
            val wasPlaying = BaseReadAloudService.isRun && !BaseReadAloudService.pause
            val wasPaused = BaseReadAloudService.isRun && BaseReadAloudService.pause
            if (!wasPlaying && !wasPaused) return

            val chapterIdx = savedProgress?.chapterIndex ?: ReadBook.durChapterIndex
            val pageIdx = savedProgress?.pageIndex ?: ReadBook.durPageIndex
            val chapterPos = savedProgress?.chapterPos ?: 0

            val textChapter = ReadBook.curTextChapter
            val startPos = if (textChapter != null) {
                val pageStart = textChapter.getReadLength(pageIdx)
                (chapterPos - pageStart).coerceAtLeast(0)
            } else 0

            ReadAloud.stop(requireContext())
            delay(300)
            ReadAloud.play(
                requireContext(),
                play = wasPlaying,
                chapterIndex = chapterIdx,
                pageIndex = pageIdx,
                startPos = startPos
            )
            android.util.Log.i(
                "segmentCache",
                "$logAction restart chapterIndex=$chapterIdx pageIndex=$pageIdx chapterPos=$chapterPos"
            )
        }

        private fun setCachePreferencesEnabled(enabled: Boolean) {
            listOf(
                KEY_REFRESH_CHAPTER,
                KEY_CLEAR_BOOK,
                KEY_REFRESH_CHAPTER_AI,
                KEY_CLEAR_BOOK_AI,
                KEY_RESET_ROLE_AUTO_ARCHIVE,
                KEY_EXPORT_CHAPTER_SCRIPT,
                KEY_IMPORT_CHAPTER_SCRIPT,
                KEY_EXPORT_ROLE_PROFILE,
                KEY_IMPORT_ROLE_PROFILE,
                KEY_AUDIO_CACHE_STATS,
                KEY_CLEAR_CHAPTER_AUDIO,
                KEY_CLEAR_BOOK_AUDIO,
                KEY_CLEAR_ALL_AUDIO,
                KEY_DEBUG_SEGMENT,
                KEY_DEBUG_TTS,
                KEY_DEBUG_AI,
                KEY_DEBUG_ROLE
            ).forEach { key ->
                findPreference<Preference>(key)?.isEnabled = enabled
            }
        }
    }
}
