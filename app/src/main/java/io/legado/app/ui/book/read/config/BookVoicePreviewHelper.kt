package io.legado.app.ui.book.read.config

import android.content.Context
import android.util.Base64
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.getProxyClient
import io.legado.app.help.http.newCallResponse
import io.legado.app.model.ReadAloudProviderSegment
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.tts.AliyunTtsTokenHelper
import io.legado.app.utils.jsonPath
import kotlinx.coroutines.Dispatchers.IO
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

object BookVoicePreviewHelper {

    suspend fun buildPreviewAudio(
        context: Context,
        httpTts: HttpTTS,
        preset: BookVoicePreset,
        previewText: String
    ): File {
        val providerSegment = ReadAloudProviderSegment(
            provider = httpTts.providerName(),
            voice = preset.requestValue
        )
        val file = File(context.cacheDir, "book_voice_preview.mp3")
        val speakText = previewSpeakText(preset, previewText)
        var tokenRefreshRetried = false
        while (true) {
            refreshAliyunTokenBeforeRequest(httpTts)
            val analyzeUrl = AnalyzeUrl(
                httpTts.url,
                speakText = speakText,
                speakSpeed = AppConfig.speechRatePlay + 5,
                readAloudProviderSegment = providerSegment,
                source = httpTts,
                readTimeout = 300 * 1000L,
                coroutineContext = IO
            )
            val response = analyzeUrl.getResponseAwait()
            try {
                if (!response.isSuccessful) {
                    val errorBody = runCatching { response.body.string() }
                        .getOrElse { "Unable to read error body: ${it.message}" }
                    if (!tokenRefreshRetried
                        && AliyunTtsTokenHelper.isInvalidTokenResponse(httpTts, response.code, errorBody)
                    ) {
                        tokenRefreshRetried = true
                        refreshAliyunToken(httpTts, clearLoginHeader = true)
                        continue
                    }
                    throw IllegalStateException(
                        "HTTP ${response.code}: ${AliyunTtsTokenHelper.redactSensitive(errorBody)}"
                    )
                }
                val contentType = response.headers["Content-Type"].orEmpty().substringBefore(";")
                if (contentType == "application/json" || contentType.startsWith("text/")) {
                    val bodyText = response.body.string()
                    if (!tokenRefreshRetried
                        && AliyunTtsTokenHelper.isAliyunTTS(httpTts)
                        && AliyunTtsTokenHelper.containsInvalidTokenSignal(bodyText)
                    ) {
                        tokenRefreshRetried = true
                        refreshAliyunToken(httpTts, clearLoginHeader = true)
                        continue
                    }
                    val bytes = decodePreviewAudio(httpTts, bodyText)
                    file.writeBytes(bytes)
                } else {
                    response.body.byteStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                return file
            } finally {
                response.close()
            }
        }
    }

    private fun refreshAliyunTokenBeforeRequest(httpTts: HttpTTS) {
        val reason = AliyunTtsTokenHelper.refreshReasonBeforeRequest(httpTts) ?: return
        refreshAliyunToken(httpTts, clearLoginHeader = reason == "missingHeader")
    }

    private fun refreshAliyunToken(httpTts: HttpTTS, clearLoginHeader: Boolean) {
        if (!AliyunTtsTokenHelper.isAliyunTTS(httpTts)) return
        if (clearLoginHeader) {
            httpTts.removeLoginHeader()
        }
        httpTts.login()
    }

    private fun previewSpeakText(preset: BookVoicePreset, previewText: String): String {
        return if (preset.provider == BookVoiceProvider.ALIYUN) {
            """<speak><voice name="${preset.requestValue}">$previewText</voice></speak>"""
        } else {
            previewText
        }
    }

    private suspend fun decodePreviewAudio(httpTts: HttpTTS, bodyText: String): ByteArray {
        val responseType = httpTts.responseType?.trim().orEmpty()
        if (responseType.equals("jsonLinesBase64", true)
            || responseType.equals("jsonStreamBase64", true)
        ) {
            return decodeBase64JsonChunks(bodyText)
        }
        val audioText = httpTts.audioPath
            ?.takeIf { it.isNotBlank() }
            ?.let { jsonPath.parse(bodyText).read<String>(it) }
            ?: bodyText
        return when {
            responseType.equals("jsonHex", true) || responseType.equals("hex", true) ->
                hexToBytes(audioText)
            responseType.equals("jsonBase64", true) || responseType.equals("base64", true) ->
                Base64.decode(audioText, Base64.DEFAULT)
            responseType.equals("jsonUrl", true) || responseType.equals("url", true) ->
                downloadAudioUrl(audioText)
            else -> throw IllegalStateException("Unsupported TTS response type: $responseType")
        }
    }

    private fun decodeBase64JsonChunks(bodyText: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (json in extractJsonObjects(bodyText)) {
            val obj = JSONObject(json)
            val code = obj.optInt("code", 0)
            if (code != 0 && code != 20000000) {
                throw IllegalStateException("TTS code=$code ${obj.optString("message")}")
            }
            val data = obj.optString("data")
            if (data.isNotBlank()) {
                out.write(Base64.decode(data, Base64.DEFAULT))
            }
        }
        if (out.size() == 0) {
            throw IllegalStateException("No preview audio data")
        }
        return out.toByteArray()
    }

    private fun extractJsonObjects(text: String): List<String> {
        val objects = arrayListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(text.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "Invalid hex audio" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private suspend fun downloadAudioUrl(url: String): ByteArray {
        return getProxyClient().newCallResponse {
            url(url)
        }.use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            response.body.bytes()
        }
    }
}
