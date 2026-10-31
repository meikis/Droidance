// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.droidance.settings.ProviderConfigManager
import com.droidance.core.model.llm.models.ProviderConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Provider 列表页 ViewModel
 */
@HiltViewModel
class ProviderListViewModel @Inject constructor(
    private val configManager: ProviderConfigManager
) : ViewModel() {

    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val providers: StateFlow<List<ProviderConfig>> = _providers

    init {
        refreshList()
    }

    fun refreshList() {
        _providers.value = configManager.getAllConfigs()
    }

    private val _actionFailed = MutableStateFlow(false)
    val actionFailed: StateFlow<Boolean> = _actionFailed
    fun clearError() { _actionFailed.value = false }
    private fun change(action: () -> Unit): Boolean {
        val succeeded = runCatching(action).isSuccess
        _actionFailed.value = !succeeded
        refreshList()
        return succeeded
    }

    /** 切换 Provider 启用/禁用 */
    fun toggleProvider(id: String) {
        val config = configManager.getConfig(id) ?: return
        change { configManager.updateConfig(config.copy(isEnabled = !config.isEnabled)) }
    }

    val defaultProviderId: String? get() = configManager.getDefaultConfig()?.id

    fun makeDefault(id: String) {
        change { configManager.setDefaultProvider(id) }
    }

    /** 删除 Provider */
    fun deleteProvider(id: String) {
        configManager.deleteConfig(id)
        refreshList()
    }

    /** 手动切换当前生效模型（更新 defaultModel 并重置自动降级状态） */
    fun selectModel(id: String, model: String) {
        val config = configManager.getConfig(id) ?: return
        if (model !in config.models || model == config.defaultModel) return
        if (change { configManager.updateConfig(config.copy(defaultModel = model)) }) {
            configManager.resetModelFallback(id)
        }
    }
}
