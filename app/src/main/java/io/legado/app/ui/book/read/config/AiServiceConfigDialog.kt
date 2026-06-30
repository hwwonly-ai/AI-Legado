package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.AiServiceInstance
import io.legado.app.databinding.DialogAiServiceConfigBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ai.AiCallMode
import io.legado.app.model.ai.AiConnectionError
import io.legado.app.model.ai.AiConnectionTestResult
import io.legado.app.model.ai.AiProvider
import io.legado.app.model.ai.AiServiceConfig
import io.legado.app.model.ai.AiServiceConfigStore
import io.legado.app.model.ai.AiServiceInstanceRepo
import io.legado.app.model.ai.OpenAiCompatibleClient
import io.legado.app.model.ai.ProviderPresets
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

private sealed class ProviderOption {
    abstract fun displayName(ctx: AiServiceConfigDialog): String
    data class BuiltIn(val provider: AiProvider) : ProviderOption() {
        override fun displayName(ctx: AiServiceConfigDialog) = ctx.providerDisplayName(provider)
    }
    data class Instance(val instance: AiServiceInstance) : ProviderOption() {
        override fun displayName(ctx: AiServiceConfigDialog) =
            instance.name.ifEmpty { instance.model }
    }
    object AddNew : ProviderOption() {
        override fun displayName(ctx: AiServiceConfigDialog) =
            ctx.getString(R.string.ai_service_provider_add_new)
    }
}

class AiServiceConfigDialog : BaseDialogFragment(R.layout.dialog_ai_service_config) {

    private val binding by viewBinding(DialogAiServiceConfigBinding::bind)
    private val client by lazy { OpenAiCompatibleClient() }
    private var testing = false

    private var advancedUserModified = false
    private var reasoningEffortUserModified = false

    private var suppressProviderListener = false
    private var suppressModeListener = false

