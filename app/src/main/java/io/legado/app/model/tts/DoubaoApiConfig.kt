package io.legado.app.model.tts

data class DoubaoApiConfig(
    val appId: String,
    val accessToken: String,
    val cluster: String = "volcano_tts",
    val uid: String = "legado_doubao_tts",
    val endpoint: String = "https://openspeech.bytedance.com/api/v1/tts",
    val encoding: String = "mp3",
    val rate: Int = 24000,
    val speedRatio: Double = 1.0,
    val volumeRatio: Double = 1.0,
    val pitchRatio: Double = 1.0,
    val explicitLanguage: String = "zh",
    val disableMarkdownFilter: Boolean = true,
    val disableEmojiFilter: Boolean = true
) {
    fun authorizationHeader(): String {
        return if (accessToken.startsWith("Bearer", ignoreCase = true)) {
            accessToken
        } else {
            "Bearer; $accessToken"
        }
    }
}
