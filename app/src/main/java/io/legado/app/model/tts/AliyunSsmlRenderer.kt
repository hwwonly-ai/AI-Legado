package io.legado.app.model.tts

import io.legado.app.model.ReadAloudSegment

object AliyunSsmlRenderer : ReadAloudSegmentRenderer<String> {

    override fun render(segment: ReadAloudSegment): String {
        val escapedText = escapeSSMLText(segment.text)
        val emotion = segment.emotion.ifBlank { "neutral" }
        val voiceContent = if (segment.voice.endsWith("_emo")) {
            "<emotion category=\"$emotion\" intensity=\"1.0\">$escapedText</emotion>"
        } else {
            escapedText
        }
        return "<speak><voice name=\"${segment.voice}\">$voiceContent</voice></speak>"
    }

    private fun escapeSSMLText(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
