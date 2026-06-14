package io.legado.app.model

object ReadAloudRuleSegmenter {

    private const val DEFAULT_EMOTION = "neutral"

    private val quotePairs = mapOf(
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』',
        '"' to '"',
        '＂' to '＂'
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
            segments.addDialog(dialogText, startPosition + openIndex)
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
}
