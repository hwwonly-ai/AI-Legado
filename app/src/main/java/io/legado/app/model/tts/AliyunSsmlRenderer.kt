package io.legado.app.model.tts

import io.legado.app.model.ReadAloudSegment
import io.legado.app.model.ReadAloudProviderSegment

object AliyunSsmlRenderer : ReadAloudSegmentRenderer<String> {

    override fun render(segment: ReadAloudSegment, providerSegment: ReadAloudProviderSegment): String {
        val escapedText = escapeSSMLText(segment.text)
        val emotion = segment.emotion.ifBlank { "neutral" }
        val intensity = segment.emotionIntensity.coerceIn(0f, 2f).toString()
        val voiceContent = if (providerSegment.voice.endsWith("_emo")) {
            "<emotion category=\"$emotion\" intensity=\"$intensity\">$escapedText</emotion>"
        } else {
            escapedText
        }
        return "<speak><voice name=\"${providerSegment.voice}\">$voiceContent</voice></speak>"
    }

    private fun escapeSSMLText(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
