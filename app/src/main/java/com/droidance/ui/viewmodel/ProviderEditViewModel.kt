// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.droidance.settings.ProviderConfigManager
import com.droidance.core.model.llm.ProviderEndpoint
import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.session.ModelTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

/**
 * Provider 编辑页 ViewModel
 */
@HiltViewModel
class ProviderEditViewModel @Inject constructor(
    private val configManager: ProviderConfigManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderEditUiState())
    val uiState: StateFlow<ProviderEditUiState> = _uiState
    val presets get() = configManager.presets

    private var loaded = false
    private var initialState = ProviderEditUiState()
    fun hasUnsavedChanges(): Boolean = _uiState.value.copy(saveFailed = false) !=
        initialState.copy(saveFailed = false)

    private var editingId: String? = null
    private var editingConfig: ProviderConfig? = null

    /** 加载已有 Provider 配置（编辑模式） */
    fun loadProvider(id: String?) {
        if (loaded && editingId == id) return
        if (id != null) {
            editingId = id
            val config = configManager.getConfig(id) ?: return
            editingConfig = config
            _uiState.value = config.toEditState()
        } else {
            editingId = null
            editingConfig = null
            _uiState.value = ProviderEditUiState()
        }
        initialState = _uiState.value
        loaded = true
    }

    // ====== 字段更新 ======

    fun selectPreset(id: String?) {
        if (editingId != null) return
        val preset = id?.let { selected -> presets.firstOrNull { it.id == selected } ?: return }
        // A key belongs to its endpoint; selecting another template always clears it.
        _uiState.value = if (preset == null) ProviderEditUiState() else ProviderEditUiState(
            presetId = preset.id,
            name = preset.name,
            baseUrl = preset.baseUrl,
            apiType = preset.apiType,
            transportKind = preset.transportKind
        )
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun updateBaseUrl(url: String) {
        _uiState.update { it.copy(baseUrl = url) }
    }

    fun selectProtocol(transport: ModelTransport) {
        _uiState.update { it.withProtocol(transport) }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
    }

    fun updateModelInput(model: String) {
        _uiState.update { it.copy(modelInput = model) }
    }

    fun updateReasoningPreference(effort: ReasoningEffort?) {
        _uiState.update { it.copy(reasoningPreference = effort) }
    }

    fun addModel() {
        _uiState.update { state ->
            val inputModels = parseModelInput(state.modelInput)
            if (inputModels.isEmpty()) return@update state
            val models = mergeModels(state.models, inputModels)
            state.copy(
                models = models,
                defaultModel = state.defaultModel.ifBlank { inputModels.first() },
                modelInput = ""
            )
        }
    }

    fun removeModel(model: String) {
        _uiState.update { state ->
            val models = state.models.filterNot { it == model }
            state.copy(
                models = models,
                defaultModel = state.defaultModel.takeIf { it in models }
                    ?: models.firstOrNull().orEmpty()
            )
        }
    }

    fun selectDefaultModel(model: String) {
        _uiState.update { state ->
            if (model in state.models) state.copy(defaultModel = model) else state
        }
    }

    // ====== 保存 ======

    fun saveProvider(): Boolean {
        val state = _uiState.value
        if (state.validationError != null) return false
        val models = mergeModels(state.models, parseModelInput(state.modelInput))
        if (state.name.isBlank() || state.baseUrl.isBlank() || state.apiKey.isBlank() || models.isEmpty()) return false
        val config = state.toConfig(
            id = editingId ?: UUID.randomUUID().toString(),
            models = models,
            contextWindowTokens = editingConfig?.contextWindowTokens ?: 128_000
        )
        val saved = runCatching { configManager.updateConfig(config) }.isSuccess
        _uiState.update { it.copy(saveFailed = !saved) }
        if (saved) initialState = _uiState.value
        return saved
    }

    private fun parseModelInput(input: String): List<String> = input
        .split(',', '，', ';', '；', '、', '\n', '\r', '\t')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun mergeModels(existing: List<String>, added: List<String>): List<String> {
        return (existing + added)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}

/**
 * Provider 编辑页 UI 状态
 */
data class ProviderEditUiState(
    val presetId: String? = null,
    val name: String = "",
    val apiType: ApiType = ApiType.OPENAI_COMPATIBLE,
    val transportKind: ModelTransport? = ModelTransport.GENERIC_CHAT_COMPLETIONS,
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<String> = emptyList(),
    val defaultModel: String = "",
    val modelInput: String = "",
    val isEnabled: Boolean = true,
    val reasoningPreference: ReasoningEffort? = null,
    val saveFailed: Boolean = false
) {
    private val hasSupportedUrl: Boolean
        get() = runCatching {
            val uri = java.net.URI(baseUrl.trim())
            uri.scheme?.lowercase(java.util.Locale.ROOT) in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)

    val requestUrl: String?
        get() = transportKind?.takeIf { hasSupportedUrl }?.let { ProviderEndpoint.resolve(baseUrl, it) }

    val validationError: ProviderFormError?
        get() = when {
            name.isBlank() -> ProviderFormError.NAME_REQUIRED
            baseUrl.isBlank() -> ProviderFormError.URL_REQUIRED
            !hasSupportedUrl -> ProviderFormError.INVALID_URL
            transportKind == null -> ProviderFormError.PROTOCOL_REQUIRED
            apiKey.isBlank() -> ProviderFormError.KEY_REQUIRED
            models.all { it.isBlank() } && modelInput.split(',', '，', ';', '；', '、', '\n', '\r', '\t').all { it.isBlank() } -> ProviderFormError.MODEL_REQUIRED
            else -> null
        }

    /** Selecting the current protocol keeps a stored CUSTOM entry as it is. */
    fun withProtocol(transport: ModelTransport): ProviderEditUiState =
        if (transport == transportKind) this else copy(
            transportKind = transport,
            apiType = when (transport) {
                ModelTransport.OPENAI_RESPONSES -> ApiType.OPENAI_RESPONSES
                ModelTransport.GENERIC_CHAT_COMPLETIONS -> ApiType.OPENAI_COMPATIBLE
            }
        )

    /** Saving carries the protocol the form shows, which settles any pending confirmation. */
    internal fun toConfig(id: String, models: List<String>, contextWindowTokens: Int): ProviderConfig = ProviderConfig(
        id = id,
        name = name.trim(),
        apiType = apiType,
        transportKind = transportKind,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        models = models,
        defaultModel = defaultModel.takeIf { it in models } ?: models.first(),
        isEnabled = isEnabled,
        contextWindowTokens = contextWindowTokens,
        reasoningPreference = reasoningPreference
    )
}

/** An entry that cannot run yet becomes enabled once the form completes it. */
internal fun ProviderConfig.toEditState(): ProviderEditUiState {
    val editableModels = models.ifEmpty { listOf(defaultModel).filter { it.isNotBlank() } }
    return ProviderEditUiState(
        name = name,
        apiType = apiType,
        transportKind = resolvedTransportOrNull(),
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = editableModels,
        defaultModel = defaultModel.takeIf { it in editableModels } ?: editableModels.firstOrNull().orEmpty(),
        isEnabled = isEnabled || apiKey.isBlank() || !isTransportConfigured(),
        reasoningPreference = reasoningPreference
    )
}

enum class ProviderFormError {
    NAME_REQUIRED, URL_REQUIRED, INVALID_URL, PROTOCOL_REQUIRED, KEY_REQUIRED, MODEL_REQUIRED
}
