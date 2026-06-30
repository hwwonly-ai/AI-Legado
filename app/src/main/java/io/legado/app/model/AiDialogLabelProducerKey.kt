package io.legado.app.model

import io.legado.app.model.ai.AiPromptStore
import io.legado.app.model.ai.AiServiceConfig

object AiDialogLabelProducerKey {

    fun current(config: AiServiceConfig, mode: String): String {
        val promptConfig = AiPromptStore.load()
        return ReadAloudSegmentCacheKey.aiDialogLabelProducer(
            promptVersion = promptConfig.version,
            promptHash = promptConfig.contentHash,
            provider = config.provider.name,
            selectedInstanceId = config.selectedInstanceId,
            endpointHash = AiServiceConfig.endpointHash(config.normalizedEndpoint),
            model = config.model,
            mode = mode,
            thinkingEnabled = config.thinkingEnabled,
            reasoningEffort = config.reasoningEffort
        )
    }
}
