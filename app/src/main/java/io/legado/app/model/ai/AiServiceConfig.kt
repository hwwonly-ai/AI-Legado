package io.legado.app.model.ai

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import splitties.init.appCtx

enum class AiProvider {
    DeepSeek,
    GLM,
    Custom
}

enum class AiCallMode {
    Fast,
    Balanced,
    Refine
}

object ProviderPresets {

    data class Preset(
        val defaultEndpoint: String,
        val defaultModel: String,
        val advancedModel: String,
        val modelOptions: List<String>,
        val supportsThinking: Boolean,
        val supportsReasoningEffort: Boolean,
        val reasoningEffortValues: List<String> = emptyList()
    )

    fun forProvider(provider: AiProvider): Preset = when (provider) {
        AiProvider.DeepSeek -> Preset(
            defaultEndpoint = "https://api.deepseek.com",
            defaultModel = "deepseek-v4-flash",
            advancedModel = "deepseek-v4-pro",
            modelOptions = listOf("deepseek-v4-flash", "deepseek-v4-pro"),
            supportsThinking = true,
            supportsReasoningEffort = true,
            reasoningEffortValues = listOf("high", "max")
        )
        AiProvider.GLM -> Preset(
            defaultEndpoint = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4.7-flashx",
            advancedModel = "glm-4.7",
            modelOptions = listOf("glm-4.7-flashx", "glm-4.7"),
            supportsThinking = true,
            supportsReasoningEffort = false
        )
        AiProvider.Custom -> Preset(
            defaultEndpoint = "",
            defaultModel = "",
            advancedModel = "",
            modelOptions = emptyList(),
            supportsThinking = false,
            supportsReasoningEffort = false
        )
    }

    fun forProviderName(name: String): Preset {
        val provider = runCatching { AiProvider.valueOf(name) }.getOrNull()
            ?: AiProvider.Custom
        return forProvider(provider)
    }

    data class ModeParams(
        val thinkingEnabled: Boolean,
        val reasoningEffort: String?,
        val temperature: Float
    )

    fun defaultParamsForMode(mode: AiCallMode, provider: AiProvider): ModeParams {
        val supportsReasoningEffort = forProvider(provider).supportsReasoningEffort
        return when (mode) {
            AiCallMode.Fast -> ModeParams(
                thinkingEnabled = false,
                reasoningEffort = null,
                temperature = 0.2f
            )
            AiCallMode.Balanced -> ModeParams(
                thinkingEnabled = true,
                reasoningEffort = if (supportsReasoningEffort) "high" else null,
                temperature = 0.2f
            )
            AiCallMode.Refine -> ModeParams(
                thinkingEnabled = true,
                reasoningEffort = if (supportsReasoningEffort) "max" else null,
                temperature = 0.1f
            )
        }
    }
}

