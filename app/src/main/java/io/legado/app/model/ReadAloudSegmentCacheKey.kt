package io.legado.app.model

import io.legado.app.utils.MD5Utils

object ReadAloudSegmentCacheKey {
    const val PRODUCER_RULE = "rule"
    const val RULE_SEGMENT_VERSION = 2

    /**
     * AI 对白标注结果缓存 producer。
     *
     * 与 [PRODUCER_RULE] 分开：规则缓存只保存规则 Segment，AI 缓存保存规则边界 + AI 标注后的 Segment。
     * AI 缓存失效不会破坏规则 fallback。
     */
    const val PRODUCER_AI_DIALOG_LABEL = "ai_dialog_label"
    const val AI_DIALOG_LABEL_VERSION = 2

    fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    fun contentHash(content: String): String {
        return MD5Utils.md5Encode(normalizeContent(content))
    }

    /**
     * 构造 AI 对白标注缓存的不透明 producer key。
     *
     * 把全部失效维度拼成单一字符串，复用现有 (bookUrl, chapterUrl, producer) 主键，
     * 不引入 Room 迁移。维度与 MVP6D 计划第 5 节一致：
     * - promptVersion / promptHash：Prompt 变化失效。
     * - provider / selectedInstanceId / endpointHash / model / mode：AI 配置变化失效。
     *
     * 不放入：API Key（同 endpoint/model 换 key 不失效）。
     *
     * 返回值只用于主键区分与日志截断展示，不反解析。
     */
    fun aiDialogLabelProducer(
        promptVersion: Int,
        promptHash: String,
        provider: String,
        selectedInstanceId: Long?,
        endpointHash: String,
        model: String,
        mode: String,
        thinkingEnabled: Boolean,
        reasoningEffort: String?
    ): String {
        return buildString {
            append(PRODUCER_AI_DIALOG_LABEL)
            append("|v=").append(AI_DIALOG_LABEL_VERSION)
            append("|pv=").append(promptVersion)
            append("|ph=").append(promptHash)
            append("|p=").append(provider)
            append("|inst=").append(selectedInstanceId ?: "none")
            append("|ep=").append(endpointHash)
            append("|m=").append(model)
            append("|mode=").append(mode)
            append("|think=").append(thinkingEnabled)
            append("|effort=").append(reasoningEffort ?: "none")
        }
    }

    /** 日志用的 key 摘要，只取前 32 位避免泄露。 */
    fun logKey(producer: String): String {
        return if (producer.length <= 32) producer else producer.take(32)
    }
}
