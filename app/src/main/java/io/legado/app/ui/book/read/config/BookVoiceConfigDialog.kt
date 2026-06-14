package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogBookVoiceConfigBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookVoiceConfigDialog : BaseDialogFragment(R.layout.dialog_book_voice_config) {

    private val binding by viewBinding(DialogBookVoiceConfigBinding::bind)
    private var selectedEngine: String? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.book_voice_config)
        tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        tvOk.setOnClickListener {
            saveBookVoice()
        }
        tvClear.setOnClickListener {
            etNarratorVoice.setText("")
            etDialogVoice.setText("")
            saveBookVoice()
        }
    }

    private fun initData() = binding.run {
        val book = ReadBook.book
        selectedEngine = if (arguments?.containsKey(ARG_TTS_ENGINE) == true) {
            arguments?.getString(ARG_TTS_ENGINE)
        } else {
            ReadAloud.ttsEngine
        }
        etNarratorVoice.setText(book?.getNarratorVoice(selectedEngine))
        etDialogVoice.setText(book?.getDialogVoice(selectedEngine))
        val httpTTS = selectedEngine?.toLongOrNull()?.let {
            appDb.httpTTSDao.get(it)
        }
        val supportRoleVoice = httpTTS?.isSupportedRoleVoice() == true
        tvEngineSummary.text = if (supportRoleVoice && httpTTS != null) {
            getString(
                R.string.book_voice_current_engine,
                httpTTS.name,
                httpTTS.providerName()
            )
        } else {
            getString(R.string.book_voice_unsupported_engine)
        }
        tilNarratorVoice.helperText = httpTTS?.narratorHint()
        tilDialogVoice.helperText = httpTTS?.dialogHint()
        tilNarratorVoice.isEnabled = supportRoleVoice
        tilDialogVoice.isEnabled = supportRoleVoice
        etNarratorVoice.isEnabled = supportRoleVoice
        etDialogVoice.isEnabled = supportRoleVoice
        tvOk.isEnabled = supportRoleVoice && book != null
        tvClear.isEnabled = supportRoleVoice && book != null
    }

    private fun saveBookVoice() {
        ReadBook.book?.let { book ->
            val narratorVoice = binding.etNarratorVoice.text?.toString()
            val dialogVoice = binding.etDialogVoice.text?.toString()
            book.setTtsVoices(selectedEngine, narratorVoice, dialogVoice)
            book.save()
            ReadAloud.upReadAloudClass()
        }
        dismissAllowingStateLoss()
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
            isAliyunTTS() -> "Aliyun"
            isDoubaoTTS() -> "Doubao"
            else -> "HTTP_TTS"
        }
    }

    private fun HttpTTS.isSupportedRoleVoice(): Boolean {
        return isAliyunTTS() || isDoubaoTTS()
    }

    private fun HttpTTS.narratorHint(): String {
        return if (isDoubaoTTS()) {
            getString(R.string.book_voice_narrator_hint_doubao)
        } else {
            getString(R.string.book_voice_narrator_hint_aliyun)
        }
    }

    private fun HttpTTS.dialogHint(): String {
        return if (isDoubaoTTS()) {
            getString(R.string.book_voice_dialog_hint_doubao)
        } else {
            getString(R.string.book_voice_dialog_hint_aliyun)
        }
    }

    companion object {
        private const val ARG_TTS_ENGINE = "ttsEngine"

        fun newInstance(ttsEngine: String?): BookVoiceConfigDialog {
            return BookVoiceConfigDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TTS_ENGINE, ttsEngine)
                }
            }
        }
    }
}
