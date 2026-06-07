package io.legado.app.model.tts

import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object DoubaoTtsEngine {
    const val VALUE = "doubaoTts"
    const val TITLE = "豆包语音合成"

    fun selectItemJson(): String {
        return GSON.toJson(SelectItem(TITLE, VALUE))
    }

    fun isSelected(ttsEngine: String?): Boolean {
        if (ttsEngine.isNullOrBlank()) {
            return false
        }
        return GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
            .getOrNull()
            ?.value == VALUE
    }

    fun getSettings(): DoubaoTtsSettings {
        return GSON.fromJsonObject<DoubaoTtsSettings>(AppConfig.doubaoTtsConfig)
            .getOrNull()
            ?: DoubaoTtsSettings()
    }

    fun saveSettings(settings: DoubaoTtsSettings) {
        AppConfig.doubaoTtsConfig = GSON.toJson(settings)
    }
}
