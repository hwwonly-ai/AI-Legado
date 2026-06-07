package io.legado.app.model.tts

data class DoubaoTtsRequest(
    val text: String,
    val voiceType: String,
    val emotion: String?,
    val contextTexts: List<String>,
    val useTagParser: Boolean,
    val rawStartPosition: Int,
    val rawEndPosition: Int,
    val speaker: String,
    val speakerName: String,
    val cacheKeySeed: String
)
