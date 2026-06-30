package io.legado.app.model.ai

/**
 * AI 章节解析 Prompt 的当前配置快照。
 *
 * - [promptText]：当前 Prompt 全文。
 * - [version]：Prompt 结构版本，用于默认 Prompt 升级提示。
 * - [contentHash]：Prompt 内容 hash，用于 MVP6D 缓存失效判断。
 * - [updatedAt]：用户保存或恢复默认的时间，0 表示从未保存。
 * - [isDefault]：当前 Prompt 是否与内置默认 Prompt 一致。
 */
data class AiPromptConfig(
    val promptText: String,
    val version: Int,
    val contentHash: String,
    val updatedAt: Long,
    val isDefault: Boolean
) {

    companion object {

        /** 内置默认 Prompt 对应的配置，未保存时使用。 */
        fun default(): AiPromptConfig = AiPromptConfig(
            promptText = DefaultAiPrompts.DEFAULT_AI_PROMPT,
            version = DefaultAiPrompts.DEFAULT_AI_PROMPT_VERSION,
            contentHash = DefaultAiPrompts.DEFAULT_PROMPT_HASH,
            updatedAt = 0L,
            isDefault = true
        )
    }
}
