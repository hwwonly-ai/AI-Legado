package io.legado.app.model.tts

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID

/**
 * 豆包 TTS V3 HTTP 单向接口。
 */
class DoubaoTtsClientV3(
    private val config: DoubaoApiConfig,
    private val client: OkHttpClient = okHttpClient
) {

    suspend fun synthesizeStream(request: DoubaoTtsRequest): DoubaoTtsAudio {
        val reqId = UUID.randomUUID().toString()
        val payload = buildPayloadV3(request, reqId)
        val body = GSON.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)

        val response = client.newCallResponse {
            url(config.endpointV3)
            addHeader("Content-Type", "application/json")
            addHeader("X-Api-App-Key", config.appId)
            addHeader("X-Api-Access-Key", config.accessToken)
            addHeader("X-Api-Resource-Id", config.resourceIdV3)
            addHeader("X-Api-Request-Id", reqId)
            post(body)
        }

        val bodyText = response.body.string()
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Doubao TTS V3 HTTP ${response.code}: ${bodyText.take(ERROR_PREVIEW_LENGTH)}"
            )
        }

        val result = GSON.fromJson(bodyText, DoubaoV3Response::class.java)
        if (result.code != 0 || result.data.isNullOrBlank()) {
            val message = result.message ?: bodyText.take(ERROR_PREVIEW_LENGTH)
            throw IllegalStateException("Doubao TTS V3 failed: code=${result.code}, message=$message")
        }

        return DoubaoTtsAudio(
            reqId = result.reqid ?: reqId,
            bytes = Base64.getDecoder().decode(result.data.filterNot { it.isWhitespace() }),
            encoding = config.encoding,
            rawResponse = bodyText
        )
    }

    fun buildPayloadV3(
        request: DoubaoTtsRequest,
        reqId: String = UUID.randomUUID().toString()
    ): Map<String, Any> {
        val audioParams = linkedMapOf<String, Any>(
            "format" to config.formatV3(),
            "sample_rate" to config.rate,
            "speech_rate" to config.speedRatio.toSpeechRateV3(),
            "enable_timestamp" to false
        )

        val reqParams = linkedMapOf<String, Any>(
            "text" to request.text,
            "speaker" to request.voiceType,
            "audio_params" to audioParams
        )

        buildAdditions(request).takeIf { it.isNotEmpty() }?.let {
            reqParams["additions"] = it
        }

        return linkedMapOf(
            "user" to linkedMapOf(
                "uid" to config.uid
            ),
            "req_params" to reqParams
        )
    }

    private fun buildAdditions(request: DoubaoTtsRequest): Map<String, Any> {
        val additions = linkedMapOf<String, Any>()
        if (config.explicitLanguage.isNotBlank()) {
            additions["explicit_language"] = config.explicitLanguage
        }
        additions["disable_markdown_filter"] = config.disableMarkdownFilter
        additions["disable_emoji_filter"] = config.disableEmojiFilter
        if (request.contextTexts.isNotEmpty()) {
            additions["context_texts"] = request.contextTexts
        }
        if (request.useTagParser) {
            additions["use_tag_parser"] = true
        }
        return additions
    }

    private data class DoubaoV3Response(
        val reqid: String? = null,
        val code: Int? = null,
        val message: String? = null,
        val data: String? = null
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val ERROR_PREVIEW_LENGTH = 500
    }
}

private fun Double.toSpeechRateV3(): Int {
    return ((this - 1.0) * 100).toInt().coerceIn(-50, 100)
}
