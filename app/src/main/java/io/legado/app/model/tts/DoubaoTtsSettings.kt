package io.legado.app.model.tts

data class DoubaoTtsSettings(
    val appId: String = "",
    val accessToken: String = "",
    val narratorVoiceType: String = "BV700_V2_streaming",
    val dialogVoiceType: String = "BV001_V2_streaming",
    val emotion: String = "",
    val instruction: String = "",
    val enableVoiceTags: Boolean = false,
    val contextWindowSize: Int = 2,
    val cluster: String = "volcano_tts",
    val speedRatio: Double = 1.0,
    val volumeRatio: Double = 1.0,
    val pitchRatio: Double = 1.0
) {
    fun toApiConfig(): DoubaoApiConfig {
        return DoubaoApiConfig(
            appId = appId,
            accessToken = accessToken,
            cluster = cluster,
            speedRatio = speedRatio,
            volumeRatio = volumeRatio,
            pitchRatio = pitchRatio
        )
    }

    fun toVoiceConfig(): DoubaoVoiceConfig {
        return DoubaoVoiceConfig(
            narratorVoiceType = narratorVoiceType,
            dialogVoiceType = dialogVoiceType,
            enableVoiceTags = enableVoiceTags,
            instruction = instruction,
            contextWindowSize = contextWindowSize
        )
    }

    fun isConfigured(): Boolean {
        return appId.isNotBlank() && accessToken.isNotBlank()
    }
}
