package io.legado.app.model

data class ReadAloudProviderSegment(
    val provider: String,
    val voice: String,
    val styleTag: String = "",
    val extra: Map<String, String> = emptyMap()
)
