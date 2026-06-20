package io.legado.app.model.ai

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * AI Prompt 的持久化读写。
 *
 * MVP6B 使用 SharedPreferences，不新增 Room 表，避免引入数据库迁移。
 * 未保存过时 [load] 返回内置默认 Prompt，[updatedAt] 为 0。
 */
object AiPromptStore {

    /**
     * 读取当前 Prompt 配置。
     * 未保存过时返回默认 Prompt，且不写入 SharedPreferences。
     */
    fun load(): AiPromptConfig {
        val savedText = appCtx.getPrefString(PreferKey.aiPromptText, null)
        if (savedText.isNullOrEmpty()) {
            AppLog.putDebug("aiPrompt load default (no saved text)")
            return AiPromptConfig.default()
        }
        val normalized = DefaultAiPrompts.normalizePrompt(savedText)
        val hash = DefaultAiPrompts.promptHash(savedText)
        val version = appCtx.getPrefString(PreferKey.aiPromptVersion, "")
            ?.toIntOrNull() ?: DefaultAiPrompts.DEFAULT_AI_PROMPT_VERSION
        val updatedAt = appCtx.getPrefLong(PreferKey.aiPromptUpdatedAt, 0L)
        val isDefault = hash == DefaultAiPrompts.DEFAULT_PROMPT_HASH
        AppLog.putDebug(
            "aiPrompt load saved version=$version hash=${hash.take(8)} " +
                "updatedAt=$updatedAt isDefault=$isDefault"
        )
        return AiPromptConfig(
            promptText = normalized,
            version = version,
            contentHash = hash,
            updatedAt = updatedAt,
            isDefault = isDefault
        )
    }

    /**
     * 保存 Prompt。会先 trim 首尾空白并校验非空。
     * 返回保存后的配置，输入为空时返回 null。
     */
    fun save(text: String, now: Long): AiPromptConfig? {
        val normalized = DefaultAiPrompts.normalizePrompt(text)
        if (normalized.isEmpty()) {
            AppLog.putDebug("aiPrompt save rejected empty")
            return null
        }
        val hash = DefaultAiPrompts.promptHash(normalized)
        val isDefault = hash == DefaultAiPrompts.DEFAULT_PROMPT_HASH
        appCtx.putPrefString(PreferKey.aiPromptText, normalized)
        appCtx.putPrefString(PreferKey.aiPromptVersion, DefaultAiPrompts.DEFAULT_AI_PROMPT_VERSION.toString())
        appCtx.putPrefString(PreferKey.aiPromptHash, hash)
        appCtx.putPrefLong(PreferKey.aiPromptUpdatedAt, now)
        AppLog.putDebug(
            "aiPrompt save version=${DefaultAiPrompts.DEFAULT_AI_PROMPT_VERSION} " +
                "hash=${hash.take(8)} updatedAt=$now isDefault=$isDefault"
        )
        return AiPromptConfig(
            promptText = normalized,
            version = DefaultAiPrompts.DEFAULT_AI_PROMPT_VERSION,
            contentHash = hash,
            updatedAt = now,
            isDefault = isDefault
        )
    }

    /**
     * 恢复默认 Prompt，等价于保存内置默认 Prompt。
     */
    fun restoreDefault(now: Long): AiPromptConfig {
        val saved = save(DefaultAiPrompts.DEFAULT_AI_PROMPT, now)
        // 恢复默认后明确标记 isDefault=true（save 已能判断，这里保持语义清晰）
        return saved ?: AiPromptConfig.default().copy(updatedAt = now)
    }
}
