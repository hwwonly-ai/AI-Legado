package io.legado.app.model.ai

import io.legado.app.model.ReadAloudRole
import io.legado.app.data.entities.BookReadAloudRole
import org.json.JSONObject

data class AiDialogLabel(
    val id: Int,
    val role: String,
    val roleName: String,
    val roleAttribute: String,
    val emotion: String,
    val emotionIntensity: Float
) {

    enum class ParseFail {
        EmptyInput,
        NoJsonObject,
        JsonInvalid,
        NoLabelsField,
        EmptyLabelsArray
    }

    sealed class ParseResult {
        data class Ok(val labels: List<AiDialogLabel>) : ParseResult()
        data class Fail(val reason: ParseFail) : ParseResult()
    }

    companion object {

        fun parse(rawJson: String): ParseResult {
            if (rawJson.isBlank()) return ParseResult.Fail(ParseFail.EmptyInput)
            val jsonText = extractJsonObject(rawJson)
                ?: return ParseResult.Fail(ParseFail.NoJsonObject)
            val root = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                return ParseResult.Fail(ParseFail.JsonInvalid)
            }
            val labels = root.optJSONArray("labels")
                ?: return ParseResult.Fail(ParseFail.NoLabelsField)
            if (labels.length() == 0) return ParseResult.Fail(ParseFail.EmptyLabelsArray)
            val parsed = ArrayList<AiDialogLabel>(labels.length())
            for (i in 0 until labels.length()) {
                val item = labels.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                if (id < 0) continue
                parsed.add(
                    AiDialogLabel(
                        id = id,
                        role = normalizeRole(item.optString("role")),
                        roleName = item.optString("roleName").ifBlank { "对白" },
                        roleAttribute = normalizeRoleAttribute(item.optString("roleAttribute"), item.optString("roleName")),
                        emotion = item.optString("emotion").ifBlank { "neutral" },
                        emotionIntensity = normalizeIntensity(item.opt("emotionIntensity"))
                    )
                )
            }
            return if (parsed.isEmpty()) {
                ParseResult.Fail(ParseFail.EmptyLabelsArray)
            } else {
                ParseResult.Ok(parsed)
            }
        }

        private fun normalizeRole(raw: String): String = when (raw) {
            ReadAloudRole.NARRATOR -> ReadAloudRole.NARRATOR
            ReadAloudRole.DIALOG, "" -> ReadAloudRole.DIALOG
            else -> ReadAloudRole.DIALOG
        }

        private fun normalizeRoleAttribute(raw: String, roleName: String): String {
            if (raw in BookReadAloudRole.RoleAttribute.ALL) return raw
            android.util.Log.i(
                "roleMatch",
                "autoSkip roleName=${roleName.ifBlank { "对白" }} reason=invalidRoleAttribute attr=${raw.ifBlank { "-" }}"
            )
            return BookReadAloudRole.RoleAttribute.MIDDLE_MALE
        }

        private fun normalizeIntensity(raw: Any?): Float {
            val value = when (raw) {
                is Number -> raw.toFloat()
                is String -> raw.toFloatOrNull()
                else -> null
            } ?: return 1.0f
            return value.coerceIn(0.0f, 1.0f)
        }

        private fun extractJsonObject(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) return trimmed
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
            val first = trimmed.indexOf('{')
            val last = trimmed.lastIndexOf('}')
            return if (first >= 0 && last > first) trimmed.substring(first, last + 1) else null
        }
    }
}
