package io.legado.app.model.tts

import io.legado.app.data.entities.HttpTTS

object AliyunTtsTokenHelper {

    private const val TOKEN_HEADER = "X-NLS-Token"
    private const val EXPIRE_TIME_KEY = "__aliyunExpireTime"
    private const val REFRESH_WINDOW_MS = 2 * 60 * 60 * 1000L

    fun isAliyunTTS(httpTts: HttpTTS): Boolean {
        return httpTts.name.contains("阿里云", ignoreCase = true)
                || httpTts.url.contains("nls-gateway", ignoreCase = true)
                || httpTts.url.contains("aliyuncs.com", ignoreCase = true)
    }

    fun refreshReasonBeforeRequest(
        httpTts: HttpTTS,
        now: Long = System.currentTimeMillis()
    ): String? {
        if (!isAliyunTTS(httpTts)) return null
        val headerMap = httpTts.getLoginHeaderMap()
        val token = headerMap?.get(TOKEN_HEADER).orEmpty()
        if (token.isBlank()) return "missingHeader"
        val expireAt = headerMap?.get(EXPIRE_TIME_KEY)
            ?.toLongOrNull()
            ?.let(::normalizeExpireTimeMillis)
            ?: return null
        return if (expireAt - now <= REFRESH_WINDOW_MS) "expiringSoon" else null
    }

    fun isInvalidTokenResponse(
        httpTts: HttpTTS,
        code: Int,
        bodyText: String
    ): Boolean {
        if (!isAliyunTTS(httpTts)) return false
        if (code !in listOf(400, 401, 403)) return false
        return containsInvalidTokenSignal(bodyText)
    }

    fun containsInvalidTokenSignal(bodyText: String): Boolean {
        return bodyText.contains("40000001")
                || bodyText.contains("ACCESS_DENIED", ignoreCase = true)
                || bodyText.contains("token", ignoreCase = true)
                && bodyText.contains("invalid", ignoreCase = true)
    }

    fun redactSensitive(text: String): String {
        return text
            .replace(
                Regex("""(?i)(X-NLS-Token["']?\s*[:=]\s*["']?)[^"',}\s]+"""),
                "\$1***"
            )
            .replace(
                Regex("""(?i)(AccessKeySecret["']?\s*[:=]\s*["']?)[^"',}\s]+"""),
                "\$1***"
            )
            .replace(
                Regex("""(?i)(Authorization["']?\s*[:=]\s*["']?)[^"',}\s]+"""),
                "\$1***"
            )
            .replace(
                Regex("""(?i)(token\s+'?)[A-Za-z0-9._-]{8,}('?[\s.,;:]+is\s+invalid)"""),
                "\$1***\$2"
            )
            .replace(
                Regex("""(?i)("Id"\s*:\s*")[^"]+(")"""),
                "\$1***\$2"
            )
    }

    private fun normalizeExpireTimeMillis(expireTime: Long): Long {
        return if (expireTime < 100_000_000_000L) expireTime * 1000L else expireTime
    }
}
