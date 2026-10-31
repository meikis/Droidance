// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.automation.AutomationAlarmPrecision
import com.droidance.automation.AutomationCadence
import com.droidance.automation.AutomationDefinition
import com.droidance.automation.AutomationManager
import com.droidance.automation.AutomationSchedule
import com.droidance.automation.AutomationStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

enum class AutomationDraftError {
    INCOMPLETE_REQUEST,
    MODEL_UNAVAILABLE,
    INVALID_RESPONSE,
    SAVE_FAILED,
    ACTION_FAILED
}

@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val store: AutomationStore,
    private val manager: AutomationManager,
    private val draftInterpreter: AutomationDraftInterpreter
) : ViewModel() {
    val definitions: StateFlow<List<AutomationDefinition>> = store.definitions
    val occurrences = store.occurrences

    private val _draft = MutableStateFlow<AutomationDraft?>(null)
    val draft: StateFlow<AutomationDraft?> = _draft.asStateFlow()

    private val _isGeneratingDraft = MutableStateFlow(false)
    val isGeneratingDraft: StateFlow<Boolean> = _isGeneratingDraft.asStateFlow()
    private var generationJob: Job? = null
    private var generationVersion = 0L

    private val _draftError = MutableStateFlow<AutomationDraftError?>(null)
    val draftError: StateFlow<AutomationDraftError?> = _draftError.asStateFlow()
    private val _failure = MutableStateFlow<UiOperationFailure?>(null)
    val failure: StateFlow<UiOperationFailure?> = _failure.asStateFlow()

    fun generateDraft(instruction: String, previousDraft: AutomationDraft? = null, answer: String? = null) {
        if (instruction.isBlank() || _isGeneratingDraft.value) return
        _isGeneratingDraft.value = true
        _draftError.value = null
        _failure.value = null
        val version = ++generationVersion
        generationJob = viewModelScope.launch {
            try {
                val generated = draftInterpreter.interpret(instruction, previousDraft = previousDraft, answer = answer)
                if (version != generationVersion) return@launch
                _draft.value = generated?.copy(editingAutomationId = previousDraft?.editingAutomationId,
                    originalRequest = previousDraft?.originalRequest ?: instruction.trim())
                _draftError.value = null
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (version != generationVersion) return@launch
                _failure.value = UiOperationFailure.from(error).let {
                    if (error is InvalidAutomationDraftResponse) it.copy(messageRes = com.droidance.R.string.automation_draft_invalid_response) else it
                }
                _draftError.value = if (error is InvalidAutomationDraftResponse) AutomationDraftError.INVALID_RESPONSE else AutomationDraftError.MODEL_UNAVAILABLE
            } finally {
                if (version == generationVersion) _isGeneratingDraft.value = false
            }
        }
    }

    fun startManualDraft(instruction: String) {
        val now = ZonedDateTime.now()
        _draftError.value = null
        _failure.value = null
        _draft.value = AutomationDraft(
            title = "",
            instruction = instruction.trim(),
            schedule = AutomationSchedule(
                cadence = AutomationCadence.ONCE,
                runAtMs = now.plusMinutes(5).toInstant().toEpochMilli(),
                hour = now.hour,
                minute = now.minute,
                timeZoneId = now.zone.id
            ),
            titleWasGenerated = false,
            originalRequest = instruction.trim()
        )
    }

    fun edit(definition: AutomationDefinition) {
        _draftError.value = null
        _failure.value = null
        _draft.value = AutomationDraft(
            title = definition.title,
            instruction = definition.instruction,
            schedule = if (definition.finishedAtMs > 0L && definition.schedule.cadence in setOf(AutomationCadence.ONCE, AutomationCadence.INTERVAL)) {
                val start = System.currentTimeMillis() +
                    if (definition.schedule.cadence == AutomationCadence.INTERVAL) definition.schedule.intervalMinutes * 60_000L else 300_000L
                definition.schedule.copy(runAtMs = start, endAtMs = if (definition.schedule.endAtMs > 0L)
                    start + (definition.schedule.endAtMs - definition.schedule.runAtMs) else 0L)
            } else definition.schedule,
            titleWasGenerated = definition.titleWasGenerated,
            editingAutomationId = definition.id,
            originalRequest = definition.originalRequest
        )
    }

    fun dismissDraft() {
        generationVersion++
        generationJob?.cancel()
        _isGeneratingDraft.value = false
        _draft.value = null
        _draftError.value = null
        _failure.value = null
    }

    fun clearDraftError() {
        _draftError.value = null
        _failure.value = null
    }

    fun saveDraft(
        title: String,
        instruction: String,
        schedule: AutomationSchedule
    ) {
        val currentDraft = _draft.value ?: return
        try {
            if (currentDraft.editingAutomationId == null) {
                manager.create(title, instruction, currentDraft.titleWasGenerated && title == currentDraft.title,
                    schedule, originalRequest = currentDraft.originalRequest)
            } else {
                checkNotNull(manager.update(currentDraft.editingAutomationId, title, instruction,
                    schedule, originalRequest = currentDraft.originalRequest))
            }
            _draft.value = null
            _draftError.value = null
            _failure.value = null
        } catch (error: Exception) {
            _failure.value = UiOperationFailure.from(error)
            _draftError.value = AutomationDraftError.SAVE_FAILED
        }
    }

    private fun changePlan(action: () -> Unit) {
        runCatching(action).onSuccess { _draftError.value = null; _failure.value = null }
            .onFailure { _failure.value = UiOperationFailure.from(it); _draftError.value = AutomationDraftError.ACTION_FAILED }
    }

    fun setEnabled(id: String, enabled: Boolean) = changePlan { manager.setEnabled(id, enabled) }
    fun runNow(id: String) = changePlan { checkNotNull(manager.runNow(id)) }
    fun delete(id: String) = changePlan { manager.delete(id) }
    fun retryDelivery(occurrenceId: String) = changePlan { manager.retryDelivery(occurrenceId) }

    fun alarmPrecision(): AutomationAlarmPrecision = manager.alarmPrecision()
}
