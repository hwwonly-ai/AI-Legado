package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookReadAloudRole
import io.legado.app.databinding.DialogBookRoleEditBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookRoleEditDialog : BaseDialogFragment(R.layout.dialog_book_role_edit) {

    private val binding by viewBinding(DialogBookRoleEditBinding::bind)
    private var role: BookReadAloudRole? = null
    private var ttsEngine: String? = null
    private var narratorVoice: String? = null
    private var dialogVoice: String? = null

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
        toolBar.setTitle(R.string.book_role_config)
        toolBar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolBar.setNavigationOnClickListener { dismissAllowingStateLoss() }
        tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        tvOk.setOnClickListener { saveRole() }
    }

    private fun initData() = binding.run {
        val roleJson = arguments?.getString(ARG_ROLE)
        role = if (roleJson != null) {
            GSON.fromJsonObject<BookReadAloudRole>(roleJson).getOrNull()
        } else null
        ttsEngine = arguments?.getString(ARG_TTS_ENGINE) ?: ReadAloud.ttsEngine

        role?.let { r ->
            etRoleName.setText(r.name)
            etAliases.setText(r.aliases.joinToString(", "))
            etNote.setText(r.note)
            switchLocked.isChecked = r.locked
        }

        val httpTTS = ttsEngine?.toLongOrNull()?.let {
            appDb.httpTTSDao.get(it)
        }
        val supported = httpTTS?.isSupportedRoleVoice() == true
        narratorVoice = ReadBook.book?.getNarratorVoice(ttsEngine)
        dialogVoice = ReadBook.book?.getDialogVoice(ttsEngine)
        val configuredVoice = role?.voices?.get(ttsEngine)

        rbVoiceNarrator.text = getString(
            R.string.book_role_voice_narrator_option,
            narratorVoice ?: getString(R.string.book_role_voice_not_configured)
        )
        rbVoiceDialog.text = getString(
            R.string.book_role_voice_dialog_option,
            dialogVoice ?: getString(R.string.book_role_voice_not_configured)
        )
        rbVoiceNarrator.isEnabled = supported && !narratorVoice.isNullOrBlank()
        rbVoiceDialog.isEnabled = supported && !dialogVoice.isNullOrBlank()
        rbVoiceDefault.isEnabled = supported
        rbVoiceCustom.isEnabled = supported
        rgVoice.isEnabled = supported
        tilCustomVoice.isEnabled = supported

        when (configuredVoice) {
            BookReadAloudRole.VOICE_BOOK_NARRATOR -> rbVoiceNarrator.isChecked = true
            BookReadAloudRole.VOICE_BOOK_DIALOG -> rbVoiceDialog.isChecked = true
            null, "" -> rbVoiceDefault.isChecked = true
            else -> {
                rbVoiceCustom.isChecked = true
                etCustomVoice.setText(configuredVoice)
            }
        }
        if (rgVoice.checkedRadioButtonId == View.NO_ID) {
            rbVoiceDefault.isChecked = true
        }
        updateCustomVoiceState()
        rgVoice.setOnCheckedChangeListener { _, _ -> updateCustomVoiceState() }
        tvVoiceHint.text = if (supported) {
            getString(R.string.book_role_voice_select_hint)
        } else {
            getString(R.string.book_role_voice_unsupported)
        }
    }

    private fun updateCustomVoiceState() = binding.run {
        val customEnabled = rbVoiceCustom.isEnabled && rbVoiceCustom.isChecked
        tilCustomVoice.isEnabled = customEnabled
        etCustomVoice.isEnabled = customEnabled
    }

    private fun saveRole() {
        val book = ReadBook.book ?: run {
            dismissAllowingStateLoss()
            return
        }
        val existingRole = role ?: run {
            dismissAllowingStateLoss()
            return
        }

        val name = binding.etRoleName.text?.toString()?.trim() ?: ""
        if (name.isBlank()) {
            binding.tilRoleName.error = getString(R.string.book_role_name_required)
            return
        }

        val nameError = book.validateRoleName(name, existingRole.id)
        if (nameError != null) {
            binding.tilRoleName.error = getString(R.string.book_role_name_conflict)
            return
        }
        binding.tilRoleName.error = null

        val aliasesText = binding.etAliases.text?.toString() ?: ""
        val rawAliases = aliasesText.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
        val aliases = book.normalizeAliases(rawAliases)
        for (alias in aliases) {
            val aliasError = book.validateRoleAlias(alias, existingRole.id, name)
            if (aliasError != null) {
                binding.tilAliases.error = getString(R.string.book_role_alias_conflict)
                return
            }
        }
        binding.tilAliases.error = null

        val customVoice = binding.etCustomVoice.text?.toString()?.trim().orEmpty()
        if (binding.rbVoiceCustom.isChecked && customVoice.isBlank()) {
            binding.tilCustomVoice.error = getString(R.string.book_role_voice_required)
            return
        }
        binding.tilCustomVoice.error = null

        val note = binding.etNote.text?.toString()?.trim() ?: ""
        val locked = binding.switchLocked.isChecked

        existingRole.name = name
        existingRole.aliases = aliases
        existingRole.note = note
        existingRole.locked = locked

        if (binding.rgVoice.isEnabled) {
            ttsEngine?.let { engine ->
                val selectedVoice = when (binding.rgVoice.checkedRadioButtonId) {
                    R.id.rb_voice_narrator -> BookReadAloudRole.VOICE_BOOK_NARRATOR
                    R.id.rb_voice_dialog -> BookReadAloudRole.VOICE_BOOK_DIALOG
                    R.id.rb_voice_custom -> customVoice
                    else -> null
                }
                selectedVoice?.let { existingRole.voices[engine] = it }
                    ?: existingRole.voices.remove(engine)
            }
        }

        book.addOrUpdateReadAloudRole(existingRole)
        book.save()
        ReadAloud.upReadAloudClass()
        dismissAllowingStateLoss()
    }

    companion object {
        private const val ARG_ROLE = "role"
        private const val ARG_TTS_ENGINE = "ttsEngine"

        fun newInstance(role: BookReadAloudRole, ttsEngine: String?): BookRoleEditDialog {
            return BookRoleEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_ROLE, GSON.toJson(role))
                    putString(ARG_TTS_ENGINE, ttsEngine)
                }
            }
        }
    }
}
