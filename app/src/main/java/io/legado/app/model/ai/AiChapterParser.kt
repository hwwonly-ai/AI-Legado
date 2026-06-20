package io.legado.app.model.ai

import io.legado.app.constant.AppLog
import io.legado.app.model.ReadAloudChapterSegmenter
import io.legado.app.model.ReadAloudSegment

/**
 * AI 单章解析统一入口。
 *
 * 负责：读取 AI 服务配置 + Prompt → 调用 AI → 解析 JSON → 映射原文位置 → 失败回退规则分段。
 * MVP6C 不写缓存；任何失败都返回规则分段结果，保证朗读链路可继续。
 *
 * 日志前缀统一为 aiChapterParse，不输出 API Key、正文、Prompt 全文和 AI 原始返回。
 */
object AiChapterParser {

    private val client = OpenAiCompatibleClient()

    /** MVP6C 单章最大字符数，超过直接回退规则分段，不做切片。 */
    private const val AI_PARSE_MAX_CHARS = 12000

    /**
     * 解析请求的 maxTokens 下限与上限。
     * AI 输出 JSON 需容纳原文片段 + 结构字段（role/emotion 等），按章节字符数估算并留冗余。
     * Fast 模式默认 maxTokens=4096 对章节 JSON 远不够，会导致输出被截断、JSON 解析失败。
     */
    private const val MIN_PARSE_MAX_TOKENS = 8192
    private const val MAX_PARSE_MAX_TOKENS = 32000
    private const val PARSE_TOKENS_PER_CHAR = 2
    private const val PARSE_TOKENS_STRUCTURE_OVERHEAD = 2048

    /**
     * 解析当前章节。
     *
     * @param chapterIndex 章节序号，仅用于日志。
     * @param chapterContent 章节纯文本（含换行）。
     * @return 解析结果；fallbackUsed=true 时 segments 为规则分段。
     */
    suspend fun parse(
        chapterIndex: Int,
        chapterContent: String,
        requestTimeoutSeconds: Int? = null
    ): AiChapterParseResult {
        val startNs = System.nanoTime()
        val promptConfig = AiPromptStore.load()

        val config = AiServiceConfig.load()
        // 前置校验：未启用 / 缺配置 / Prompt 空 → 直接回退
        if (!config.enabled) {
            return fallback(
                chapterIndex, chapterContent, config, promptConfig, startNs,
                AiParseFallbackReason.AiDisabled
            )
        }
        if (config.endpoint.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            return fallback(
                chapterIndex, chapterContent, config, promptConfig, startNs,
                AiParseFallbackReason.MissingConfig
            )
        }
        if (promptConfig.promptText.isBlank()) {
            return fallback(
                chapterIndex, chapterContent, config, promptConfig, startNs,
                AiParseFallbackReason.EmptyPrompt
            )
        }
        if (chapterContent.length > AI_PARSE_MAX_CHARS) {
            return fallback(
                chapterIndex, chapterContent, config, promptConfig, startNs,
                AiParseFallbackReason.ChapterTooLong
            )
        }

        AppLog.putDebug(
            "aiChapterParse start chapter=$chapterIndex chars=${chapterContent.length} " +
                "provider=${config.provider} model=${config.model} " +
                "promptHash=${promptConfig.contentHash.take(8)} mode=${config.mode}"
        )

        val messages = listOf(
            AiChatMessage(role = "system", content = promptConfig.promptText),
            AiChatMessage(role = "user", content = chapterContent)
        )
        val parseConfig = config
            .withParseMaxTokens(chapterContent.length)
            .withRequestTimeout(requestTimeoutSeconds)
        val chatResult = client.chatCompletion(parseConfig, messages)
        when (chatResult) {
            is AiChatResult.Failure -> {
                val reason = if (chatResult.type == AiConnectionError.Unauthorized) {
                    AiParseFallbackReason.Unauthorized
                } else {
                    AiParseFallbackReason.RequestFailed
                }
                return fallback(
                    chapterIndex, chapterContent, config, promptConfig, startNs, reason,
                    detail = "type=${chatResult.type} code=${chatResult.code} " +
                        "message=${chatResult.message.logSafe()}"
                )
            }
            is AiChatResult.Success -> Unit
        }

        val rawContent = (chatResult as AiChatResult.Success).content
        AppLog.putDebug(
            "aiChapterParse response chapter=$chapterIndex elapsedMs=${chatResult.elapsedMs} " +
                "rawLength=${rawContent.length}"
        )

        val parseResult = AiSegmentResponse.parse(rawContent)
        val aiSegments = when (parseResult) {
            is AiSegmentResponse.ParseResult.Ok -> parseResult.segments
            is AiSegmentResponse.ParseResult.Fail -> {
                logParseDiagnosis(chapterIndex, parseResult.reason, rawContent)
                return fallback(
                    chapterIndex, chapterContent, config, promptConfig, startNs,
                    AiParseFallbackReason.EmptySegments,
                    detail = "parseFail=${parseResult.reason}"
                )
            }
        }

        val mapped = AiSegmentMapper.map(aiSegments, chapterContent)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        when (mapped) {
            is AiSegmentMapper.MapResult.Ok -> {
                AppLog.putDebug(
                    "aiChapterParse mapped chapter=$chapterIndex aiSegments=${mapped.total} " +
                        "mapped=${mapped.mapped} filledGaps=${mapped.filledGaps} " +
                        "filledGapChars=${mapped.filledGapChars} " +
                        "narratorDialogSplits=${mapped.narratorDialogSplits} elapsedMs=$elapsedMs"
                )
                return AiChapterParseResult(
                    segments = mapped.segments,
                    provider = config.provider.name,
                    model = config.model,
                    promptHash = promptConfig.contentHash,
                    promptVersion = promptConfig.version,
                    elapsedMs = elapsedMs,
                    fallbackUsed = false
                )
            }
            is AiSegmentMapper.MapResult.Failed -> {
                val fail = mapped.firstFailure
                val failDiag = if (fail != null) {
                    " firstFailIdx=${fail.index} failLen=${fail.textLen} cursor=${fail.cursor} " +
                        "occurrence=${fail.occurrence} firstOccurrence=${fail.firstOccurrence} " +
                        "failHead=[${fail.head}] failTail=[${fail.tail}] " +
                        "failText=[${fail.fullText}]"
                } else ""
                AppLog.putDebug(
                    "aiChapterParse fallback chapter=$chapterIndex reason=mappingFailed " +
                        "mapped=${mapped.mapped} total=${mapped.total} elapsedMs=$elapsedMs$failDiag"
                )
                return AiChapterParseResult(
                    segments = ruleSegments(chapterContent),
                    provider = config.provider.name,
                    model = config.model,
                    promptHash = promptConfig.contentHash,
                    promptVersion = promptConfig.version,
                    elapsedMs = elapsedMs,
                    fallbackUsed = true,
                    errorMessage = AiParseFallbackReason.MappingFailed.logValue()
                )
            }
        }
    }

