package io.legado.app.ui.book.read.config

import android.content.Context
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS

fun HttpTTS.isAliyunTTS(): Boolean {
    return name.contains("阿里云", ignoreCase = true)
            || url.contains("nls-gateway", ignoreCase = true)
            || url.contains("aliyuncs.com", ignoreCase = true)
}

fun HttpTTS.isDoubaoTTS(): Boolean {
    return name.contains("豆包", ignoreCase = true)
            || url.contains("openspeech.bytedance.com", ignoreCase = true)
            || url.contains("volcengine", ignoreCase = true)
}

fun HttpTTS.providerName(): String {
    return when {
        isAliyunTTS() -> "Aliyun"
        isDoubaoTTS() -> "Doubao"
        else -> "HTTP_TTS"
    }
}

fun HttpTTS.isSupportedRoleVoice(): Boolean {
    return isAliyunTTS() || isDoubaoTTS()
}

fun HttpTTS.voiceHint(context: Context, isNarrator: Boolean): String {
    val resId = if (isDoubaoTTS()) {
        if (isNarrator) R.string.book_voice_narrator_hint_doubao
        else R.string.book_voice_dialog_hint_doubao
    } else {
        if (isNarrator) R.string.book_voice_narrator_hint_aliyun
        else R.string.book_voice_dialog_hint_aliyun
    }
    return context.getString(resId)
}
