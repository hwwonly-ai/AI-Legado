package io.legado.app.model

object ReadAloudRuleSegmenter {

    private const val NARRATOR = "narrator"
    private const val DIALOG = "dialog"
    private const val NARRATOR_VOICE = "zhifeng_emo"
    private const val DIALOG_VOICE = "zhixiaoxia"
    private const val DEFAULT_EMOTION = "neutral"

    private val quotePairs = mapOf(
        '“' to '”',
        '‘' to '’',
        '「' to '」',
        '『' to '』'
    )

    fun segment(
        text: String,
        startPosition: Int,
        narratorVoice: String = NARRATOR_VOICE,
        dialogVoice: String = DIALOG_VOICE
    ): List<ReadAloudSegment> {
        if (text.isBlank()) {
            return emptyList()
        }
        val segments = mutableListOf<ReadAloudSegment>()
        var cursor = 0
        while (cursor < text.length) {
            val openIndex = text.indexOfFirstQuote(cursor)
            if (openIndex < 0) {
                segments.addNarrator(text.substring(cursor), startPosition + cursor, narratorVoice)
                break
            }
            if (openIndex > cursor) {
                segments.addNarrator(text.substring(cursor, openIndex), startPosition + cursor, narratorVoice)
            }
            val closeQuote = quotePairs[text[openIndex]] ?: return listOf(
                narratorSegment(text, startPosition, narratorVoice)
            )
            val closeIndex = text.indexOf(closeQuote, openIndex + 1)
            if (closeIndex < 0 || hasNestedOpeningQuote(text, openIndex + 1, closeIndex)) {
                return listOf(narratorSegment(text, startPosition, narratorVoice))
            }
            val dialogText = text.substring(openIndex, closeIndex + 1)
            segments.addDialog(dialogText, startPosition + openIndex, dialogVoice)
            cursor = closeIndex + 1
        }
        return segments.filter { it.plainLength > 0 }
            .ifEmpty { listOf(narratorSegment(text, startPosition, narratorVoice)) }
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

    private fun hasNestedOpeningQuote(text: String, startIndex: Int, endIndex: Int): Boolean {
        for (index in startIndex until endIndex) {
            if (text[index] in quotePairs.keys) {
                return true
            }
        }
        return false
    }

    private fun MutableList<ReadAloudSegment>.addNarrator(
        text: String,
        startPosition: Int,
        voice: String
    ) {
        if (text.isNotBlank()) {
            add(narratorSegment(text, startPosition, voice))
        }
    }

    private fun MutableList<ReadAloudSegment>.addDialog(
        text: String,
        startPosition: Int,
        voice: String
    ) {
        if (text.isNotBlank()) {
            add(createSegment(text, startPosition, DIALOG, voice))
        }
    }

    private fun narratorSegment(text: String, startPosition: Int, voice: String): ReadAloudSegment {
        return createSegment(text, startPosition, NARRATOR, voice)
    }

    private fun createSegment(
        text: String,
        startPosition: Int,
        speaker: String,
        voice: String
    ): ReadAloudSegment {
        return ReadAloudSegment(
            text = text,
            ssml = toAliyunSSML(text, voice),
            startPosition = startPosition,
            plainLength = text.length,
            speaker = speaker,
            emotion = DEFAULT_EMOTION,
            voice = voice
        )
    }

    private fun toAliyunSSML(text: String, voice: String): String {
        val escapedText = escapeSSMLText(text)
        val voiceContent = if (voice.endsWith("_emo")) {
            "<emotion category=\"$DEFAULT_EMOTION\" intensity=\"1.0\">$escapedText</emotion>"
        } else {
            escapedText
        }
        return "<speak><voice name=\"$voice\">$voiceContent</voice></speak>"
    }

    private fun escapeSSMLText(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
