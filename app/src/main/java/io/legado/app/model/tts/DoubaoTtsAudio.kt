package io.legado.app.model.tts

data class DoubaoTtsAudio(
    val reqId: String,
    val bytes: ByteArray,
    val encoding: String,
    val rawResponse: String
) {
    val contentType: String
        get() = when (encoding.lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg_opus" -> "audio/ogg"
            "pcm" -> "audio/pcm"
            else -> "application/octet-stream"
        }
}
