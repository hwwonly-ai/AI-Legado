package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookReadAloudRole
import io.legado.app.databinding.DialogBookRoleEditBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudAudioCache
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookRoleEditDialog : BaseDialogFragment(R.layout.dialog_book_role_edit) {

    private val binding by viewBinding(DialogBookRoleEditBinding::bind)
    private var role: BookReadAloudRole? = null
    private var ttsEngine: String? = null
    private var narratorVoice: String? = null
    private var dialogVoice: String? = null
    private var isSystem: Boolean = false
    private var configuredVoiceBeforeSave: String? = null

    private val categoryChips = mutableMapOf<String, TextView>()
    private val attributeChips = mutableMapOf<String, TextView>()
    private var selectedCategory: String = BookReadAloudRole.RoleCategory.MINOR_SUPPORTING
    private var selectedAttribute: String = BookReadAloudRole.RoleAttribute.MIDDLE_MALE

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.85f)
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
        tvSelectVoice.setOnClickListener { showVoiceSelector() }
        tvClearVoice.setOnClickListener { clearCustomVoice() }
    }

    private fun initData() {
        binding.run {
            val roleJson = arguments?.getString(ARG_ROLE)
            role = if (roleJson != null) {
                GSON.fromJsonObject<BookReadAloudRole>(roleJson).getOrNull()
            } else null
            ttsEngine = arguments?.getString(ARG_TTS_ENGINE) ?: ReadAloud.ttsEngine

            val existingRole = role ?: run {
                dismissAllowingStateLoss()
                return
            }
            isSystem = existingRole.systemRole
            selectedCategory = existingRole.roleCategory
            selectedAttribute = existingRole.roleAttribute

            etRoleName.setText(existingRole.name)
            etAliases.setText(existingRole.aliases.joinToString(", "))
            etNote.setText(existingRole.note)
            switchLocked.isChecked = existingRole.locked

            configuredVoiceBeforeSave = existingRole.voices[ttsEngine]
            val configuredVoice = configuredVoiceBeforeSave

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
            refreshVoiceEngineState()
            updateCustomVoiceState()
            rgVoice.setOnCheckedChangeListener { _, _ -> updateCustomVoiceState() }

            setupCategoryAndAttribute(existingRole)
        }
    }

    private fun refreshVoiceEngineState() = binding.run {
        val httpTTS = ttsEngine?.toLongOrNull()?.let {
            appDb.httpTTSDao.get(it)
        }
        val supported = httpTTS?.isSupportedRoleVoice() == true
        narratorVoice = ReadBook.book?.getNarratorVoice(ttsEngine)
        dialogVoice = ReadBook.book?.getDialogVoice(ttsEngine)
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
        tvSelectVoice.isEnabled = supported
        tvSelectVoice.alpha = if (supported) 1f else 0.4f
        tvClearVoice.isEnabled = supported
        tvClearVoice.alpha = if (supported) 1f else 0.4f
        tvVoiceHint.text = if (supported) {
            getString(R.string.book_role_voice_select_hint)
        } else {
            getString(R.string.book_role_voice_picker_unsupported)
        }
        updateCustomVoiceState()
    }

    private fun showVoiceSelector() {
        val existingRole = role ?: return
        val dialog = BookRoleVoiceSelectDialog.newInstance(existingRole.id, ttsEngine).apply {
            onVoiceSelected = { engine, preset ->
                ttsEngine = engine
                refreshVoiceEngineState()
                binding.rbVoiceCustom.isChecked = true
                binding.etCustomVoice.setText(preset.requestValue)
                binding.tilCustomVoice.error = null
                updateCustomVoiceState()
            }
        }
        showDialogFragment(dialog)
    }

    private fun clearCustomVoice() = binding.run {
        rbVoiceDefault.isChecked = true
        etCustomVoice.setText("")
        tilCustomVoice.error = null
        updateCustomVoiceState()
    }

    private fun setupCategoryAndAttribute(existingRole: BookReadAloudRole) {
        binding.run {
            if (isSystem) {
                // 龙套：名称/分类/属性只读，隐藏分类属性选择区与锁定区，显示系统只读提示。
                tvCategoryLabel.visibility = View.GONE
                flCategory.visibility = View.GONE
                tvAttrLabel.visibility = View.GONE
                flAttribute.visibility = View.GONE
                llLocked.visibility = View.GONE
                tvLockedHint.visibility = View.GONE
                etRoleName.isEnabled = false
                tvSystemInfo.text = getString(
                    R.string.book_role_system_readonly
                ) + " · " + attributeLabel(existingRole.roleAttribute)
                tvSystemInfo.visibility = View.VISIBLE
                return
            }

            tvSystemInfo.visibility = View.GONE
            buildCategoryChips()
            buildAttributeChips()
            refreshAttributeEnabled()
        }
    }

    private fun buildCategoryChips() = binding.run {
        flCategory.removeAllViews()
        categoryChips.clear()
        val categories = listOf(
            BookReadAloudRole.RoleCategory.MALE_LEAD to R.string.book_role_category_male_lead,
            BookReadAloudRole.RoleCategory.FEMALE_LEAD to R.string.book_role_category_female_lead,
            BookReadAloudRole.RoleCategory.MAJOR_SUPPORTING to R.string.book_role_category_major,
            BookReadAloudRole.RoleCategory.MINOR_SUPPORTING to R.string.book_role_category_minor
        )
        for ((category, labelRes) in categories) {
            val chip = createChip(getString(labelRes))
            chip.setOnClickListener {
                selectedCategory = category
                applyCategorySelection()
                refreshAttributeEnabled()
            }
            flCategory.addView(chip)
            categoryChips[category] = chip
        }
        applyCategorySelection()
    }

    private fun applyCategorySelection() {
        for ((category, chip) in categoryChips) {
            applyChipSelectedState(chip, category == selectedCategory)
        }
    }

    private fun buildAttributeChips() = binding.run {
        flAttribute.removeAllViews()
        attributeChips.clear()
        for (attr in BookReadAloudRole.RoleAttribute.ALL) {
            val labelRes = when (attr) {
                BookReadAloudRole.RoleAttribute.BOY -> R.string.book_role_attr_boy
                BookReadAloudRole.RoleAttribute.YOUNG_MALE -> R.string.book_role_attr_young_male
                BookReadAloudRole.RoleAttribute.MIDDLE_MALE -> R.string.book_role_attr_middle_male
                BookReadAloudRole.RoleAttribute.ELDER_MALE -> R.string.book_role_attr_elder_male
                BookReadAloudRole.RoleAttribute.GIRL -> R.string.book_role_attr_girl
                BookReadAloudRole.RoleAttribute.YOUNG_FEMALE -> R.string.book_role_attr_young_female
                BookReadAloudRole.RoleAttribute.MIDDLE_FEMALE -> R.string.book_role_attr_middle_female
                BookReadAloudRole.RoleAttribute.ELDER_FEMALE -> R.string.book_role_attr_elder_female
                else -> 0
            }
            if (labelRes == 0) continue
            val chip = createChip(getString(labelRes))
            chip.setOnClickListener {
                if (chip.isEnabled) {
                    selectedAttribute = attr
                    applyAttributeSelection()
                }
            }
            flAttribute.addView(chip)
            attributeChips[attr] = chip
        }
        applyAttributeSelection()
    }

    private fun applyAttributeSelection() {
        for ((attr, chip) in attributeChips) {
            applyChipSelectedState(chip, attr == selectedAttribute)
        }
    }

    private fun applyChipSelectedState(chip: TextView, selected: Boolean) {
        chip.isSelected = selected
        if (selected) {
            chip.setTextColor(Color.WHITE)
            chip.setBackgroundColor(Color.parseColor("#888888"))
        } else {
            chip.setTextColor(resolveChipTextColor())
            chip.setBackgroundResource(android.R.drawable.list_selector_background)
        }
    }

    /**
     * 男主角仅启用男属性，女主角仅启用女属性，其余全启用。
     * 当前选中属性被禁则自动选首个允许项。
     */
    private fun refreshAttributeEnabled() {
        val allowed = when (selectedCategory) {
            BookReadAloudRole.RoleCategory.MALE_LEAD -> BookReadAloudRole.RoleAttribute.MALE
            BookReadAloudRole.RoleCategory.FEMALE_LEAD -> BookReadAloudRole.RoleAttribute.FEMALE
            else -> BookReadAloudRole.RoleAttribute.MALE + BookReadAloudRole.RoleAttribute.FEMALE
        }
        for ((attr, chip) in attributeChips) {
            chip.isEnabled = attr in allowed
            chip.alpha = if (chip.isEnabled) 1f else 0.4f
        }
        if (selectedAttribute !in allowed) {
            selectedAttribute = allowed.first()
            applyAttributeSelection()
        }
    }

    private fun createChip(text: String): TextView {
        val chip = TextView(requireContext())
        val lp = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 12, 8)
        chip.layoutParams = lp
        chip.text = text
        chip.setPadding(28, 10, 28, 10)
        chip.textSize = 13f
        chip.setBackgroundResource(android.R.drawable.list_selector_background)
        chip.setTextColor(resolveChipTextColor())
        chip.isClickable = true
        return chip
    }

    private fun resolveChipTextColor(): Int {
        val tv = TextView(requireContext())
        return tv.currentTextColor
    }

    private fun attributeLabel(attr: String): String {
        val resId = when (attr) {
            BookReadAloudRole.RoleAttribute.BOY -> R.string.book_role_attr_boy
            BookReadAloudRole.RoleAttribute.YOUNG_MALE -> R.string.book_role_attr_young_male
            BookReadAloudRole.RoleAttribute.MIDDLE_MALE -> R.string.book_role_attr_middle_male
            BookReadAloudRole.RoleAttribute.ELDER_MALE -> R.string.book_role_attr_elder_male
            BookReadAloudRole.RoleAttribute.GIRL -> R.string.book_role_attr_girl
            BookReadAloudRole.RoleAttribute.YOUNG_FEMALE -> R.string.book_role_attr_young_female
            BookReadAloudRole.RoleAttribute.MIDDLE_FEMALE -> R.string.book_role_attr_middle_female
            BookReadAloudRole.RoleAttribute.ELDER_FEMALE -> R.string.book_role_attr_elder_female
            else -> 0
        }
        return if (resId != 0) getString(resId) else ""
    }

    private fun updateCustomVoiceState() = binding.run {
        val customEnabled = rbVoiceCustom.isEnabled && rbVoiceCustom.isChecked
        tilCustomVoice.isEnabled = customEnabled
        etCustomVoice.isEnabled = customEnabled
    }

    @SuppressLint("StringFormatInvalid")
    private fun saveRole() {
        val book = ReadBook.book ?: run {
            dismissAllowingStateLoss()
            return
        }
        val existingRole = role ?: run {
            dismissAllowingStateLoss()
            return
        }

        if (isSystem) {
            if (!saveSystemRole(book, existingRole)) return
            afterSave(book, existingRole)
            return
        }

        // 非系统角色：校验分类上限。
        val capError = book.validateRoleCategoryCap(selectedCategory, existingRole.id)
        if (capError != null) {
            val max = BookReadAloudRole.RoleCategory.MAX_COUNT[selectedCategory] ?: 0
            binding.tilRoleName.error = getString(R.string.book_role_category_cap_exceeded, max)
            return
        }
        binding.tilRoleName.error = null

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

        // 男主角强制男属性，女主角强制女属性。
        val finalAttribute = when (selectedCategory) {
            BookReadAloudRole.RoleCategory.MALE_LEAD ->
                if (selectedAttribute in BookReadAloudRole.RoleAttribute.MALE) selectedAttribute
                else BookReadAloudRole.RoleAttribute.MIDDLE_MALE
            BookReadAloudRole.RoleCategory.FEMALE_LEAD ->
                if (selectedAttribute in BookReadAloudRole.RoleAttribute.FEMALE) selectedAttribute
                else BookReadAloudRole.RoleAttribute.MIDDLE_FEMALE
            else -> selectedAttribute
        }

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
        existingRole.roleCategory = selectedCategory
        existingRole.roleAttribute = finalAttribute

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
        afterSave(book, existingRole)
    }

    private fun saveSystemRole(book: io.legado.app.data.entities.Book, existingRole: BookReadAloudRole): Boolean {
        // 龙套：仅别名和音色可编辑，分类/属性/名称/锁定/系统标记保持不变。
        val aliasesText = binding.etAliases.text?.toString() ?: ""
        val rawAliases = aliasesText.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() }
        val aliases = book.normalizeAliases(rawAliases)
        for (alias in aliases) {
            val aliasError = book.validateRoleAlias(alias, existingRole.id, existingRole.name)
            if (aliasError != null) {
                binding.tilAliases.error = getString(R.string.book_role_alias_conflict)
                return false
            }
        }
        binding.tilAliases.error = null

        val customVoice = binding.etCustomVoice.text?.toString()?.trim().orEmpty()
        if (binding.rbVoiceCustom.isChecked && customVoice.isBlank()) {
            binding.tilCustomVoice.error = getString(R.string.book_role_voice_required)
            return false
        }
        binding.tilCustomVoice.error = null

        existingRole.aliases = aliases
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
        return true
    }

    private fun afterSave(
        book: io.legado.app.data.entities.Book,
        existingRole: BookReadAloudRole
    ) {
        // MVP7 测试期：音色变更后清理当前章节音频缓存（仅 MP3，不清 segment 缓存）。
        val newVoice = existingRole.voices[ttsEngine]
        if (newVoice != configuredVoiceBeforeSave) {
            ReadAloudAudioCache.clearChapterAudioCache(
                requireContext().cacheDir,
                ReadBook.curTextChapter?.title
            )
        }
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
