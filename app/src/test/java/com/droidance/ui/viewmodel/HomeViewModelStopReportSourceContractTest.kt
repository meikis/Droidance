// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelStopReportSourceContractTest {
    @Test
    fun manualStopReplacesTransientProcessWithDurablePlainText() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        val cancellationBlock = source.substringAfter("onFailure = { error ->")
            .substringBefore("onFinished =")
        val stopReportBody = source.substringAfter("private fun buildStoppedAssistantText(")
            .substringBefore("\n    private fun friendlyError")

        assertFalse("Cancellation handling should not mine recent process logs for the user-facing stop report", cancellationBlock.contains("buildStoppedProgressSummary"))
        assertTrue(cancellationBlock.contains("existingText = currentAssistantMessage.text"))
        assertTrue(cancellationBlock.contains("runtimeFinalText = agentRuntime.lastResponse.value"))
        assertFalse(cancellationBlock.contains("executionEvents = emptyList()"))
        assertTrue(cancellationBlock.contains("isTransient = false"))
        assertTrue(stopReportBody.contains("PublicAssistantText.sanitize(runtimeFinalText)"))
        assertFalse(stopReportBody.contains("<report"))
        assertTrue(stopReportBody.contains("R.string.execution_stopped_by_user"))
        assertTrue(stopReportBody.contains("R.string.execution_cancelled"))
        assertFalse(stopReportBody.contains("用户已手动停止任务"))
        assertFalse(stopReportBody.contains("currentText"))
    }

    @Test
    fun manualTakeoverUsesTypedRequestsAndDurableProjection() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()

        assertTrue(source.contains("agentRuntime.userInteractionRequest"))
        assertTrue(source.contains("UserInteractionState.PENDING"))
        assertTrue(source.contains("it.conversationId == conversationId"))
        assertFalse(source.contains("persistManualTakeoverMessage()"))
        assertTrue(source.contains("isTransient = false"))
        assertTrue(source.contains("eventStore.observeThreadProjectionResult(conversationId)"))
        assertFalse(source.contains("saveConversation()"))
        assertTrue(source.contains("preservePublicAssistantText"))
    }

    @Test
    fun submissionFailureReplacesTransientProcessWithFinalFailure() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()

        val submission = source.substringAfter("private fun submitConversationRequest(")
            .substringBefore("fun steerCurrentTask(")
        val failure = submission.substringAfter("onFailure = { error ->")
            .substringBefore("onFinished =")
        assertTrue(failure.contains("buildVisibleFailureText(friendlyError(error))"))
        assertTrue(failure.contains("replaceAssistantMessage(currentAssistantMessage)"))
        assertFalse(submission.contains("executionEvents = emptyList()"))
        assertTrue(failure.contains("isTransient = false"))
        assertFalse(submission.contains("val tail = _messages.value.lastOrNull()"))
        assertFalse(source.contains("<report"))
        assertFalse(source.contains("执行日志已保留"))
    }

    @Test
    fun finalAssistantHistoryKeepsTheStreamedOpeningAndAddsTheFinalProjection() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        val submitBlock = source.substringAfter("private fun submitConversationRequest(")
            .substringBefore("\n    fun steerCurrentTask(")

        assertTrue(submitBlock.contains("conversationTasks.submit("))
        assertTrue(submitBlock.contains("onCompleted = { outcome ->"))
        assertTrue(submitBlock.contains("mergeFinalAssistantText(latest.text, outcome.response)"))
        assertTrue(source.contains("conversationTasks.activeTasks"))
        assertTrue(source.contains("_isProcessing.value = submitted.isNotEmpty() || (mine && state != AgentState.IDLE)"))
        assertTrue(source.contains("PublicAssistantText.sanitize(response)"))
        assertFalse(submitBlock.contains("executionEvents = emptyList()"))
        assertTrue(submitBlock.contains("isTransient = false"))
        assertFalse(submitBlock.contains("while (shouldContinue)"))
        assertFalse(submitBlock.contains("appScope.launch"))
        assertFalse(source.contains("shouldAppendReturnedResponse("))
        assertTrue(source.contains("projectConversationMessages(it.conversation.entries,"))
        assertTrue(source.contains("reconcileConversationMessages(current, projected, terminalTurns, active)"))
        assertFalse(source.contains("buildTaskSummary("))
        assertTrue(source.contains("private fun responseAlreadyVisible("))
    }

    @Test
    fun finalBubbleDoesNotDiscardTheFirstModelAuthoredReply() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        val merge = source.substringAfter("private fun mergeFinalAssistantText(")
            .substringBefore("private fun responseAlreadyVisible(")

        assertTrue(merge.contains("PublicAssistantText.sanitize(existingText)"))
        assertTrue(merge.contains("responseAlreadyVisible(existingPublic, finalProjection)"))
        assertTrue(merge.contains("return \"\$existingPublic\\n\\n\$finalProjection\""))
    }

    @Test
    fun durableHistoryUsesPublicEventStoreProjectionInsteadOfProtocolMarkup() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        val projection = projectFile("app/src/main/java/com/droidance/ui/viewmodel/ConversationMessageUpdates.kt").readText()

        assertTrue(projection.contains("PublicAssistantText.sanitize(it.text)"))
        assertFalse(source.contains("ConversationManager"))
        assertFalse(source.contains("processInputForTitle"))
        assertFalse(source.contains("maybeGenerateConversationTitle"))
    }

    @Test
    fun conversationSelectionDoesNotReplayHistoryIntoRuntimeOrOwnASecondJob() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()

        assertTrue(source.contains("conversationTasks.dispatch(conversationId, request, submissionMode, prepareRequest)"))
        assertTrue(source.contains("conversationTasks.submit("))
        assertFalse(source.contains("prepareRuntimeConversationBinding"))
        assertFalse(source.contains("restoreRuntimeConversation"))
        assertFalse(source.contains("agentRuntime.bindConversation"))
        assertFalse(source.contains("agentRuntime.registerActiveJob"))
        assertFalse(source.contains("appScope.launch"))
    }

    @Test
    fun stoppedAssistantTextPreservesExistingOutputAndCombinesTerminalNotice() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        val stopReportBody = source.substringAfter("private fun buildStoppedAssistantText(")
            .substringBefore("\n    private fun friendlyError")

        assertTrue(stopReportBody.contains("preserved.isBlank() -> terminalNotice"))
        assertTrue(stopReportBody.contains("terminalNotice.isBlank() || responseAlreadyVisible(preserved, terminalNotice) -> preserved"))
        assertTrue(stopReportBody.contains("\"\$preserved\\n\\n\$terminalNotice\""))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
