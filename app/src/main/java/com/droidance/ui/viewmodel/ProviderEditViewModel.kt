// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.droidance.settings.ProviderConfigManager
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
            val models = config.models.ifEmpty {
                listOf(config.defaultModel).filter { it.isNotBlank() }
            }
            val defaultModel = config.defaultModel.takeIf { it in models }
                ?: models.firstOrNull().orEmpty()
            _uiState.value = ProviderEditUiState(
                name = config.name,
                apiType = config.apiType,
                transportKind = config.transportKind,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                models = models,
                defaultModel = defaultModel,
                isEnabled = config.isEnabled || config.apiKey.isBlank(),
                reasoningPreference = config.reasoningPreference
            )
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
        val existing = editingConfig
        if (state.isEnabled && existing != null && !existing.isTransportConfigured()) {
            _uiState.update { it.copy(saveFailed = true) }
            return false
        }
        val defaultModel = state.defaultModel.takeIf { it in models } ?: models.first()
        val config = ProviderConfig(
            id = editingId ?: UUID.randomUUID().toString(),
            name = state.name.trim(),
            apiType = state.apiType,
            transportKind = state.transportKind,
            transportDiagnostic = existing?.transportDiagnostic,
            baseUrl = state.baseUrl.trim(),
            apiKey = state.apiKey.trim(),
            models = models,
            defaultModel = defaultModel,
            isEnabled = state.isEnabled,
            contextWindowTokens = existing?.contextWindowTokens ?: 128_000,
            reasoningPreference = state.reasoningPreference
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
    val validationError: ProviderFormError?
        get() = when {
            name.isBlank() -> ProviderFormError.NAME_REQUIRED
            baseUrl.isBlank() -> ProviderFormError.URL_REQUIRED
            !runCatching {
                val uri = java.net.URI(baseUrl.trim())
                uri.scheme?.lowercase(java.util.Locale.ROOT) in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
            }.getOrDefault(false) -> ProviderFormError.INVALID_URL
            apiKey.isBlank() -> ProviderFormError.KEY_REQUIRED
            models.all { it.isBlank() } && modelInput.split(',', '，', ';', '；', '、', '\n', '\r', '\t').all { it.isBlank() } -> ProviderFormError.MODEL_REQUIRED
            else -> null
        }
}

enum class ProviderFormError {
    NAME_REQUIRED, URL_REQUIRED, INVALID_URL, KEY_REQUIRED, MODEL_REQUIRED
}
