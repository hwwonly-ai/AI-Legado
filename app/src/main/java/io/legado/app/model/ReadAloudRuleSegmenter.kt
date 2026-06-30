package io.legado.app.model

import io.legado.app.constant.AppLog

object ReadAloudRuleSegmenter {

    private const val DEFAULT_EMOTION = "neutral"
    private const val TERM_MAX_LENGTH = 8
    private const val CONTEXT_WINDOW = 12

    private val quotePairs = mapOf(
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』',
        '"' to '"',
        '＂' to '＂'
    )

    private val termContextHints = listOf(
        "所谓",
        "叫作",
        "叫做",
        "称为",
        "称作",
        "名为",
        "爵位",
        "官职",
        "秦律",
        "罪名",
        "案由",
        "封号",
        "称号",
        "规定",
        "这便是",
        "便是",
        "律",
        "罪",
        "法"
    )

    private val speechContextHints = listOf(
        "说道",
        "问道",
        "喊道",
        "答道",
        "反驳",
        "诘问",
        "骂道",
        "笑道",
        "道：",
        "曰"
    )

    fun segment(
        text: String,
        startPosition: Int
    ): List<ReadAloudSegment> {
        if (text.isBlank()) {
            return emptyList()
        }
        val segments = mutableListOf<ReadAloudSegment>()
        var cursor = 0
        while (cursor < text.length) {
            val openIndex = text.indexOfFirstQuote(cursor)
            if (openIndex < 0) {
                segments.addNarrator(
                    text.substring(cursor),
                    startPosition + cursor
                )
                break
            }
            if (openIndex > cursor) {
                segments.addNarrator(
                    text.substring(cursor, openIndex),
                    startPosition + cursor
                )
            }
            val closeQuote = quotePairs[text[openIndex]] ?: return listOf(
                narratorSegment(text, startPosition)
            )
            val closeIndex = text.indexOf(closeQuote, openIndex + 1)
            if (closeIndex < 0 || hasNestedOpeningQuote(text, openIndex + 1, closeIndex, text[openIndex])) {
                return listOf(narratorSegment(text, startPosition))
            }
            val dialogText = text.substring(openIndex, closeIndex + 1)
            val classification = classifyQuote(text, openIndex, closeIndex)
            if (classification.role == ReadAloudRole.NARRATOR) {
                segments.addNarrator(dialogText, startPosition + openIndex)
            } else {
                segments.addDialog(dialogText, startPosition + openIndex)
            }
            if (classification.reason != null) {
                AppLog.putDebug(
                    "aiChapterParse quoteClassify role=${classification.role} " +
                        "reason=${classification.reason} start=${startPosition + openIndex} " +
                        "len=${dialogText.length} text=${dialogText.logPreview()}"
                )
            }
            cursor = closeIndex + 1
        }
        return segments.filter { it.plainLength > 0 }
            .ifEmpty { listOf(narratorSegment(text, startPosition)) }
    }

    private fun String.indexOfFirstQuote(startIndex: Int): Int {
        var index = startIndex
        while (index < length) {
            if (this[index] in quotePairs.keys) {
                return index
            }
            index++
        }
        return -1
    }

    private fun hasNestedOpeningQuote(
        text: String,
        startIndex: Int,
        endIndex: Int,
        openQuote: Char
    ): Boolean {
        if (quotePairs[openQuote] == openQuote) {
            return false
        }
        for (index in startIndex until endIndex) {
            if (text[index] in quotePairs.keys) {
                return true
            }
        }
        return false
    }

    private fun classifyQuote(
        text: String,
        openIndex: Int,
        closeIndex: Int
    ): QuoteClassification {
        val before = text.substring((openIndex - CONTEXT_WINDOW).coerceAtLeast(0), openIndex)
        val after = text.substring(closeIndex + 1, (closeIndex + 1 + CONTEXT_WINDOW).coerceAtMost(text.length))
        val context = before + after
        if (context.containsAny(speechContextHints)) {
            return QuoteClassification(ReadAloudRole.DIALOG, "speechContext")
        }
        val inner = text.substring(openIndex + 1, closeIndex)
        if (!inner.isStrictTermText()) {
            return QuoteClassification(ReadAloudRole.DIALOG, null)
        }
        return if (context.containsAny(termContextHints)) {
            QuoteClassification(ReadAloudRole.NARRATOR, "termContext")
        } else {
            QuoteClassification(ReadAloudRole.DIALOG, null)
        }
    }

    private fun String.isStrictTermText(): Boolean {
        if (length !in 1..TERM_MAX_LENGTH) {
            return false
        }
        return all { it.isLetterOrDigit() }
    }

    private fun String.containsAny(values: List<String>): Boolean {
        return values.any { contains(it) }
    }

    private fun String.logPreview(): String {
        return if (length <= 24) this else take(24) + "..."
    }

    private fun MutableList<ReadAloudSegment>.addNarrator(
        text: String,
        startPosition: Int
    ) {
        if (text.isNotBlank()) {
            add(narratorSegment(text, startPosition))
        }
    }

    private fun MutableList<ReadAloudSegment>.addDialog(
        text: String,
        startPosition: Int
    ) {
        if (text.isNotBlank()) {
            add(createSegment(text, startPosition, ReadAloudRole.DIALOG, "对白"))
        }
    }

    private fun narratorSegment(
        text: String,
        startPosition: Int
    ): ReadAloudSegment {
        return createSegment(text, startPosition, ReadAloudRole.NARRATOR, "旁白")
    }

    private fun createSegment(
        text: String,
        startPosition: Int,
        role: String,
        roleName: String
    ): ReadAloudSegment {
        return ReadAloudSegment(
            text = text,
            startPosition = startPosition,
            plainLength = text.length,
            eventType = ReadAloudEventType.TEXT,
            role = role,
            roleName = roleName,
            emotion = DEFAULT_EMOTION,
            emotionIntensity = 1.0f
        )
    }

    private data class QuoteClassification(
        val role: String,
        val reason: String?
    )
}
