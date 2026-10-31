// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.core.content.ContextCompat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.R
import com.droidance.config.LanguageManager
import com.droidance.core.knowledge.skill.SkillEngine
import com.droidance.core.knowledge.skill.SkillInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

data class SkillEditorState(val skillId: String, val name: String, val description: String, val body: String, val revision: String,
    val language: String, val displayName: String, val displayDescription: String)

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skillEngine: SkillEngine,
    private val agentRuntime: com.droidance.agent.AgentRuntime,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _skills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val skills: StateFlow<List<SkillInfo>> = _skills
    private val _editing = MutableStateFlow<SkillEditorState?>(null)
    val editing: StateFlow<SkillEditorState?> = _editing
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage
    private val _operationFailure = MutableStateFlow<UiOperationFailure?>(null)
    val operationFailure: StateFlow<UiOperationFailure?> = _operationFailure
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working
    private var initialEditor: SkillEditorState? = null
    private val _assessments = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val assessments: StateFlow<Map<String, List<String>>> = _assessments
    private val draftPreferences = context.getSharedPreferences("skill-authoring", Context.MODE_PRIVATE)
    private val _autoDraft = MutableStateFlow(draftPreferences.getBoolean("auto_draft", false))
    val autoDraft: StateFlow<Boolean> = _autoDraft
    val draftWorkState = agentRuntime.skillDraftWorkState
    init { loadSkills() }

    fun loadSkills() { viewModelScope.launch {
        try {
            val loaded = withContext(Dispatchers.IO) { skillEngine.loadAllSkills() }
            _skills.value = loaded
            _assessments.value = withContext(Dispatchers.IO) {
                loaded.associate { it.selectionKey to skillEngine.assessments(it.id, it.isDraft) }
            }
        } catch (error: Exception) { reportOperationFailure(error) }
    } }
    fun setAutoDraft(value: Boolean) {
        draftPreferences.edit().putBoolean("auto_draft", value).apply()
        _autoDraft.value = value
        if (!value) agentRuntime.cancelSkillDraftWork()
        else agentRuntime.resumeSkillDraftWork()
    }
    fun startEditing(skill: SkillInfo) {
        val language = LanguageManager.resolvedTag(context)
        if (!skill.isReadOnly) {
            initialEditor = SkillEditorState(skill.id, skill.name, skill.description,
                skill.rawContent, skill.revision, language, skill.displayName(language), skill.displayDescription(language))
            _editing.value = initialEditor
        }
    }
    fun updateEditingName(value: String) { _editing.value = _editing.value?.copy(displayName = value) }
    fun updateEditingSummary(value: String) { _editing.value = _editing.value?.copy(displayDescription = value) }
    fun updateEditingDescription(value: String) { _editing.value = _editing.value?.copy(description = value) }
    fun updateEditingBody(value: String) { _editing.value = _editing.value?.copy(body = value) }
    fun hasUnsavedChanges(): Boolean = _editing.value != initialEditor
    fun cancelEditing() { _editing.value = null; initialEditor = null }
    fun saveEditing() {
        val editor = _editing.value ?: return
        perform { skillEngine.updateSkill(editor.skillId, editor.description, editor.body, editor.revision,
            editor.language, editor.displayName, editor.displayDescription); cancelEditing() }
    }
    fun publish(skill: SkillInfo) {
        // Capture the exact previewed revision before dispatch; a concurrent writer cannot replace it.
        perform { skillEngine.publishDraft(skill.id, skill.revision) }
    }
    fun setAutoInvocation(skill: SkillInfo, allowed: Boolean) = perform { skillEngine.setImplicitInvocation(skill.id, allowed) }
    fun installPackage(input: InputStream) {
        _operationFailure.value = null
        try { skillEngine.installSkillPackage(input); loadSkills(); notice(R.string.skill_draft_saved) }
        catch (error: Exception) { reportOperationFailure(error) }
    }
    fun exportPackage(skill: SkillInfo): ByteArray = skillEngine.exportPackage(skill.id, skill.isDraft)
    fun reportOperationFailure(error: Throwable) { _operationFailure.value = UiOperationFailure.from(error) }
    fun clearOperationFailure() { _operationFailure.value = null }
    fun uninstall(skill: SkillInfo) = perform { skillEngine.deleteSkill(skill.id) }
    fun clearOperationMessage() { _operationMessage.value = null }
    private fun notice(id: Int) { _operationMessage.value = ContextCompat.getContextForLanguage(context).getString(id) }
    private fun perform(block: () -> Unit) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try { _operationFailure.value = null; withContext(Dispatchers.IO) { block() }; loadSkills() }
            catch (error: Exception) { reportOperationFailure(error) }
            finally { _working.value = false }
        }
    }
    fun buildCreationPrompt(description: String): String =
        ContextCompat.getContextForLanguage(context).getString(R.string.skill_create_prompt, description)
    fun buildPresentationPrompt(skill: SkillInfo): String = localizedRequest(
        if (skill.isDraft) R.string.skill_draft_presentation_prompt else R.string.skill_presentation_prompt, skill)
    private fun localizedRequest(id: Int, skill: SkillInfo): String = ContextCompat.getContextForLanguage(context).getString(
        id, skill.displayName(LanguageManager.resolvedTag(context)), skill.name)
    fun buildRecoveryPrompt(failure: UiOperationFailure, context: String = ""): String =
        ContextCompat.getContextForLanguage(this.context).getString(R.string.ui_recovery_request) + "\n\n" +
            kotlinx.serialization.json.buildJsonObject {
                put("context", kotlinx.serialization.json.JsonPrimitive(context))
                put("failure", kotlinx.serialization.json.JsonPrimitive(failure.diagnostic))
            }.toString()
}