    private var providerOptions: List<ProviderOption> = emptyList()
    private var currentInstanceId: Long? = null
    private var isCreatingNew = false

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.ai_service_config)
        tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        tvOk.setOnClickListener { saveConfig() }
        tvTest.setOnClickListener { performTest(fromUi()) }
        tvRestore.setOnClickListener { restoreDefaults() }
        tvDelete.setOnClickListener { deleteCurrentInstance() }
        tvPrompt.setOnClickListener { showDialogFragment(AiPromptConfigDialog()) }

        spMode.adapter = simpleAdapter(AiCallMode.values().map { modeDisplayName(it) })

        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (suppressProviderListener) return
                onProviderOptionChanged(providerOptions[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (suppressModeListener) return
                onModeChanged(AiCallMode.values()[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        etTemperature.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) advancedUserModified = true
        }
        etProviderExtraJson.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) advancedUserModified = true
        }
        switchThinking.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressModeListener) {
                advancedUserModified = true
                AppLog.putDebug("aiConfig thinkingToggled enabled=$isChecked")
            }
        }
        switchStream.setOnCheckedChangeListener { _, _ ->
            if (!suppressModeListener) advancedUserModified = true
        }
        spReasoningEffort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (!suppressModeListener && !suppressProviderListener) {
                    reasoningEffortUserModified = true
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun initData() {
        rebuildProviderOptions()
        val config = AiServiceConfig.load()
        AppLog.putDebug(
            "aiConfig initData provider=${config.provider} mode=${config.mode} " +
                "endpointHash=${AiServiceConfig.endpointHash(config.normalizedEndpoint)} " +
                "model=${config.model} thinking=${config.thinkingEnabled} " +
                "reasoningEffort=${config.reasoningEffort} stream=${config.stream} " +
                "instanceId=${config.selectedInstanceId}"
        )
        binding.switchEnabled.isChecked = config.enabled
        suppressModeListener = true
        binding.spMode.setSelection(config.mode.ordinal)
        suppressModeListener = false

        selectOptionByInstance(config.selectedInstanceId, config.provider)
        applyAdvancedFields(config)
    }

    private fun rebuildProviderOptions() {
        val builtIns = listOf(AiProvider.DeepSeek, AiProvider.GLM).map { ProviderOption.BuiltIn(it) }
        val customs = (AiServiceInstanceRepo.allProvidersInstances()
            .filter { it.provider != AiProvider.DeepSeek.name && it.provider != AiProvider.GLM.name }
            .map { ProviderOption.Instance(it) })
        providerOptions = builtIns + customs + ProviderOption.AddNew
        binding.spProvider.adapter = simpleAdapter(providerOptions.map { it.displayName(this) })
    }

    private fun selectOptionByInstance(instanceId: Long?, fallbackProvider: AiProvider) {
        val idx = providerOptions.indexOfFirst { opt ->
            when (opt) {
                is ProviderOption.Instance -> opt.instance.id == instanceId
                is ProviderOption.BuiltIn -> instanceId == null && opt.provider == fallbackProvider
                ProviderOption.AddNew -> false
            }
        }.takeIf { it >= 0 }
            ?: providerOptions.indexOfFirst { it is ProviderOption.BuiltIn && it.provider == fallbackProvider }
            .takeIf { it >= 0 }
            ?: 0
        suppressProviderListener = true
        binding.spProvider.setSelection(idx)
        suppressProviderListener = false
        onProviderOptionChanged(providerOptions[idx])
    }

    private fun onProviderOptionChanged(option: ProviderOption) {
        AppLog.putDebug("aiConfig providerOptionChanged option=${option.javaClass.simpleName}")
        when (option) {
            is ProviderOption.BuiltIn -> loadBuiltIn(option.provider)
            is ProviderOption.Instance -> loadInstance(option.instance)
            ProviderOption.AddNew -> startCreateNew()
        }
    }

    private fun loadBuiltIn(provider: AiProvider) {
        isCreatingNew = false
        binding.tilInstanceName.visibility = View.GONE
        val instance = AiServiceInstanceRepo.getDefault(provider)
        currentInstanceId = instance?.id
        val preset = ProviderPresets.forProvider(provider)
        binding.etEndpoint.setText(instance?.endpoint?.ifEmpty { preset.defaultEndpoint } ?: preset.defaultEndpoint)
        binding.etModel.setText(instance?.model?.ifEmpty { preset.defaultModel } ?: preset.defaultModel)
        binding.etApiKey.setText(instance?.let { AiServiceInstanceRepo.apiKey(it) } ?: "")
        applyReasoningEffortOptions(provider, null)
        AppLog.putDebug("aiConfig loadBuiltIn provider=$provider instanceId=$currentInstanceId")
    }

    private fun loadInstance(instance: AiServiceInstance) {
        isCreatingNew = false
        binding.tilInstanceName.visibility = View.GONE
        currentInstanceId = instance.id
        binding.etEndpoint.setText(instance.endpoint)
        binding.etModel.setText(instance.model)
        binding.etApiKey.setText(AiServiceInstanceRepo.apiKey(instance))
        applyReasoningEffortOptions(AiProvider.Custom, null)
        AppLog.putDebug("aiConfig loadInstance instanceId=${instance.id} provider=${instance.provider}")
    }

    private fun startCreateNew() {
        isCreatingNew = true
        currentInstanceId = null
        binding.tilInstanceName.visibility = View.VISIBLE
        binding.etInstanceName.setText("")
        binding.etEndpoint.setText("")
        binding.etModel.setText("")
        binding.etApiKey.setText("")
        applyReasoningEffortOptions(AiProvider.Custom, null)
        AppLog.putDebug("aiConfig startCreateNew")
    }

    private fun applyAdvancedFields(config: AiServiceConfig) {
        binding.switchThinking.isChecked = config.thinkingEnabled
        binding.etTemperature.setText(config.temperature.toString())
        binding.switchStream.isChecked = config.stream
        binding.etProviderExtraJson.setText(config.providerExtraJson)
    }

    private fun currentProviderName(): String {
        val option = providerOptions.getOrNull(binding.spProvider.selectedItemPosition)
        return when (option) {
            is ProviderOption.BuiltIn -> option.provider.name
            is ProviderOption.Instance -> option.instance.provider
            ProviderOption.AddNew -> AiProvider.Custom.name
            null -> AiProvider.DeepSeek.name
        }
    }

    private fun onModeChanged(mode: AiCallMode) {
        val providerName = currentProviderName()
        val provider = runCatching { AiProvider.valueOf(providerName) }.getOrNull() ?: AiProvider.Custom
        val params = ProviderPresets.defaultParamsForMode(mode, provider)
        AppLog.putDebug(
            "aiConfig modeChanged to=$mode autoFill=${!advancedUserModified} " +
                "thinking=${params.thinkingEnabled} reasoningEffort=${params.reasoningEffort} " +
                "temp=${params.temperature}"
        )
        if (advancedUserModified) return
        suppressModeListener = true
        binding.switchThinking.isChecked = params.thinkingEnabled
        binding.etTemperature.setText(params.temperature.toString())
        if (!reasoningEffortUserModified) applyReasoningEffortSelection(params.reasoningEffort)
        suppressModeListener = false
    }

    private fun restoreDefaults() {
        val providerName = currentProviderName()
        val provider = runCatching { AiProvider.valueOf(providerName) }.getOrNull() ?: AiProvider.Custom
        val mode = AiCallMode.values()[binding.spMode.selectedItemPosition]
        val params = ProviderPresets.defaultParamsForMode(mode, provider)
        AppLog.putDebug(
            "aiConfig restoreDefaults provider=$provider mode=$mode " +
                "thinking=${params.thinkingEnabled} reasoningEffort=${params.reasoningEffort} " +
                "temp=${params.temperature}"
        )
        suppressModeListener = true
        binding.run {
            switchThinking.isChecked = params.thinkingEnabled
            etTemperature.setText(params.temperature.toString())
            etProviderExtraJson.setText("")
            applyReasoningEffortOptions(provider, params.reasoningEffort)
        }
        suppressModeListener = false
        advancedUserModified = false
        reasoningEffortUserModified = false
    }

    private fun applyReasoningEffortOptions(provider: AiProvider, selected: String?) = binding.run {
        val preset = ProviderPresets.forProvider(provider)
        if (preset.supportsReasoningEffort && preset.reasoningEffortValues.isNotEmpty()) {
            llReasoningEffort.visibility = View.VISIBLE
            spReasoningEffort.adapter = simpleAdapter(preset.reasoningEffortValues)
            applyReasoningEffortSelection(selected)
        } else {
            llReasoningEffort.visibility = View.GONE
        }
    }

    private fun applyReasoningEffortSelection(selected: String?) = binding.run {
        val adapter = spReasoningEffort.adapter ?: return
        val count = adapter.count
        var idx = -1
        if (selected != null) {
            for (i in 0 until count) {
                if (adapter.getItem(i) == selected) {
                    idx = i
                    break
                }
            }
        }
        spReasoningEffort.setSelection(if (idx >= 0) idx else 0)
    }

    private fun fromUi(): AiServiceConfig = binding.run {
        val providerName = currentProviderName()
        val provider = runCatching { AiProvider.valueOf(providerName) }.getOrNull() ?: AiProvider.Custom
        val mode = AiCallMode.values()[spMode.selectedItemPosition]
        val reasoningEffort = if (llReasoningEffort.visibility == View.VISIBLE) {
            spReasoningEffort.selectedItem as? String
        } else {
            null
        }
        AiServiceConfig(
            enabled = switchEnabled.isChecked,
            provider = provider,
            selectedInstanceId = currentInstanceId,
            endpoint = etEndpoint.text?.toString().orEmpty(),
            apiKey = etApiKey.text?.toString().orEmpty(),
            model = etModel.text?.toString().orEmpty(),
            mode = mode,
            thinkingEnabled = switchThinking.isChecked,
            reasoningEffort = reasoningEffort,
            temperature = etTemperature.text?.toString()?.toFloatOrNull()
                ?: AiServiceConfig().temperature,
            stream = switchStream.isChecked,
            providerExtraJson = etProviderExtraJson.text?.toString().orEmpty()
        )
    }

    private fun saveConfig() {
        val config = fromUi()
        if (isCreatingNew) {
            val name = binding.etInstanceName.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                binding.tilInstanceName.error = getString(R.string.ai_service_instance_name_required)
                return
            }
            val instance = AiServiceInstance(
                name = name,
                provider = name,
                endpoint = config.endpoint,
                model = config.model
            )
            AiServiceInstanceRepo.save(instance, config.apiKey)
            currentInstanceId = instance.id
            isCreatingNew = false
            AppLog.putDebug("aiConfig createNewInstance id=${instance.id} name=$name")
        } else {
            persistCurrentInstance(config)
        }
        AiServiceConfigStore.save(fromUi())
        dismissAllowingStateLoss()
    }

    private fun persistCurrentInstance(config: AiServiceConfig) {
        val existing = currentInstanceId?.let { AiServiceInstanceRepo.get(it) }
        if (existing != null) {
            existing.endpoint = config.endpoint
            existing.model = config.model
            AiServiceInstanceRepo.save(existing, config.apiKey)
            currentInstanceId = existing.id
        }
    }

    private fun deleteCurrentInstance() {
        val option = providerOptions.getOrNull(binding.spProvider.selectedItemPosition) ?: return
        val instance = when (option) {
            is ProviderOption.Instance -> option.instance
            is ProviderOption.BuiltIn -> AiServiceInstanceRepo.getDefault(option.provider) ?: return
            ProviderOption.AddNew -> return
        } ?: return
        alert(message = getString(R.string.ai_service_instance_delete_confirm, instance.name)) {
            okButton {
                AiServiceInstanceRepo.delete(instance)
                currentInstanceId = null
                rebuildProviderOptions()
                selectOptionByInstance(null, AiProvider.DeepSeek)
            }
            cancelButton()
        }
    }

    private fun performTest(config: AiServiceConfig) {
        if (testing) return
        testing = true
        binding.run {
            tvTest.isEnabled = false
            tvTest.text = getString(R.string.ai_service_testing)
            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = getString(R.string.ai_service_test_running)
        }
        lifecycleScope.launch {
            val result = client.testConnection(config)
            if (!isAdded) return@launch
            binding.run {
                tvTest.isEnabled = true
                tvTest.text = getString(R.string.ai_service_test)
                when (result) {
                    is AiConnectionTestResult.Success -> {
                        tvTestResult.text = getString(
                            R.string.ai_service_test_success,
                            result.model,
                            result.reply.take(80)
                        )
                    }

                    is AiConnectionTestResult.Failure -> {
                        tvTestResult.text = formatFailure(result)
                    }
                }
            }
            testing = false
        }
    }

    private fun formatFailure(failure: AiConnectionTestResult.Failure): String {
        val reasonRes = when (failure.type) {
            AiConnectionError.MissingField -> R.string.ai_service_err_missing_field
            AiConnectionError.InvalidEndpoint -> R.string.ai_service_err_invalid_endpoint
            AiConnectionError.Unauthorized -> R.string.ai_service_err_unauthorized
            AiConnectionError.NotFound -> R.string.ai_service_err_not_found
            AiConnectionError.RateLimited -> R.string.ai_service_err_rate_limited
            AiConnectionError.ServerError -> R.string.ai_service_err_server_error
            AiConnectionError.Timeout -> R.string.ai_service_err_timeout
            AiConnectionError.NetworkError -> R.string.ai_service_err_network
            AiConnectionError.BadResponse -> R.string.ai_service_err_bad_response
        }
        val detail = failure.message.ifEmpty { failure.code?.toString().orEmpty() }
        return getString(R.string.ai_service_test_failed, getString(reasonRes), detail)
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    internal fun providerDisplayName(provider: AiProvider): String = when (provider) {
        AiProvider.DeepSeek -> getString(R.string.ai_service_provider_deepseek)
        AiProvider.GLM -> getString(R.string.ai_service_provider_glm)
        AiProvider.Custom -> getString(R.string.ai_service_provider_custom)
    }

    private fun modeDisplayName(mode: AiCallMode): String = when (mode) {
        AiCallMode.Fast -> getString(R.string.ai_service_mode_fast)
        AiCallMode.Balanced -> getString(R.string.ai_service_mode_balanced)
        AiCallMode.Refine -> getString(R.string.ai_service_mode_refine)
    }
}
