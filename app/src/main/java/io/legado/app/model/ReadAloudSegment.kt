package io.legado.app.model

data class ReadAloudSegment(
    val text: String,
    val startPosition: Int,
    val plainLength: Int,
    val eventType: String = ReadAloudEventType.TEXT,
    val role: String = ReadAloudRole.NARRATOR,
    val roleName: String = "",
    val emotion: String = "neutral",
    val emotionIntensity: Float = 1.0f
) {
    val endPosition: Int
        get() = startPosition + plainLength
}
