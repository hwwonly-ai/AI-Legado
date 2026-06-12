package io.legado.app.model.tts

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import java.util.UUID

class DoubaoTtsClient(
    private val config: DoubaoApiConfig,
    private val client: OkHttpClient = okHttpClient
) {

    suspend fun synthesize(request: DoubaoTtsRequest): DoubaoTtsAudio {
        val reqId = UUID.randomUUID().toString()
        val payload = buildPayload(request, reqId)
        val body = GSON.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val response = client.newCallResponse {
            url(config.endpoint)
            addHeader("Content-Type", "application/json")
            addHeader("Authorization", config.authorizationHeader())
            post(body)
        }
        val bodyText = response.body.string()
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Doubao TTS HTTP ${response.code}: ${bodyText.take(ERROR_PREVIEW_LENGTH)}"
            )
        }
        val result = GSON.fromJson(bodyText, DoubaoV1Response::class.java)
        if (result.data.isNullOrBlank()) {
            val message = result.message
                ?: result.error
                ?: bodyText.take(ERROR_PREVIEW_LENGTH)
            throw IllegalStateException("Doubao TTS failed: code=${result.code}, message=$message")
        }
        return DoubaoTtsAudio(
            reqId = result.reqid ?: reqId,
            bytes = Base64.getDecoder().decode(result.data.filterNot { it.isWhitespace() }),
            encoding = config.encoding,
            rawResponse = bodyText
        )
    }

    suspend fun synthesizeStream(request: DoubaoTtsRequest): InputStream {
        return ByteArrayInputStream(synthesize(request).bytes)
    }

    fun buildPayload(
        request: DoubaoTtsRequest,
        reqId: String = UUID.randomUUID().toString()
    ): Map<String, Any> {
        val audio = linkedMapOf<String, Any>(
            "voice_type" to request.voiceType,
            "encoding" to config.encoding,
            "rate" to config.rate,
            "speed_ratio" to config.speedRatio,
            "volume_ratio" to config.volumeRatio,
            "pitch_ratio" to config.pitchRatio
        )
        request.emotion?.takeIf { it.isNotBlank() }?.let {
            audio["emotion"] = it
        }
        val requestBody = linkedMapOf<String, Any>(
            "reqid" to reqId,
            "text" to request.text,
            "text_type" to "plain",
            "operation" to "query"
        )
        buildAdditions(request).takeIf { it.isNotEmpty() }?.let {
            requestBody["extra_param"] = GSON.toJson(it)
        }
        return linkedMapOf(
            "app" to linkedMapOf(
                "appid" to config.appId,
                "token" to config.accessToken,
                "cluster" to config.cluster
            ),
            "user" to linkedMapOf(
                "uid" to config.uid
            ),
            "audio" to audio,
            "request" to requestBody
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

    private data class DoubaoV1Response(
        val reqid: String? = null,
        val code: Int? = null,
        val message: String? = null,
        val error: String? = null,
        val operation: String? = null,
        val sequence: Int? = null,
        val data: String? = null
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val ERROR_PREVIEW_LENGTH = 500

        fun buildPayloadForConfig(
            config: DoubaoApiConfig,
            request: DoubaoTtsRequest,
            reqId: String = UUID.randomUUID().toString()
        ): Map<String, Any> {
            val audio = linkedMapOf<String, Any>(
                "voice_type" to request.voiceType,
                "encoding" to config.encoding,
                "rate" to config.rate,
                "speed_ratio" to config.speedRatio,
                "volume_ratio" to config.volumeRatio,
                "pitch_ratio" to config.pitchRatio
            )
            request.emotion?.takeIf { it.isNotBlank() }?.let {
                audio["emotion"] = it
            }
            val requestBody = linkedMapOf<String, Any>(
                "reqid" to reqId,
                "text" to request.text,
                "text_type" to "plain",
                "operation" to "query"
            )
            buildAdditionsForRequest(config, request).takeIf { it.isNotEmpty() }?.let {
                requestBody["extra_param"] = GSON.toJson(it)
            }
            return linkedMapOf(
                "app" to linkedMapOf(
                    "appid" to config.appId,
                    "token" to config.accessToken,
                    "cluster" to config.cluster
                ),
                "user" to linkedMapOf(
                    "uid" to config.uid
                ),
                "audio" to audio,
                "request" to requestBody
            )
        }

        private fun buildAdditionsForRequest(config: DoubaoApiConfig, request: DoubaoTtsRequest): Map<String, Any> {
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
    }
}
