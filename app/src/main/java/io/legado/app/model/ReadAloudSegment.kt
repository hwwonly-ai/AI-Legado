package io.legado.app.model

data class ReadAloudSegment(
    val text: String,
    val ssml: String,
    val startPosition: Int,
    val plainLength: Int,
    val speaker: String = "",
    val speakerName: String = "",
    val emotion: String = "",
    val voice: String = "",
    val styleTag: String = ""
) {
    val endPosition: Int
        get() = startPosition + plainLength
}
