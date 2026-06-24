package io.legado.app.model.ai

import io.legado.app.constant.AppLog
import io.legado.app.model.ReadAloudRole
import io.legado.app.model.ReadAloudSegment
import org.json.JSONArray
import org.json.JSONObject

object AiDialogLabeler {

    private const val BEFORE_SEGMENT_COUNT = 3
    private const val AFTER_SEGMENT_COUNT = 2
    private const val BEFORE_CHAR_LIMIT = 500
    private const val AFTER_CHAR_LIMIT = 300
    private const val MAX_DIALOGS_PER_REQUEST = 80
    private const val MIN_LABEL_MAX_TOKENS = 4096
    private const val LABEL_TOKENS_PER_DIALOG = 96

    private val client = OpenAiCompatibleClient()

    suspend fun labelRuleSegments(
        chapterIndex: Int,
        segments: List<ReadAloudSegment>,
        candidates: List<String>
    ): AiChapterParseResult {
        val startNs = System.nanoTime()
        val promptConfig = AiPromptStore.load()
        val config = AiServiceConfig.load()
        if (!config.enabled) {
            return fallback(chapterIndex, segments, config, promptConfig, startNs, "aiDisabled")
        }
        if (config.endpoint.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            return fallback(chapterIndex, segments, config, promptConfig, startNs, "missingConfig")
        }
        if (promptConfig.promptText.isBlank()) {
            return fallback(chapterIndex, segments, config, promptConfig, startNs, "emptyPrompt")
        }

        val dialogIndexes = segments.withIndex()
            .filter { it.value.role == ReadAloudRole.DIALOG }
            .map { it.index }
            .take(MAX_DIALOGS_PER_REQUEST)
        AppLog.putDebug(
            "aiChapterParse labelStart chapter=$chapterIndex dialogs=${dialogIndexes.size} " +
                "segments=${segments.size} candidates=${candidates.size} model=${config.model}"
        )
        if (dialogIndexes.isEmpty()) {
            return AiChapterParseResult(
                segments = segments,
                provider = config.provider.name,
                model = config.model,
                promptHash = promptConfig.contentHash,
                promptVersion = promptConfig.version,
                elapsedMs = (System.nanoTime() - startNs) / 1_000_000,
                fallbackUsed = false
            )
        }

        val messages = listOf(
            AiChatMessage(role = "system", content = promptConfig.promptText),
            AiChatMessage(role = "user", content = buildUserPayload(segments, dialogIndexes, candidates))
        )
        val maxTokens = computeLabelMaxTokens(dialogIndexes.size)
        logLabelConfig(config, maxTokens, "primary")
        var chatResult = client.chatCompletion(config, messages, maxTokens)
        if (chatResult is AiChatResult.Failure &&
            shouldRetryWithoutThinking(chatResult) &&
            (config.thinkingEnabled || !config.reasoningEffort.isNullOrEmpty())
        ) {
            val retryConfig = config.copy(
                thinkingEnabled = false,
                reasoningEffort = null
            )
            AppLog.putDebug(
                "aiChapterParse labelRetry reason=thinkingRejected code=${chatResult.code} " +
                    "thinking=false reasoningEffort=null"
            )
            logLabelConfig(retryConfig, maxTokens, "retry")
            chatResult = client.chatCompletion(retryConfig, messages, maxTokens)
        }
        when (chatResult) {
            is AiChatResult.Failure -> {
                return fallback(
                    chapterIndex = chapterIndex,
                    segments = segments,
                    config = config,
                    promptConfig = promptConfig,
                    startNs = startNs,
                    reason = "requestFailed",
                    detail = "type=${chatResult.type} code=${chatResult.code} " +
                        "message=${chatResult.message.logSafe()}"
                )
            }
            is AiChatResult.Success -> Unit
        }

        val rawContent = (chatResult as AiChatResult.Success).content
        val parseResult = AiDialogLabel.parse(rawContent)
        val labels = when (parseResult) {
            is AiDialogLabel.ParseResult.Ok -> parseResult.labels
            is AiDialogLabel.ParseResult.Fail -> {
                return fallback(
                    chapterIndex = chapterIndex,
                    segments = segments,
                    config = config,
                    promptConfig = promptConfig,
                    startNs = startNs,
                    reason = "labelParseFailed",
                    detail = "parseFail=${parseResult.reason} rawLength=${rawContent.length}"
                )
            }
        }

        val dialogIndexSet = dialogIndexes.toSet()
        val labelMap = labels
            .filter { it.id in dialogIndexSet }
            .associateBy { it.id }
        val labeledSegments = segments.mapIndexed { index, segment ->
            val label = labelMap[index]
            if (label == null || segment.role != ReadAloudRole.DIALOG) {
                segment
            } else if (label.role == ReadAloudRole.NARRATOR) {
                segment.copy(
                    role = ReadAloudRole.NARRATOR,
                    roleName = "旁白",
                    roleAttribute = label.roleAttribute,
                    emotion = "neutral",
                    emotionIntensity = 1.0f
                )
            } else {
                segment.copy(
                    roleName = label.roleName.ifBlank { "对白" },
                    roleAttribute = label.roleAttribute,
                    emotion = label.emotion.ifBlank { "neutral" },
                    emotionIntensity = label.emotionIntensity
                )
            }
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        AppLog.putDebug(
            "aiChapterParse labelResult chapter=$chapterIndex labeled=${labelMap.size} " +
                "total=${dialogIndexes.size} fallback=false elapsedMs=$elapsedMs"
        )
        return AiChapterParseResult(
            segments = labeledSegments,
            provider = config.provider.name,
            model = config.model,
            promptHash = promptConfig.contentHash,
            promptVersion = promptConfig.version,
            elapsedMs = elapsedMs,
            fallbackUsed = false
        )
    }

    private fun shouldRetryWithoutThinking(failure: AiChatResult.Failure): Boolean {
        if (failure.code == 422) return true
        return failure.code == 400 && failure.message.contains("thinking", ignoreCase = true)
    }

    private fun logLabelConfig(config: AiServiceConfig, maxTokens: Int, attempt: String) {
        AppLog.putDebug(
            "aiChapterParse labelConfig attempt=$attempt model=${config.model} " +
                "mode=${config.mode} thinking=${config.thinkingEnabled} " +
                "reasoningEffort=${config.reasoningEffort} maxTokens=$maxTokens"
        )
    }

    private fun fallback(
        chapterIndex: Int,
        segments: List<ReadAloudSegment>,
        config: AiServiceConfig,
        promptConfig: AiPromptConfig,
        startNs: Long,
        reason: String,
        detail: String? = null
    ): AiChapterParseResult {
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val detailPart = if (detail != null) " $detail" else ""
        val dialogs = segments.count { it.role == ReadAloudRole.DIALOG }
        AppLog.putDebug(
            "aiChapterParse labelResult chapter=$chapterIndex labeled=0 total=$dialogs " +
                "fallback=true reason=$reason elapsedMs=$elapsedMs$detailPart"
        )
        return AiChapterParseResult(
            segments = segments,
            provider = config.provider.name,
            model = config.model,
            promptHash = promptConfig.contentHash,
            promptVersion = promptConfig.version,
            elapsedMs = elapsedMs,
            fallbackUsed = true,
            errorMessage = reason
        )
    }

    private fun buildUserPayload(
        segments: List<ReadAloudSegment>,
        dialogIndexes: List<Int>,
        candidates: List<String>
    ): String {
        val root = JSONObject()
        val candidatesJson = JSONArray()
        candidates.take(40).forEach { candidatesJson.put(it) }
        root.put("candidates", candidatesJson)
        val dialogsJson = JSONArray()
        dialogIndexes.forEachIndexed { dialogOrder, index ->
            val item = JSONObject()
            item.put("id", index)
            item.put("dialogOrder", dialogOrder)
            item.put("text", segments[index].text)
            item.put("beforeSegments", contextSegments(segments, index, before = true))
            item.put("afterSegments", contextSegments(segments, index, before = false))
            dialogsJson.put(item)
        }
        root.put("dialogs", dialogsJson)
        return root.toString()
    }

    private fun contextSegments(
        segments: List<ReadAloudSegment>,
        index: Int,
        before: Boolean
    ): JSONArray {
        val result = JSONArray()
        var chars = 0
        val range = if (before) {
            ((index - BEFORE_SEGMENT_COUNT) until index).toList().asReversed()
        } else {
            ((index + 1)..(index + AFTER_SEGMENT_COUNT)).toList()
        }
        val charLimit = if (before) BEFORE_CHAR_LIMIT else AFTER_CHAR_LIMIT
        val collected = ArrayList<JSONObject>()
        for (i in range) {
            val segment = segments.getOrNull(i) ?: continue
            val text = segment.text.take(if (before) BEFORE_CHAR_LIMIT else AFTER_CHAR_LIMIT)
            if (text.isBlank()) continue
            chars += text.length
            if (chars > charLimit && collected.isNotEmpty()) break
            collected.add(
                JSONObject()
                    .put("id", i)
                    .put("role", segment.role)
                    .put("roleName", segment.roleName)
                    .put("text", text)
            )
        }
        val ordered = if (before) collected.asReversed() else collected
        ordered.forEach { result.put(it) }
        return result
    }

    private fun computeLabelMaxTokens(dialogCount: Int): Int {
        return (MIN_LABEL_MAX_TOKENS + dialogCount * LABEL_TOKENS_PER_DIALOG)
            .coerceAtMost(16000)
    }

    private fun String.logSafe(limit: Int = 160): String {
        val oneLine = replace(Regex("\\s+"), " ").trim()
        return oneLine.take(limit) + if (oneLine.length > limit) "..." else ""
    }
}
