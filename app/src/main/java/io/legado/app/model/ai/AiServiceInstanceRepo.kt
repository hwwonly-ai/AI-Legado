package io.legado.app.model.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiServiceInstance

object AiServiceInstanceRepo {

    fun getAll(provider: AiProvider): List<AiServiceInstance> =
        appDb.aiServiceInstanceDao.getByProvider(provider.name)

    fun allProvidersInstances(): List<AiServiceInstance> =
        appDb.aiServiceInstanceDao.all

    fun getDefault(provider: AiProvider): AiServiceInstance? =
        appDb.aiServiceInstanceDao.getDefault(provider.name)

    fun get(id: Long): AiServiceInstance? =
        appDb.aiServiceInstanceDao.get(id)

    fun apiKey(instance: AiServiceInstance): String =
        ApiKeyCrypto.decrypt(instance.apiKeyEncrypted)

    fun save(instance: AiServiceInstance, plaintextApiKey: String): Long {
        instance.apiKeyEncrypted = ApiKeyCrypto.encrypt(plaintextApiKey)
        if (instance.isDefault) {
            clearOtherDefaults(instance.provider, instance.id)
        }
        appDb.aiServiceInstanceDao.insert(instance)
        AppLog.putDebug(
            "aiConfig instanceSaved id=${instance.id} provider=${instance.provider} " +
                "isDefault=${instance.isDefault} hasKey=${plaintextApiKey.isNotEmpty()}"
        )
        return instance.id
    }

    fun setDefault(provider: AiProvider, id: Long) {
        clearOtherDefaults(provider.name, id)
        get(id)?.let {
            it.isDefault = true
            appDb.aiServiceInstanceDao.update(it)
        }
    }

    fun delete(instance: AiServiceInstance) {
        appDb.aiServiceInstanceDao.delete(instance)
        AppLog.putDebug("aiConfig instanceDeleted id=${instance.id} provider=${instance.provider}")
    }

    private fun clearOtherDefaults(provider: String, keepId: Long) {
        getAll(runCatching { AiProvider.valueOf(provider) }.getOrDefault(AiProvider.Custom))
            .filter { it.isDefault && it.id != keepId }
            .forEach {
                it.isDefault = false
                appDb.aiServiceInstanceDao.update(it)
            }
    }
}
