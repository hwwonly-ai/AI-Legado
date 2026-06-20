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
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSegmentCacheManager
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
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
        }

        private val cacheManager by lazy {
            ReadAloudSegmentCacheManager(appDb.readAloudSegmentCacheDao)
        }
        private var cacheJob: Job? = null

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
            findPreference<Preference>(KEY_REFRESH_CHAPTER)?.isEnabled = hasBook
            findPreference<Preference>(KEY_CLEAR_BOOK)?.isEnabled = hasBook
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

        private fun performRefreshChapter(
            book: io.legado.app.data.entities.Book,
            chapter: io.legado.app.data.entities.BookChapter,
            savedProgress: io.legado.app.model.ReadAloudProgress?
        ) {
            val refreshPref = findPreference<Preference>(KEY_REFRESH_CHAPTER)
            val clearPref = findPreference<Preference>(KEY_CLEAR_BOOK)
            refreshPref?.isEnabled = false
            clearPref?.isEnabled = false
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) {
                        cacheManager.refreshChapter(book, chapter)
                    }
                    val wasPlaying = BaseReadAloudService.isRun && !BaseReadAloudService.pause
                    val wasPaused = BaseReadAloudService.isRun && BaseReadAloudService.pause

                    if (wasPlaying || wasPaused) {
                        val chapterIdx = savedProgress?.chapterIndex ?: ReadBook.durChapterIndex
                        val pageIdx = savedProgress?.pageIndex ?: ReadBook.durPageIndex
                        val chapterPos = savedProgress?.chapterPos ?: 0

                        val textChapter = ReadBook.curTextChapter
                        val startPos = if (textChapter != null) {
                            val pageStart = textChapter.getReadLength(pageIdx)
                            (chapterPos - pageStart).coerceAtLeast(0)
                        } else 0

                        ReadAloud.stop(requireContext())
                        // Brief delay to allow old service to stop
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
                            "manualRefresh restart chapterIndex=$chapterIdx pageIndex=$pageIdx chapterPos=$chapterPos"
                        )
                    }

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
                        refreshPref?.isEnabled = true
                        clearPref?.isEnabled = ReadBook.book != null
                    }
                }
            }
        }

        private fun performClearBook(book: io.legado.app.data.entities.Book) {
            val refreshPref = findPreference<Preference>(KEY_REFRESH_CHAPTER)
            val clearPref = findPreference<Preference>(KEY_CLEAR_BOOK)
            refreshPref?.isEnabled = false
            clearPref?.isEnabled = false
            cacheJob?.cancel()
            cacheJob = lifecycleScope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) {
                        cacheManager.clearBook(book)
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
                        refreshPref?.isEnabled = ReadBook.book != null
                        clearPref?.isEnabled = ReadBook.book != null
                    }
                }
            }
        }
    }
}