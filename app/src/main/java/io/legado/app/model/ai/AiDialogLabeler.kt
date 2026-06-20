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
        candidates: List<String>,
        requestTimeoutSeconds: Int? = null
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
            AiChatMessage(role = "system", content = buildSystemPrompt(promptConfig.promptText)),
            AiChatMessage(role = "user", content = buildUserPayload(segments, dialogIndexes, candidates))
        )
        val labelConfig = config
            .copy(
                thinkingEnabled = false,
                reasoningEffort = null
            )
            .withLabelMaxTokens(dialogIndexes.size)
            .withRequestTimeout(requestTimeoutSeconds)
        AppLog.putDebug(
            "aiChapterParse labelConfig model=${labelConfig.model} mode=${labelConfig.mode} " +
                "thinking=${labelConfig.thinkingEnabled} reasoningEffort=${labelConfig.reasoningEffort} " +
                "maxTokens=${labelConfig.maxTokens}"
        )
        val chatResult = client.chatCompletion(labelConfig, messages)
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
            } else {
                segment.copy(
                    roleName = label.roleName.ifBlank { "对白" },
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

    private fun buildSystemPrompt(userPrompt: String): String {
        return """
            ${userPrompt.trim()}

            最高优先级任务：你现在只负责给已经由规则切出的小说对白标注说话人和情绪。
            禁止改写、补写或返回原文正文。只输出 JSON，不输出解释。

            输入包含：
            - candidates：候选角色名。
            - dialogs：需要标注的对白，每条有 id、text、beforeSegments、afterSegments。

            判断规则：
            1. 根据当前对白和前后上下文判断说话人。
            2. 前文若出现“甲说道/问道/笑道/拱手问道：”，通常当前对白为甲。
            3. 前文若出现“甲问/看向/询问乙”，且当前对白像回答，优先判定为乙。
            4. 后文出现“某人说道/问道”通常用于引出下一句，不要反推当前对白，除非语义明确。
            5. 无法确定说话人时，roleName 返回 "对白"，不要编造人物。
            6. emotion 只使用 neutral、happy、sad、angry、fear、surprise；不确定用 neutral。
            7. emotionIntensity 范围 0.0 到 1.0，不确定用 1.0。

            输出格式：
            {
              "labels": [
                {"id": 1, "roleName": "黑夫", "emotion": "neutral", "emotionIntensity": 1.0}
              ]
            }
        """.trimIndent()
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

    private fun AiServiceConfig.withLabelMaxTokens(dialogCount: Int): AiServiceConfig {
        val target = (MIN_LABEL_MAX_TOKENS + dialogCount * LABEL_TOKENS_PER_DIALOG)
            .coerceAtMost(16000)
        return if (maxTokens >= target) this else copy(maxTokens = target)
    }

    private fun AiServiceConfig.withRequestTimeout(timeoutSeconds: Int?): AiServiceConfig {
        val timeout = timeoutSeconds ?: return this
        return copy(timeoutSeconds = timeout.coerceIn(5, this.timeoutSeconds))
    }

    private fun String.logSafe(limit: Int = 160): String {
        val oneLine = replace(Regex("\\s+"), " ").trim()
        return oneLine.take(limit) + if (oneLine.length > limit) "..." else ""
    }
}
