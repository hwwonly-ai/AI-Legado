package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAiPromptConfigBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ai.AiPromptConfig
import io.legado.app.model.ai.AiPromptStore
import io.legado.app.model.ai.DefaultAiPrompts
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * AI 章节解析 Prompt 管理弹窗。
 *
 * MVP6B 只做查看/编辑/保存/恢复默认/导入/导出，不调用 AI，不解析章节。
 */
class AiPromptConfigDialog : BaseDialogFragment(R.layout.dialog_ai_prompt_config) {

    private val binding by viewBinding(DialogAiPromptConfigBinding::bind)

    /** 当前已持久化的配置，用于判断编辑框是否有未保存修改。 */
    private var saved: AiPromptConfig = AiPromptConfig.default()

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
        toolBar.setTitle(R.string.ai_prompt_config)
        tvCancel.setOnClickListener { handleClose() }
        tvSave.setOnClickListener { savePrompt() }
        tvRestore.setOnClickListener { confirmRestoreDefault() }
        tvCopy.setOnClickListener { copyPrompt() }
        tvImport.setOnClickListener { importFromClipboard() }
    }

    private fun initData() {
        saved = AiPromptStore.load()
        binding.etPrompt.setText(saved.promptText)
        refreshStatus(saved, dirty = false)
    }

    /** 编辑框内容是否与已保存内容不一致（基于规范化文本比较）。 */
    private fun isDirty(): Boolean {
        val current = DefaultAiPrompts.normalizePrompt(binding.etPrompt.text?.toString().orEmpty())
        val savedText = DefaultAiPrompts.normalizePrompt(saved.promptText)
        return current != savedText
    }

    /** 根据当前编辑框文本计算状态摘要。dirty=true 表示存在未保存修改。 */
    private fun refreshStatus(config: AiPromptConfig, dirty: Boolean) {
        val hashShort = config.contentHash.take(8)
        val defaultLabel = if (config.isDefault) {
            getString(R.string.ai_prompt_is_default)
        } else {
            getString(R.string.ai_prompt_is_custom)
        }
        val updatedLabel = if (config.updatedAt > 0L) {
           getString(R.string.ai_prompt_updated_at, formatTime(config.updatedAt))
        } else {
            getString(R.string.ai_prompt_never_saved)
        }
        val dirtyLabel = if (dirty) " · ${getString(R.string.ai_prompt_unsaved)}" else ""
        binding.tvStatus.text = getString(
            R.string.ai_prompt_status_summary,
            config.version.toString(),
            hashShort,
            defaultLabel,
            updatedLabel
        ) + dirtyLabel
    }

    private fun savePrompt() {
        val text = binding.etPrompt.text?.toString().orEmpty()
        val normalized = DefaultAiPrompts.normalizePrompt(text)
        if (normalized.isEmpty()) {
            binding.tilPrompt.error = getString(R.string.ai_prompt_empty_error)
            return
        }
        binding.tilPrompt.error = null
        val now = System.currentTimeMillis()
        val result = AiPromptStore.save(text, now) ?: run {
            toastOnUi(R.string.ai_prompt_empty_error)
            return
        }
        saved = result
        binding.etPrompt.setText(result.promptText)
        refreshStatus(result, dirty = false)
        toastOnUi(R.string.ai_prompt_saved)
    }

    private fun confirmRestoreDefault() {
        alert(message = getString(R.string.ai_prompt_restore_confirm)) {
            okButton {
                val now = System.currentTimeMillis()
                val result = AiPromptStore.restoreDefault(now)
                saved = result
                binding.etPrompt.setText(result.promptText)
                refreshStatus(result, dirty = false)
                toastOnUi(R.string.ai_prompt_restored)
            }
            cancelButton()
        }
    }

    private fun copyPrompt() {
        val text = binding.etPrompt.text?.toString().orEmpty()
        if (text.isBlank()) {
            toastOnUi(R.string.ai_prompt_empty_error)
            return
        }
        requireContext().sendToClip(text)
        toastOnUi(R.string.ai_prompt_copied)
    }

    private fun importFromClipboard() {
        val clip = requireContext().getClipText()
        if (clip.isNullOrBlank()) {
            toastOnUi(R.string.ai_prompt_import_empty)
            return
        }
        binding.etPrompt.setText(clip)
        // 粘贴只填编辑框，不自动保存；标记未保存。
        val tempConfig = buildTempConfig(clip)
        refreshStatus(tempConfig, dirty = true)
        toastOnUi(R.string.ai_prompt_imported)
    }

    private fun handleClose() {
        if (isDirty()) {
            alert(message = getString(R.string.ai_prompt_unsaved_confirm)) {
                okButton { dismissAllowingStateLoss() }
                cancelButton()
            }
        } else {
            dismissAllowingStateLoss()
        }
    }

    /** 基于编辑框临时文本构造配置，用于显示状态摘要（不持久化）。 */
    private fun buildTempConfig(text: String): AiPromptConfig {
        val hash = DefaultAiPrompts.promptHash(text)
        return AiPromptConfig(
            promptText = DefaultAiPrompts.normalizePrompt(text),
            version = DefaultAiPrompts.DEFAULT_AI_PROMPT_VERSION,
            contentHash = hash,
            updatedAt = saved.updatedAt,
            isDefault = hash == DefaultAiPrompts.DEFAULT_PROMPT_HASH
        )
    }

    private fun formatTime(timestamp: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return format.format(java.util.Date(timestamp))
    }
}
