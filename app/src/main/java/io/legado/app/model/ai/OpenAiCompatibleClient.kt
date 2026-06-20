package io.legado.app.model.ai

import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient as defaultOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import splitties.systemservices.powerManager
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

sealed class AiConnectionTestResult {
    data class Success(
        val model: String,
        val reply: String,
        val elapsedMs: Long
    ) : AiConnectionTestResult()

    data class Failure(
        val type: AiConnectionError,
        val code: Int? = null,
        val message: String,
        val elapsedMs: Long
    ) : AiConnectionTestResult()
}

/** OpenAI-compatible chat completions 调用结果。 */
sealed class AiChatResult {
    data class Success(
        val content: String,
        val elapsedMs: Long
    ) : AiChatResult()

    data class Failure(
        val type: AiConnectionError,
        val code: Int? = null,
        val message: String,
        val elapsedMs: Long
    ) : AiChatResult()
}

/** 单条 chat message。role 固定为 system / user。 */
data class AiChatMessage(
    val role: String,
    val content: String
)

enum class AiConnectionError {
    MissingField,
    InvalidEndpoint,
    Unauthorized,
    NotFound,
    RateLimited,
    ServerError,
    Timeout,
    NetworkError,
    BadResponse
}

class OpenAiCompatibleClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient
) {

    companion object {
        /** 请求超时固定 600s，不再可配。 */
        private const val REQUEST_TIMEOUT_SECONDS = 600
        /** 连接测试 ping 用的固定 maxTokens。给足余量避免思考模型把 token 全用在 reasoning 上。 */
        private const val TEST_MAX_TOKENS = 64
    }

    private fun validate(config: AiServiceConfig): String? {
        return when {
            config.endpoint.isEmpty() -> "endpoint"
            !config.endpoint.startsWith("http://") &&
                !config.endpoint.startsWith("https://") -> "endpoint"
            config.apiKey.isEmpty() -> "apiKey"
            config.model.isEmpty() -> "model"
            config.temperature < 0.0f || config.temperature > 2.0f -> "temperature"
            else -> null
        }
    }

    suspend fun testConnection(config: AiServiceConfig): AiConnectionTestResult =
        withContext(Dispatchers.IO) {
            val endpoint = AiServiceConfig.normalizeEndpoint(config.endpoint)
            val validation = validate(config.copy(endpoint = endpoint))
            if (validation != null) {
                return@withContext AiConnectionTestResult.Failure(
                    type = AiConnectionError.MissingField,
                    message = validation,
                    elapsedMs = 0
                )
            }

            val endpointHash = AiServiceConfig.endpointHash(endpoint)
            val url = "$endpoint/chat/completions"
            val payload = buildPayload(config)
            val startNs = System.nanoTime()

            AppLog.putDebug(
                "aiConfig testStart endpointHash=$endpointHash model=${config.model}"
            )

            val client = okHttpClient.newBuilder()
                .setConnectTimeout(REQUEST_TIMEOUT_SECONDS)
                .setReadTimeout(REQUEST_TIMEOUT_SECONDS)
                .setWriteTimeout(REQUEST_TIMEOUT_SECONDS)
                .setCallTimeout(REQUEST_TIMEOUT_SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                    val body = response.body?.string().orEmpty()
                    val code = response.code

                    if (code in 200..299) {
                        val parsed = parseSuccess(body, config.model)
                        if (parsed != null) {
                            AppLog.putDebug(
                                "aiConfig testSuccess model=${config.model} elapsedMs=$elapsedMs"
                            )
                            return@withContext AiConnectionTestResult.Success(
                                model = config.model,
                                reply = parsed,
                                elapsedMs = elapsedMs
                            )
                        }
                        AppLog.putDebug(
                            "aiConfig testFailed type=BadResponse code=$code elapsedMs=$elapsedMs"
                        )
                        return@withContext AiConnectionTestResult.Failure(
                            type = AiConnectionError.BadResponse,
                            code = code,
                            message = body.ifEmpty { "Empty response body" },
                            elapsedMs = elapsedMs
                        )
                    }

                    val (errorType, msg) = classifyHttpError(code, body)
                    AppLog.putDebug(
                        "aiConfig testFailed type=$errorType code=$code elapsedMs=$elapsedMs"
                    )
                    return@withContext AiConnectionTestResult.Failure(
                        type = errorType,
                        code = code,
                        message = msg,
                        elapsedMs = elapsedMs
                    )
                }
            } catch (e: SocketTimeoutException) {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                AppLog.putDebug(
                    "aiConfig testFailed type=Timeout code=null elapsedMs=$elapsedMs"
                )
                return@withContext AiConnectionTestResult.Failure(
                    type = AiConnectionError.Timeout,
                    message = e.message ?: "timeout",
                    elapsedMs = elapsedMs
                )
            } catch (e: Exception) {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                AppLog.putDebug(
                    "aiConfig testFailed type=NetworkError code=null elapsedMs=$elapsedMs"
                )
                return@withContext AiConnectionTestResult.Failure(
                    type = AiConnectionError.NetworkError,
                    message = e.message ?: e.javaClass.simpleName,
                    elapsedMs = elapsedMs
                )
            }
        }

    /**
     * 调用 OpenAI-compatible chat completions，返回原始 content。
     *
     * 用于 MVP6C 章节解析：messages 为 system(Prompt) + user(章节正文)。
     * 不在日志打印 messages 内容，只记录 endpointHash/model/elapsedMs。
     */
    suspend fun chatCompletion(
        config: AiServiceConfig,
        messages: List<AiChatMessage>,
        maxTokens: Int
    ): AiChatResult = withContext(Dispatchers.IO) {
        val endpoint = AiServiceConfig.normalizeEndpoint(config.endpoint)
        val validation = validate(config.copy(endpoint = endpoint))
        if (validation != null) {
            return@withContext AiChatResult.Failure(
                type = AiConnectionError.MissingField,
                message = validation,
                elapsedMs = 0
            )
        }
        val endpointHash = AiServiceConfig.endpointHash(endpoint)
        val url = "$endpoint/chat/completions"
        val payload = buildChatPayload(config, messages, maxTokens)
        val startNs = System.nanoTime()

        AppLog.putDebug(
            "aiChapterParse requestStart endpointHash=$endpointHash model=${config.model} " +
                "mode=${config.mode} thinking=${config.thinkingEnabled} " +
                "stream=${config.stream} messages=${messages.size} maxTokens=$maxTokens " +
                "timeoutSeconds=$REQUEST_TIMEOUT_SECONDS " +
                "callTimeoutMs=${REQUEST_TIMEOUT_SECONDS * 1000} " +
                "screenInteractive=${powerManager.isInteractive}"
        )

        val client = okHttpClient.newBuilder()
            .setConnectTimeout(REQUEST_TIMEOUT_SECONDS)
            .setReadTimeout(REQUEST_TIMEOUT_SECONDS)
            .setWriteTimeout(REQUEST_TIMEOUT_SECONDS)
            .setCallTimeout(REQUEST_TIMEOUT_SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                val body = response.body?.string().orEmpty()
                val code = response.code

                if (code in 200..299) {
                    val parsed = parseSuccess(body, config.model)
                    if (parsed != null) {
                    AppLog.putDebug(
                        "aiChapterParse responseSuccess model=${config.model} " +
                            "elapsedMs=$elapsedMs rawLength=${body.length} " +
                            "screenInteractive=${powerManager.isInteractive}"
                    )
                        return@withContext AiChatResult.Success(
                            content = parsed,
                            elapsedMs = elapsedMs
                        )
                    }
                    AppLog.putDebug(
                        "aiChapterParse responseFailed type=BadResponse code=$code elapsedMs=$elapsedMs"
                    )
                    return@withContext AiChatResult.Failure(
                        type = AiConnectionError.BadResponse,
                        code = code,
                        message = body.ifEmpty { "Empty response body" },
                        elapsedMs = elapsedMs
                    )
                }

                val (errorType, msg) = classifyHttpError(code, body)
                AppLog.putDebug(
                    "aiChapterParse responseFailed type=$errorType code=$code elapsedMs=$elapsedMs " +
                        "message=${msg.logSafe()} body=${body.logSafe()} " +
                        "screenInteractive=${powerManager.isInteractive}"
                )
                return@withContext AiChatResult.Failure(
                    type = errorType,
                    code = code,
                    message = msg,
                    elapsedMs = elapsedMs
                )
            }
        } catch (e: SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            AppLog.putDebug(
                "aiChapterParse responseFailed type=Timeout code=null elapsedMs=$elapsedMs " +
                    "exception=${e.javaClass.simpleName} message=${e.message.orEmpty().logSafe()} " +
                    "screenInteractive=${powerManager.isInteractive}"
            )
            return@withContext AiChatResult.Failure(
                type = AiConnectionError.Timeout,
                message = e.message ?: "timeout",
                elapsedMs = elapsedMs
            )
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            AppLog.putDebug(
                "aiChapterParse responseFailed type=NetworkError code=null elapsedMs=$elapsedMs " +
                    "exception=${e.javaClass.simpleName} message=${e.message.orEmpty().logSafe()} " +
                    "screenInteractive=${powerManager.isInteractive}"
            )
            return@withContext AiChatResult.Failure(
                type = AiConnectionError.NetworkError,
                message = e.message ?: e.javaClass.simpleName,
                elapsedMs = elapsedMs
            )
        }
    }

    private fun formatTemperature(value: Float): Double {
        // float 精度会暴露 0.2f -> 0.20000000298023224，服务端要求小数点后 2 位会报非法。
        // 限制为最多 2 位小数，避免精度尾巴。
        return Math.round(value * 100.0) / 100.0
    }

    private fun buildPayload(config: AiServiceConfig): String {
        val json = JSONObject()
        json.put("model", config.model)
        val messages = JSONArray()
        val message = JSONObject()
        message.put("role", "user")
        message.put("content", "ping")
        messages.put(message)
        json.put("messages", messages)

        // 连接测试强制关闭 thinking/reasoning_effort 并给足 max_tokens，
        // 否则思考模型会把 token 全用在 reasoning 上导致 content 为空、误判为格式错误。
        val thinking = JSONObject()
        thinking.put("type", "disabled")
        json.put("thinking", thinking)

        json.put("temperature", formatTemperature(config.temperature))
        json.put("max_tokens", TEST_MAX_TOKENS)
        json.put("stream", false)

        val payload = json.toString()
        AppLog.putDebug(
            "aiConfig buildPayload provider=${config.provider} mode=${config.mode} " +
                "thinking=false stream=false payload=$payload"
        )
        return payload
    }

    /**
     * 构造 chat completions 请求体，messages 来自参数（system Prompt + user 章节正文）。
     * 不打印 payload，避免泄露正文与 Prompt。
     */
    private fun buildChatPayload(
        config: AiServiceConfig,
        messages: List<AiChatMessage>,
        maxTokens: Int
    ): String {
        val json = JSONObject()
        json.put("model", config.model)
        val messagesArray = JSONArray()
        for (message in messages) {
            val item = JSONObject()
            item.put("role", message.role)
            item.put("content", message.content)
            messagesArray.put(item)
        }
        json.put("messages", messagesArray)

        if (config.thinkingEnabled) {
            val thinking = JSONObject()
            thinking.put("type", "enabled")
            json.put("thinking", thinking)
        }

        val preset = ProviderPresets.forProvider(config.provider)
        if (preset.supportsReasoningEffort && !config.reasoningEffort.isNullOrEmpty()) {
            json.put("reasoning_effort", config.reasoningEffort)
        }

        json.put("temperature", formatTemperature(config.temperature))
        if (config.model.startsWith("deepseek-v4")) {
            json.put("max_completion_tokens", maxTokens)
        } else {
            json.put("max_tokens", maxTokens)
        }
        json.put("stream", config.stream)

        return json.toString()
    }

    private fun parseSuccess(body: String, model: String): String? {
        return runCatching {
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.optJSONObject(0)
                val message = first?.optJSONObject("message")
                val content = message?.optString("content")
                if (!content.isNullOrEmpty()) return@runCatching content
                val text = first?.optString("text")
                if (!text.isNullOrEmpty()) return@runCatching text
            }
            val content = root.optString("content")
            if (!content.isNullOrEmpty()) return@runCatching content
            null
        }.getOrNull()
    }

    private fun classifyHttpError(code: Int, body: String): Pair<AiConnectionError, String> {
        val extracted = extractErrorMessage(body)
        return when (code) {
            401, 403 -> AiConnectionError.Unauthorized to (extracted ?: "Unauthorized")
            404 -> AiConnectionError.NotFound to (extracted ?: "Not found")
            429 -> AiConnectionError.RateLimited to (extracted ?: "Rate limited")
            in 500..599 -> AiConnectionError.ServerError to (extracted ?: "Server error")
            else -> AiConnectionError.BadResponse to (extracted ?: body.logSafe().ifEmpty { "HTTP $code" })
        }
    }

    private fun extractErrorMessage(body: String): String? {
        return runCatching {
            val root = JSONObject(body)
            val error = root.optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: root.optString("message").takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

private fun OkHttpClient.Builder.setConnectTimeout(seconds: Int) =
    connectTimeout(seconds.toLong(), TimeUnit.SECONDS)

private fun OkHttpClient.Builder.setReadTimeout(seconds: Int) =
    readTimeout(seconds.toLong(), TimeUnit.SECONDS)

private fun OkHttpClient.Builder.setWriteTimeout(seconds: Int) =
    writeTimeout(seconds.toLong(), TimeUnit.SECONDS)

private fun OkHttpClient.Builder.setCallTimeout(seconds: Int) =
    callTimeout(seconds.toLong(), TimeUnit.SECONDS)

private fun String.logSafe(maxLength: Int = 120): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .take(maxLength)
