// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.agent.AgentRuntime
import com.droidance.agent.AgentWorkspaceStore
import com.droidance.core.knowledge.memory.LongTermMemoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val agentRuntime: AgentRuntime,
    private val agentWorkspaceStore: AgentWorkspaceStore,
    private val longTermMemoryStore: LongTermMemoryStore
) : ViewModel() {

    data class AgentInfo(
        val name: String,
        val soul: String,
        val memory: String,
        val isActive: Boolean
    )

    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents

    /** 当前正在编辑的 Agent 名称（null = 未展开任何详情） */
    private val _editingAgent = MutableStateFlow<String?>(null)
    val editingAgent: StateFlow<String?> = _editingAgent

    /** 编辑中的 SOUL 内容 */
    private val _editingSoul = MutableStateFlow("")
    val editingSoul: StateFlow<String> = _editingSoul

    /** 是否正在保存 */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving
    private var savedSoul = ""
    private val _saveFailure = MutableStateFlow<UiOperationFailure?>(null)
    val saveFailure: StateFlow<UiOperationFailure?> = _saveFailure
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    init {
        loadAgents()
    }

    fun loadAgents() {
        val activeAgent = agentRuntime.getActiveAgent()
        val names = agentWorkspaceStore.listAgents().ifEmpty { listOf("default") }
        _agents.value = names.map { name ->
            AgentInfo(
                name = name,
                soul = agentWorkspaceStore.readSoul(name).take(120) + "…",
                memory = longTermMemoryStore.read(name).let {
                    it.take(60).let { text -> if (text.isBlank()) "" else "$text…" }
                },
                isActive = name == activeAgent
            )
        }
    }

    /** 切换当前激活的 Agent */
    fun switchAgent(name: String) {
        agentRuntime.switchAgent(name)
        loadAgents()
    }

    /** 展开 Agent 详情编辑 */
    fun openEdit(name: String) {
        _editingAgent.value = name
        _editingSoul.value = agentWorkspaceStore.readSoul(name)
        savedSoul = _editingSoul.value
        _saveFailure.value = null
        _saved.value = false
    }

    fun onSoulChanged(text: String) {
        _editingSoul.value = text
        _saved.value = false
        _saveFailure.value = null
    }

    /** 保存 SOUL.md 修改 */
    fun saveSoul() {
        val name = _editingAgent.value ?: return
        if (_isSaving.value) return
        val text = _editingSoul.value
        _isSaving.value = true
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { agentWorkspaceStore.writeSoul(text, name) }
                savedSoul = text
                _saved.value = true
                _saveFailure.value = null
                loadAgents()
            } catch (error: Exception) {
                _saveFailure.value = UiOperationFailure.from(error)
            } finally { _isSaving.value = false }
        }
    }

    fun hasUnsavedChanges(): Boolean = _editingSoul.value != savedSoul

    fun closeEdit() {
        _editingAgent.value = null
    }

}
