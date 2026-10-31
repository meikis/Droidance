// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import androidx.core.content.ContextCompat

import android.content.Context
import android.util.Log
import com.droidance.R
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.PublicAssistantText
import com.droidance.core.agent.di.ApplicationScope
import com.droidance.daemon.DeviceExecutionGuard
import com.droidance.daemon.TaskExecutionService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Serial coordinator for scheduled occurrences and their independent delivery outbox. */
@Singleton
class AutomationCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val store: AutomationStore,
    private val manager: AutomationManager,
    private val agentRuntime: AgentRuntime,
    private val deviceExecutionGuard: DeviceExecutionGuard
) {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val recovered = store.recoverInterruptedOccurrences()
        if (recovered > 0) {
            Log.w(TAG, "Recovered $recovered interrupted automation occurrences as unknown")
        }
        appScope.launch(Dispatchers.Default) { schedulerLoop() }
        appScope.launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive) {
                val observed = store.changeVersion.value
                runCatching { manager.dispatchDue() }.onFailure { Log.e(TAG, "Deadline dispatch failed", it) }
                val next = store.definitions.value.filter { it.enabled && it.nextRunAtMs > 0L }
                    .minOfOrNull { it.nextRunAtMs }
                val waitMs = next?.minus(System.currentTimeMillis())?.coerceIn(250L, MAX_IDLE_WAIT_MS)
                    ?: MAX_IDLE_WAIT_MS
                withTimeoutOrNull(waitMs) { store.changeVersion.first { it != observed } }
            }
        }
    }

    private suspend fun schedulerLoop() {
        while (currentCoroutineContext().isActive) {
            val delivery = store.nextDelivery()
            if (delivery != null) {
                deliver(delivery)
                continue
            }
            if (agentRuntime.state.value != AgentState.IDLE) {
                withTimeoutOrNull(1_000L) { agentRuntime.state.first { it == AgentState.IDLE } }
                continue
            }
            val occurrence = store.claimNextOccurrence()
            if (occurrence != null) {
                execute(occurrence)
                continue
            }
            waitForWork()
        }
    }

    private suspend fun execute(occurrence: AutomationOccurrence) {
        if (occurrence.instructionSnapshot.isBlank()) {
            store.completeOccurrence(
                id = occurrence.id,
                executionState = AutomationExecutionState.UNKNOWN,
                resultStatus = "unknown",
                resultSummary = "",
                failureCode = "DEFINITION_MISSING"
            )
            return
        }

        val l10n = ContextCompat.getContextForLanguage(context)
        try {
            TaskExecutionService.startExecution(context, occurrence.titleSnapshot)
            val turn = agentRuntime.processAutomationInput(
                userInput = occurrence.instructionSnapshot,
                conversationId = occurrence.threadId,
                prepareDevice = {
                    val preparation = deviceExecutionGuard.prepareForRemoteExecution(occurrence.titleSnapshot.take(80))
                    AutoCloseable { deviceExecutionGuard.finishRemoteExecution(preparation, occurrence.titleSnapshot.take(80)) }
                },
                onTurnStarted = { scope ->
                    store.bindTurn(occurrence.id, scope.turnId)
                    TaskExecutionService.notifyAutomationStarted(context, occurrence.titleSnapshot)
                }
            )
            val status = turn.status.trim().lowercase()
            val summary = PublicAssistantText.sanitize(turn.response)
            val executionState = when {
                status == "done" -> AutomationExecutionState.SUCCEEDED
                status == "need_more_info" -> AutomationExecutionState.WAITING_FOR_USER
                status == "failed" || status == "error" ->
                    AutomationExecutionState.FAILED
                else -> AutomationExecutionState.UNKNOWN
            }
            store.completeOccurrence(
                id = occurrence.id,
                executionState = executionState,
                resultStatus = status.ifBlank { "unknown" },
                resultSummary = summary,
                failureCode = if (executionState == AutomationExecutionState.UNKNOWN) {
                    "MISSING_TERMINAL_STATUS"
                } else {
                    ""
                }
            )
        } catch (error: Throwable) {
            val preparationFailure = error as? com.droidance.daemon.DevicePreparationException
            val canceled = error is kotlinx.coroutines.CancellationException
            Log.e(TAG, "Automation execution failed id=${occurrence.id}", error)
            store.completeOccurrence(
                id = occurrence.id,
                executionState = if (preparationFailure?.reason?.requiresUser == true) AutomationExecutionState.WAITING_FOR_USER
                    else if (canceled) AutomationExecutionState.UNKNOWN else AutomationExecutionState.FAILED,
                resultStatus = if (preparationFailure?.reason?.requiresUser == true) "need_more_info" else if (canceled) "cancelled" else "failed",
                resultSummary = if (preparationFailure != null) l10n.getString(preparationFailure.reason.messageResource) else l10n.getString(
                    R.string.automation_execution_failed_message,
                    error.message.orEmpty().take(160)
                ),
                failureCode = preparationFailure?.reason?.name ?: if (canceled) "EXECUTION_INTERRUPTED" else "EXECUTION_EXCEPTION"
            )
            if (canceled) throw error
        } finally {
            TaskExecutionService.stopExecution(context)
        }
    }

    private fun deliver(occurrence: AutomationOccurrence) {
        val l10n = ContextCompat.getContextForLanguage(context)
        val text = occurrence.resultSummary.ifBlank {
            l10n.getString(R.string.automation_result_unknown_message)
        }
        val definition = store.definition(occurrence.automationId)
        val title = occurrence.titleSnapshot.ifBlank { definition?.title.orEmpty() }
        val sent = if (occurrence.executionState == AutomationExecutionState.SUCCEEDED) {
            TaskExecutionService.notifyTaskCompleted(context, title, text)
        } else {
            TaskExecutionService.notifyTaskInterrupted(context, title, text)
        }
        if (sent) {
            store.markDeliveryAccepted(occurrence.id)
        } else {
            store.markDeliveryFailed(occurrence.id, "LOCAL_NOTIFICATION_UNAVAILABLE")
        }
    }

    private suspend fun waitForWork() {
        val observedVersion = store.changeVersion.value
        val nowMs = System.currentTimeMillis()
        val waitMs = store.nextDeliveryAtMs()
            ?.minus(nowMs)
            ?.coerceAtLeast(1L)
            ?.coerceAtMost(MAX_IDLE_WAIT_MS)
            ?: MAX_IDLE_WAIT_MS
        withTimeoutOrNull(waitMs) {
            store.changeVersion.first { it != observedVersion }
        }
    }

    private companion object {
        const val TAG = "AutomationCoordinator"
        const val MAX_IDLE_WAIT_MS = 60_000L
    }
}
