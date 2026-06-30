package io.legado.app.model.ai

import io.legado.app.model.ReadAloudSegment

/**
 * AI 单章解析结果。
 *
 * MVP6C 不落库，该结果只用于当前播放流程。
 * - [fallbackUsed] 为 true 时 [segments] 为 MVP3 规则分段，[errorMessage] 说明回退原因。
 */
data class AiChapterParseResult(
    val segments: List<ReadAloudSegment>,
    val provider: String,
    val model: String,
    val promptHash: String,
    val promptVersion: Int,
    val elapsedMs: Long,
    val fallbackUsed: Boolean,
    val errorMessage: String? = null
)

/** AI 解析回退原因，统一用于日志和结果。 */
enum class AiParseFallbackReason {
    AiDisabled,
    MissingConfig,
    EmptyPrompt,
    ChapterTooLong,
    RequestFailed,
    Unauthorized,
    EmptySegments,
    JsonInvalid,
    MappingFailed;

    fun logValue(): String = when (this) {
        AiDisabled -> "aiDisabled"
        MissingConfig -> "missingConfig"
        EmptyPrompt -> "emptyPrompt"
        ChapterTooLong -> "chapterTooLong"
        RequestFailed -> "requestFailed"
        Unauthorized -> "unauthorized"
        EmptySegments -> "emptySegments"
        JsonInvalid -> "jsonInvalid"
        MappingFailed -> "mappingFailed"
    }
}