data class AiServiceConfig(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.DeepSeek,
    val selectedInstanceId: Long? = null,
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
    val mode: AiCallMode = AiCallMode.Fast,
    val thinkingEnabled: Boolean = false,
    val reasoningEffort: String? = null,
    val temperature: Float = 0.2f,
    val stream: Boolean = false,
    val providerExtraJson: String = ""
) {

    val normalizedEndpoint: String
        get() = normalizeEndpoint(endpoint)

    companion object {

        fun normalizeEndpoint(input: String): String {
            var value = input.trim().trimEnd('/')
            val suffix = "/chat/completions"
            if (value.endsWith(suffix, ignoreCase = true)) {
                value = value.removeSuffix(suffix)
                value = value.trimEnd('/')
            }
            return value
        }

        fun maskApiKey(key: String): String {
            if (key.isEmpty()) return ""
            if (key.length <= 8) return "***"
            return key.take(4) + "..." + key.takeLast(3)
        }

        fun endpointHash(endpoint: String): String {
            val host = runCatching {
                endpoint.toHttpUrlOrNull()?.host ?: endpoint
            }.getOrDefault(endpoint)
            return host.hashCode().toString(16)
        }

        fun load(): AiServiceConfig {
            val prefs = appCtx.defaultSharedPreferences
            val provider = appCtx.getPrefString(PreferKey.aiServiceProvider, "")
                ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
                ?: AiProvider.DeepSeek
            val mode = appCtx.getPrefString(PreferKey.aiServiceMode, "")
                ?.let { runCatching { AiCallMode.valueOf(it) }.getOrNull() }
                ?: AiCallMode.Fast
            val recommended = ProviderPresets.defaultParamsForMode(mode, provider)
            cleanupLegacyPrefKeys()
            val selectedInstanceId = appCtx.getPrefString(PreferKey.aiServiceSelectedInstance, "")
                ?.toLongOrNull()
            migrateLegacyApiKeyIfNeeded(provider)
            val config = AiServiceConfig(
                enabled = appCtx.getPrefBoolean(PreferKey.aiServiceEnabled, false),
                provider = provider,
                selectedInstanceId = selectedInstanceId,
                endpoint = appCtx.getPrefString(PreferKey.aiServiceEndpoint, "") ?: "",
                apiKey = resolveApiKey(provider, selectedInstanceId),
                model = appCtx.getPrefString(PreferKey.aiServiceModel, "") ?: "",
                mode = mode,
                thinkingEnabled = appCtx.getPrefBoolean(
                    PreferKey.aiServiceThinkingEnabled,
                    recommended.thinkingEnabled
                ),
                reasoningEffort = if (prefs.contains(PreferKey.aiServiceReasoningEffort)) {
                    appCtx.getPrefString(PreferKey.aiServiceReasoningEffort, "")
                        ?.takeIf { it.isNotEmpty() }
                } else {
                    recommended.reasoningEffort
                },
                temperature = appCtx.getPrefString(
                    PreferKey.aiServiceTemperature,
                    recommended.temperature.toString()
                )?.toFloatOrNull() ?: recommended.temperature,
                stream = appCtx.getPrefBoolean(PreferKey.aiServiceStream, false),
                providerExtraJson = appCtx.getPrefString(PreferKey.aiServiceProviderExtraJson, "") ?: ""
            )
            AppLog.putDebug(
                "aiConfig load provider=${config.provider} mode=${config.mode} " +
                    "endpointHash=${endpointHash(config.normalizedEndpoint)} model=${config.model} " +
                    "thinking=${config.thinkingEnabled} reasoningEffort=${config.reasoningEffort} " +
                    "stream=${config.stream} instanceId=${config.selectedInstanceId} " +
                    "hasKey=${config.apiKey.isNotEmpty()}"
            )
            return config
        }

        private fun resolveApiKey(provider: AiProvider, instanceId: Long?): String {
            val instance = instanceId?.let { AiServiceInstanceRepo.get(it) }
                ?: AiServiceInstanceRepo.getDefault(provider) ?: return ""
            return AiServiceInstanceRepo.apiKey(instance)
        }

        private fun cleanupLegacyPrefKeys() {
            // MVP6D 后 maxTokens/topP/timeout 不再可配，清理旧 pref 残留值。
            // 这些 key 已从 PreferKey 移除，这里用字面量清理磁盘残留。
            appCtx.removePref("aiServiceMaxTokens")
            appCtx.removePref("aiServiceTopP")
            appCtx.removePref("aiServiceTimeoutSeconds")
        }

        private fun migrateLegacyApiKeyIfNeeded(provider: AiProvider) {
            val legacyKey = appCtx.getPrefString(PreferKey.aiServiceApiKey, "") ?: ""
            if (legacyKey.isEmpty()) return
            if (AiServiceInstanceRepo.getDefault(provider) != null) return
            val instance = io.legado.app.data.entities.AiServiceInstance(
                name = provider.name,
                provider = provider.name,
                endpoint = appCtx.getPrefString(PreferKey.aiServiceEndpoint, "") ?: "",
                model = appCtx.getPrefString(PreferKey.aiServiceModel, "") ?: "",
                isDefault = true
            )
            AiServiceInstanceRepo.save(instance, legacyKey)
            appCtx.removePref(PreferKey.aiServiceApiKey)
            AppLog.putDebug("aiConfig migrateLegacyKey provider=$provider instanceId=${instance.id}")
        }
    }
}

object AiServiceConfigStore {

    fun save(config: AiServiceConfig) {
        val endpoint = AiServiceConfig.normalizeEndpoint(config.endpoint)
        appCtx.putPrefBoolean(PreferKey.aiServiceEnabled, config.enabled)
        appCtx.putPrefString(PreferKey.aiServiceProvider, config.provider.name)
        if (config.selectedInstanceId != null) {
            appCtx.putPrefString(
                PreferKey.aiServiceSelectedInstance,
                config.selectedInstanceId.toString()
            )
        } else {
            appCtx.removePref(PreferKey.aiServiceSelectedInstance)
        }
        appCtx.putPrefString(PreferKey.aiServiceEndpoint, endpoint)
        appCtx.putPrefString(PreferKey.aiServiceModel, config.model)
        appCtx.putPrefString(PreferKey.aiServiceMode, config.mode.name)
        appCtx.putPrefBoolean(PreferKey.aiServiceThinkingEnabled, config.thinkingEnabled)
        if (config.reasoningEffort.isNullOrEmpty()) {
            appCtx.removePref(PreferKey.aiServiceReasoningEffort)
        } else {
            appCtx.putPrefString(PreferKey.aiServiceReasoningEffort, config.reasoningEffort)
        }
        appCtx.putPrefString(PreferKey.aiServiceTemperature, config.temperature.toString())
        appCtx.putPrefBoolean(PreferKey.aiServiceStream, config.stream)
        appCtx.putPrefString(PreferKey.aiServiceProviderExtraJson, config.providerExtraJson)
        AppLog.putDebug(
            "aiConfig save provider=${config.provider} mode=${config.mode} " +
                "endpointHash=${AiServiceConfig.endpointHash(endpoint)} model=${config.model} " +
                "thinking=${config.thinkingEnabled} reasoningEffort=${config.reasoningEffort} " +
                "stream=${config.stream}"
        )
    }
}
