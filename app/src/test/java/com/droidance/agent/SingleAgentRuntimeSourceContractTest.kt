// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleAgentRuntimeSourceContractTest {
    @Test
    fun cancellationDoesNotReuseTakeoverInstructions() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val cancellation = runtime.substringAfter("private suspend fun buildCancelledTaskSummary(")
            .substringBefore("private suspend fun recentConversationContext")
        assertFalse(cancellation.contains("_userInteractionRequest"))
        assertTrue(cancellation.contains("lastCancellationOrigin?.contains(\"stop\") == true"))
        assertTrue(cancellation.contains("R.string.execution_stopped_by_user"))
        assertTrue(cancellation.contains("R.string.execution_cancelled"))
        assertTrue(cancellation.contains("ConversationEntryKind.ASSISTANT_USER_INTERVENTION"))
        assertTrue(cancellation.contains("detail.isBlank() -> statusText"))
        assertFalse(cancellation.contains("\"\$detail\\n\\n\$statusText\""))
    }

    @Test
    fun ingressCreatesImmutableRequestAndUsesOneOperatorLoop() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val hotPath = runtime.substringAfter("private suspend fun processInputInternal(")
            .substringBefore("private fun resolveInternalTerminalMarker(")

        assertTrue(runtime.contains("TurnRequest.create("))
        assertTrue(hotPath.contains("request: TurnRequest"))
        assertFalse(hotPath.contains("TaskRequest.create("))
        assertTrue(hotPath.contains("diagnostics.startTaskDiagnostics(request.requestId)"))
        assertTrue(hotPath.contains("runUiTask("))
        assertFalse(hotPath.contains("runtimeIntentRouter.route("))
        assertFalse(hotPath.contains("unifiedIntentAgent.analyzeStrict("))
        assertFalse(hotPath.contains("resolveOperatorTaskContract("))
        assertFalse(hotPath.contains("buildTaskStartAcknowledgement("))
        assertFalse(hotPath.contains("toolExecutor.beginExecutionContext("))
        assertFalse(hotPath.contains("ensureVisionCapabilityKnown("))
        assertFalse(hotPath.contains("getProbedVisionCapability("))
        assertFalse(hotPath.contains("setProbedVisionCapability("))
    }

    @Test
    fun runtimeCallsTheModelLoopDirectlyWithoutFakeAgentHandoffOrPlanShells() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val operator = com.droidance.agent.coordinationSources()
        val sourceRoot = projectFile("app/src/main/java/com/droidance/agent")

        assertTrue(runtime.contains("taskCoordinator.coordinateTask("))
        assertTrue(runtime.contains("additionalContext = stepInstruction"))
        assertFalse(runtime.contains("AgentMessageBus"))
        assertFalse(runtime.contains("TaskStep"))
        assertFalse(runtime.contains("TaskTimeline"))
        assertFalse(operator.contains("TaskStep"))
        assertFalse(operator.contains("TaskTimeline"))
        assertFalse(sourceRoot.resolve("AgentMessageBus.kt").exists())
        assertFalse(sourceRoot.resolve("timeline/TaskTimeline.kt").exists())
    }

    @Test
    fun userTurnRuntimeOwnsTheOperatorLifecycleWithoutAPhaseADecision() {
        val stateSource = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/AgentLoopState.kt").readText()
        val enumBody = stateSource.substringAfter("enum class AgentLoopState {").substringBefore("}")
        val states = enumBody.lines().map { it.trim().trimEnd(',') }.filter { it.isNotBlank() }
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        assertTrue(
            states == listOf("ACTIVATING_ANDROID", "PHASE_B", "WAITING_HUMAN", "COMPLETED")
        )
        states.forEach { state ->
            assertTrue(runtime.contains("AgentLoopState.$state"))
        }
        assertFalse(runtime.contains("AgentLoopState.PHASE_A"))
        assertFalse(runtime.contains("taskSession.decide("))
    }

    @Test
    fun operatorBeginsTheDurableRequestBeforeBindingSelectedDeviceCalls() {
        val operator = com.droidance.agent.coordinationSources()
        val session = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundSession.kt").readText()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val request = sourceSection(operator, "val roundTimeoutMs =",
            "reviewRequestCompleted = true")
        val begin = request.indexOf("beginDurableRound(repairPurpose)")
        val collect = request.indexOf("operatorStreamCollector.collect(")
        assertTrue(begin >= 0 && collect > begin)
        assertTrue(session.contains("pending = coordinator.begin("))
        assertFalse(request.contains("ensureDurableExecutionBinding("))
        assertFalse(request.contains("onPrepareDevice()"))
        assertFalse(runtime.contains("taskSession.decide("))
        assertTrue(runtime.contains("val uiResult = runUiTask("))
    }

    @Test
    fun operatorCommitsTheChatRoundBeforePublishingItsDirectReply() {
        val operator = com.droidance.agent.coordinationSources()
        val session = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorRoundSession.kt").readText()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val commit = operator.indexOf("val durableCommit = commitDurableRound(")
        val directBranch = operator.indexOf("// A tool-free reply remains conversational after a retry or user steer.")
        val publish = operator.indexOf("streamFallbackContent(fullContent)", directBranch)
        assertTrue(commit >= 0 && directBranch > commit && publish > directBranch)
        assertTrue(session.contains("if (result.publicStartCommitted) markPublicStartCommitted()"))
        val chat = sourceSection(operator, "// A tool-free reply remains conversational after a retry or user steer.",
            "// Once tools have been attempted")
        assertTrue(chat.contains("!executionStarted"))
        assertTrue(chat.contains("totalToolCalls == 0"))
        assertTrue(chat.contains("terminal = TurnTerminal.DIRECT_REPLY"))
        assertFalse(chat.contains("ensureExecutionStarted("))
        assertFalse(runtime.contains("crossPhaseABoundary("))
    }

    @Test
    fun missingTerminalTextNeverStartsASecondModelRequest() {
        val operator = com.droidance.agent.coordinationSources()
        val router = projectFile("core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()
        assertFalse(operator.contains("requestFinalReplyRepair"))
        assertFalse(operator.contains("RepairPurpose.FINAL_REPLY"))
        assertFalse(router.contains("resetResponsesSessionForFinalReply"))
    }

    @Test
    fun deviceInitializationUsesTheFrozenModelRoundBinding() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val runUiTask = runtime.substringAfter("private suspend fun runUiTask(")
            .substringBefore("private fun ensureLocalUiAutomationResidency(")
        val beforeOperator = runUiTask.substringBefore("taskCoordinator.coordinateTask(")
        val operatorCall = runUiTask.substringAfter("taskCoordinator.coordinateTask(")
            .substringBefore("materializeOperatorEvidence(")

        assertFalse(beforeOperator.contains("ensureLocalUiAutomationResidency()"))
        assertFalse(beforeOperator.contains("toolExecutor.preloadToolsByIntent("))
        assertFalse(beforeOperator.contains("toolExecutor.execute("))
        assertFalse(beforeOperator.contains("experienceResolver.resolve("))
        assertFalse(beforeOperator.contains("tryRunExperienceScript("))
        assertTrue(operatorCall.contains("onExecutionStarted"))
        assertTrue(operatorCall.contains("durableTurn = durableTurn"))
        assertTrue(operatorCall.contains("executionStrategy = executionStrategy"))
        assertFalse(operatorCall.contains("toolExecutor.beginExecutionContext(committedStrategy)"))
        assertFalse(operatorCall.contains("firstToolName in ToolCatalog.mainScreenNativeToolNames"))
        assertTrue(
            com.droidance.agent.coordinationSources()
                .contains("toolExecutor.beginFrozenExecutionContext")
        )
    }

    @Test
    fun taskEntryRefreshesTransientCapabilitiesBeforeFreezingTheStrategy() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val registry = projectFile("app/src/main/java/com/droidance/agent/RuntimeCapabilityRegistry.kt").readText()
        val rules = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertTrue(runtime.contains("capabilityRegistry.currentStrategy()"))
        assertTrue(runtime.contains("capabilityRegistry.awaitTaskStrategy("))
        val initialPrefix = runtime.substringAfter("initialPrefix = {").substringBefore("unfinishedNotice = {")
        assertFalse(initialPrefix.contains("awaitTaskStrategy()"))
        val prepareExecution = runtime.substringAfter("prepareExecution = {").substringBefore("val effectiveTerminal")
        assertTrue(prepareExecution.contains("awaitTaskStrategy("))
        assertTrue(prepareExecution.contains("space = frozenExecutionStrategy.spaceSelection.requestedSpace"))
        assertTrue(registry.contains("suspend fun awaitTaskStrategy(space: ExecutionSpace = requestedSpace())"))
        assertTrue(registry.contains("currentStrategy(refresh(force = true), space)"))
        assertTrue(registry.contains("taskEntryCapabilityWaitMs"))
        assertTrue(registry.contains("RuntimeMode.STANDARD -> airModePairingManager.isPreviouslyPaired"))
        assertTrue(registry.contains("else -> false"))
        assertTrue(rules.contains("task_entry_capability_wait_ms"))
        assertTrue(rules.contains("route_entry_settle_timeout_ms"))
    }

    @Test
    fun taskCompletionNeverRelaunchesDroidanceAfterAnyTool() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        assertFalse(runtime.contains("didLeaveApp"))
        assertFalse(runtime.contains("returnToApp"))
        assertFalse(runtime.contains("launchHostActivity"))
        assertFalse(runtime.contains("startActivity"))
    }

    @Test
    fun recoveryObservationUsesUniqueDurableCallIdentity() {
        val operator = com.droidance.agent.coordinationSources()

        assertTrue(operator.contains("id = \"auto_refresh_"))
        assertTrue(operator.contains("java.util.UUID.randomUUID()"))
        assertFalse(operator.contains("ToolCall(\"auto_refresh\", \"observe_screen\", \"{}\")"))
    }

    @Test
    fun runtimeHasNoHiddenLegacyExecutionPaths() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        listOf(
            "maybeHandleBlockingOverlay(",
            "ensureTargetAppForeground(",
            "tryRunExperienceScript(",
            "ToolCall(\"auto_refresh_after_blocker\", \"screenshot\"",
            "ToolCall(id = \"experience_script\", name = \"run_script\"",
            "AUTO_PERCEPTION_TOOLS",
            "DIRECT_NATIVE_TOOLS"
        ).forEach { legacyPath ->
            assertFalse("Runtime should not retain hidden execution path: $legacyPath", runtime.contains(legacyPath))
        }
    }

    @Test
    fun messageSourceIsExplicitAndNeverStoredAsMutableRuntimeState() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val automation = projectFile(
            "app/src/main/java/com/droidance/automation/AutomationCoordinator.kt"
        ).readText()

        val directEntry = runtime.substringAfter("override suspend fun processConversationInput(")
            .substringBefore("data class RuntimeTurnOutcome")
        assertTrue(directEntry.contains("request: TurnRequest"))
        assertFalse(directEntry.contains("TurnRequest.create("))
        assertFalse(runtime.contains("MessageSource.SCRIPT_PATROL"))
        assertTrue(runtime.contains("source = MessageSource.SCHEDULED"))
        assertTrue(automation.contains("agentRuntime.processAutomationInput("))
        assertFalse(runtime.contains("currentMessageSource"))
        assertFalse(runtime.contains("setMessageSource("))
        assertFalse(runtime.contains("lastDeviceTaskInput"))
        assertFalse(runtime.contains("lastDeviceTaskContract"))
    }

    @Test
    fun publicFallbackLookupIsLocalAndDoesNotCallAnotherModel() {
        val rules = projectFile("app/src/main/java/com/droidance/config/RuleConfigManager.kt").readText()

        assertFalse(rules.contains("llmRouter.chat("))
    }

    private fun sourceSection(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue("Missing source boundary: $start", startIndex >= 0)
        val contentStart = startIndex + start.length
        val endIndex = source.indexOf(end, contentStart)
        assertTrue("Missing source boundary: $end", endIndex >= contentStart)
        return source.substring(contentStart, endIndex)
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