    private suspend fun fallback(
        chapterIndex: Int,
        chapterContent: String,
        config: AiServiceConfig,
        promptConfig: AiPromptConfig,
        startNs: Long,
        reason: AiParseFallbackReason,
        detail: String? = null
    ): AiChapterParseResult {
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val detailPart = if (detail != null) " $detail" else ""
        AppLog.putDebug(
            "aiChapterParse fallback chapter=$chapterIndex reason=${reason.logValue()} " +
                "elapsedMs=$elapsedMs$detailPart"
        )
        return AiChapterParseResult(
            segments = ruleSegments(chapterContent),
            provider = config.provider.name,
            model = config.model,
            promptHash = promptConfig.contentHash,
            promptVersion = promptConfig.version,
            elapsedMs = elapsedMs,
            fallbackUsed = true,
            errorMessage = reason.logValue()
        )
    }

    private fun ruleSegments(chapterContent: String): List<ReadAloudSegment> =
        ReadAloudChapterSegmenter.buildChapterRuleSegments(chapterContent)

    /**
     * 临时诊断：JSON 解析失败时输出 content 的结构特征（非正文）。
     * 仅输出长度/首字符/关键标记命中情况/首尾 60 字符，用于定位 AI 返回格式问题。
     * 定位完成后可移除。
     */
    private fun logParseDiagnosis(
        chapterIndex: Int,
        reason: AiSegmentResponse.ParseFail,
        content: String
    ) {
        val head = content.take(60).replace("\n", "\\n")
        val tail = content.takeLast(60).replace("\n", "\\n")
        val firstChar = if (content.isNotEmpty()) content[0] else ' '
        val flags = buildString {
            if (content.contains("segments")) append("seg ")
            if (content.contains("```")) append("fence ")
            if (content.contains("{")) append("brace ")
            if (content.contains("<think", ignoreCase = true)) append("think ")
            if (content.contains("reasoning_content", ignoreCase = true)) append("rc ")
        }.trim()
        AppLog.putDebug(
            "aiChapterParse parseFailDiag chapter=$chapterIndex reason=$reason " +
                "len=${content.length} first=$firstChar flags=[$flags] " +
                "head=[$head] tail=[$tail]"
        )
    }

    /**
     * 按章节字符数放大 maxTokens，避免章节 JSON 输出被截断导致解析失败。
     * 估算：章节字符数 * 2 + 结构冗余，限制在 [MIN_PARSE_MAX_TOKENS]..[MAX_PARSE_MAX_TOKENS]。
     */
    private fun AiServiceConfig.withParseMaxTokens(chapterChars: Int): AiServiceConfig {
        val estimated = chapterChars * PARSE_TOKENS_PER_CHAR +
            PARSE_TOKENS_STRUCTURE_OVERHEAD
        val target = estimated.coerceIn(MIN_PARSE_MAX_TOKENS, MAX_PARSE_MAX_TOKENS)
        return if (maxTokens >= target) this else copy(maxTokens = target)
    }

    private fun AiServiceConfig.withRequestTimeout(timeoutSeconds: Int?): AiServiceConfig {
        val timeout = timeoutSeconds ?: return this
        return copy(timeoutSeconds = timeout.coerceIn(5, this.timeoutSeconds))
    }
}

private fun String.logSafe(maxLength: Int = 120): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .take(maxLength)
