package io.legado.app.model

import io.legado.app.model.tts.AliyunSsmlRenderer

object ReadAloudRuleSegmenter {

    const val DEFAULT_NARRATOR_VOICE = "zhifeng_emo"
    const val DEFAULT_DIALOG_VOICE = "zhixiaoxia"
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
        startPosition: Int,
        narratorVoice: String = DEFAULT_NARRATOR_VOICE,
        dialogVoice: String = DEFAULT_DIALOG_VOICE,
        renderAliyunSsml: Boolean = true
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
                    startPosition + cursor,
                    narratorVoice,
                    renderAliyunSsml
                )
                break
            }
            if (openIndex > cursor) {
                segments.addNarrator(
                    text.substring(cursor, openIndex),
                    startPosition + cursor,
                    narratorVoice,
                    renderAliyunSsml
                )
            }
            val closeQuote = quotePairs[text[openIndex]] ?: return listOf(
                narratorSegment(text, startPosition, narratorVoice, renderAliyunSsml)
            )
            val closeIndex = text.indexOf(closeQuote, openIndex + 1)
            if (closeIndex < 0 || hasNestedOpeningQuote(text, openIndex + 1, closeIndex, text[openIndex])) {
                return listOf(narratorSegment(text, startPosition, narratorVoice, renderAliyunSsml))
            }
            val dialogText = text.substring(openIndex, closeIndex + 1)
            segments.addDialog(dialogText, startPosition + openIndex, dialogVoice, renderAliyunSsml)
            cursor = closeIndex + 1
        }
        return segments.filter { it.plainLength > 0 }
            .ifEmpty { listOf(narratorSegment(text, startPosition, narratorVoice, renderAliyunSsml)) }
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
        startPosition: Int,
        voice: String,
        renderAliyunSsml: Boolean
    ) {
        if (text.isNotBlank()) {
            add(narratorSegment(text, startPosition, voice, renderAliyunSsml))
        }
    }

    private fun MutableList<ReadAloudSegment>.addDialog(
        text: String,
        startPosition: Int,
        voice: String,
        renderAliyunSsml: Boolean
    ) {
        if (text.isNotBlank()) {
            add(createSegment(text, startPosition, ReadAloudRole.DIALOG, voice, renderAliyunSsml))
        }
    }

    private fun narratorSegment(
        text: String,
        startPosition: Int,
        voice: String,
        renderAliyunSsml: Boolean
    ): ReadAloudSegment {
        return createSegment(text, startPosition, ReadAloudRole.NARRATOR, voice, renderAliyunSsml)
    }

    private fun createSegment(
        text: String,
        startPosition: Int,
        speaker: String,
        voice: String,
        renderAliyunSsml: Boolean
    ): ReadAloudSegment {
        val segment = ReadAloudSegment(
            text = text,
            ssml = "",
            startPosition = startPosition,
            plainLength = text.length,
            speaker = speaker,
            speakerName = if (speaker == ReadAloudRole.NARRATOR) "旁白" else "对白",
            emotion = DEFAULT_EMOTION,
            voice = voice
        )
        return if (renderAliyunSsml) {
            segment.copy(ssml = AliyunSsmlRenderer.render(segment))
        } else {
            segment
        }
    }
}
