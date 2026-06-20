package io.legado.app.model.ai

import io.legado.app.model.ReadAloudEventType
import io.legado.app.model.ReadAloudRole
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 章节解析返回的单个 segment 中间结构。
 *
 * 对应 AI 输出 JSON 中 segments 数组的一项。字段缺失或不合法时回退默认值，
 * 不在此处抛异常，保证解析链路健壮。
 */
data class AiSegmentResponse(
    val eventType: String,
    val role: String,
    val roleName: String,
    val emotion: String,
    val emotionIntensity: Float,
    val text: String
) {

    /** parse 的细分失败原因，用于诊断日志。 */
    enum class ParseFail {
        EmptyInput,
        NoJsonObject,
        JsonInvalid,
        NoSegmentsField,
        EmptySegmentsArray
    }

    /** parse 结果：成功带列表，失败带原因。 */
    sealed class ParseResult {
        data class Ok(val segments: List<AiSegmentResponse>) : ParseResult()
        data class Fail(val reason: ParseFail) : ParseResult()
    }

    companion object {

        /**
         * 解析 AI 返回的整段 JSON 文本为 segment 列表。
         * 兼容 ```json 代码块和外层说明文字。失败返回细分原因。
         */
        fun parse(rawJson: String): ParseResult {
            if (rawJson.isBlank()) return ParseResult.Fail(ParseFail.EmptyInput)
            val jsonText = extractJsonObject(rawJson)
                ?: return ParseResult.Fail(ParseFail.NoJsonObject)
            val root = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                return ParseResult.Fail(ParseFail.JsonInvalid)
            }
            val segments = root.optJSONArray("segments")
                ?: return ParseResult.Fail(ParseFail.NoSegmentsField)
            if (segments.length() == 0) return ParseResult.Fail(ParseFail.EmptySegmentsArray)
            val parsed = parseSegments(segments)
            return if (parsed.isEmpty()) {
                ParseResult.Fail(ParseFail.EmptySegmentsArray)
            } else {
                ParseResult.Ok(parsed)
            }
        }

        /** 旧入口，保留兼容；内部委托给 [parse]。 */
        fun parseList(rawJson: String): List<AiSegmentResponse>? = when (val r = parse(rawJson)) {
            is ParseResult.Ok -> r.segments
            is ParseResult.Fail -> null
        }

        private fun parseSegments(segments: JSONArray): List<AiSegmentResponse> {
            val result = ArrayList<AiSegmentResponse>(segments.length())
            for (i in 0 until segments.length()) {
                val item = segments.optJSONObject(i) ?: continue
                val text = item.optString("text").orEmpty()
                if (text.isEmpty()) continue
                result.add(
                    AiSegmentResponse(
                        eventType = ReadAloudEventType.TEXT,
                        role = normalizeRole(item.optString("role")),
                        roleName = item.optString("roleName").ifEmpty { defaultRoleName(item.optString("role")) },
                        emotion = item.optString("emotion").ifEmpty { "neutral" },
                        emotionIntensity = normalizeIntensity(item.opt("emotionIntensity")),
                        text = text
                    )
                )
            }
            return result
        }

        private fun normalizeRole(raw: String): String = when (raw) {
            ReadAloudRole.NARRATOR, ReadAloudRole.DIALOG, "unknown" -> raw
            else -> ReadAloudRole.NARRATOR
        }

        private fun defaultRoleName(role: String): String = when (role) {
            ReadAloudRole.NARRATOR -> "旁白"
            "unknown" -> "unknown"
            else -> ""
        }

        private fun normalizeIntensity(raw: Any?): Float {
            val value = when (raw) {
                is Number -> raw.toFloat()
                is String -> raw.toFloatOrNull()
                else -> null
            } ?: return 1.0f
            return value.coerceIn(0.0f, 1.0f)
        }

        /** 从可能包含 markdown 代码块或说明文字的响应中提取首个 JSON 对象文本。 */
        private fun extractJsonObject(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            // 直接尝试整体解析
            if (trimmed.startsWith("{")) return trimmed
            // 提取 ```json ... ``` 或 ``` ... ``` 代码块
            val fenceStart = trimmed.indexOf("```")
            if (fenceStart >= 0) {
                val afterFence = trimmed.substring(fenceStart + 3)
                val newlineIdx = afterFence.indexOf('\n')
                val contentStart = if (newlineIdx >= 0) newlineIdx + 1 else 0
                val fenceEnd = afterFence.indexOf("```", contentStart)
                if (fenceEnd > contentStart) {
                    val candidate = afterFence.substring(contentStart, fenceEnd).trim()
                    if (candidate.startsWith("{")) return candidate
                }
            }
            // 兜底：找到首个 { 到最后一个 }
            val first = trimmed.indexOf('{')
            val last = trimmed.lastIndexOf('}')
            return if (first in 0 until last) trimmed.substring(first, last + 1) else null
        }
    }
}
