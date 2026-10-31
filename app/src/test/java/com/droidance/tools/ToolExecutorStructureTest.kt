// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolExecutorStructureTest {
    @Test
    fun mutableToolStateIsOwnedByTheBoundTurnContext() {
        val executor = com.droidance.agent.executorSources()
        val turnServices = projectFile("app/src/main/java/com/droidance/tools/ToolTurnServices.kt").readText()
        val taskLifecycle = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/TaskLifecycle.kt"
        ).readText()
        val observations = projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText()
        val taskState = executor.substringAfter("private class TaskState(")
            .substringBefore("private val taskState: TaskState")

        listOf(
            "completionState",
            "harnessUtilityState",
            "actionDeliveryByToolCall",
            "textInputIdempotencyGuard"
        ).forEach { field ->
            assertTrue("TaskState must own mutable turn field: $field", taskState.contains(field))
        }
        assertTrue(executor.contains("toolTurnServices.requireCurrent().sessionContext.getOrPutTyped {"))
        assertTrue(taskState.contains("val observations: ToolObservationSession"))
        assertTrue(executor.contains("TaskState(ToolObservationSession("))
        listOf("observationEnvelopeStore", "pendingScreenStateText", "lastActionEndedAtElapsedMs", "autoCaptureJob")
            .forEach { assertTrue("Observation session must own $it", observations.contains(it)) }
        assertFalse(executor.contains("observationSession::"))
        assertTrue(executor.contains("stagePostActionObservation = { observationSession.stageReusablePostActionFrame(it) }"))
        assertTrue(executor.contains("invocationState.observations.captureSettledAutoFrame(frameWaitMs)"))
        assertTrue(executor.contains("invocationState.observations.publishPreparedVisionFrame("))
        assertFalse(executor.contains("activeSessionRef"))
        assertFalse(executor.contains("globalTaskState"))
        assertTrue(turnServices.contains("if (active === binding)"))
        assertFalse(turnServices.contains("sessionContext.clear()"))
        assertTrue(taskLifecycle.contains("cleanup { releaseResources(active) }"))
        assertTrue(taskLifecycle.contains("cleanup { active.context.clear() }"))
    }

    @Test
    fun retiredDeepLinkStackIsInternalizedByAppLaunchService() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val executor = com.droidance.agent.executorSources()
        val service = toolsDir.resolve("AppLaunchService.kt").readText()

        listOf("DeepLinkToolRegistrar.kt", "DeepLinkCatalogQuery.kt").forEach { retiredFile ->
            assertFalse(toolsDir.resolve(retiredFile).exists())
        }
        assertFalse(projectFile("app/src/main/java/com/droidance/debug").resolve("DeepLinkDiagnostics.kt").exists())
        assertFalse(executor.contains("DeepLinkToolRegistrar"))
        assertFalse(executor.contains("lastLaunchedDeepLink"))
        assertFalse(executor.contains("deepLinkTaskStateResetter"))
        assertTrue(service.contains("deepLinkKnowledgeStore.resolve("))
    }

    @Test
    fun appLaunchIsAnInternalServiceBehindAndroidAct() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val executor = com.droidance.agent.executorSources()
        val service = toolsDir.resolve("AppLaunchService.kt").readText()

        assertFalse(toolsDir.resolve("AppLaunchToolRegistrar.kt").exists())
        assertFalse(service.contains("registerTool"))
        assertFalse(service.contains("override val name = \"open_app\""))
        assertTrue(executor.contains("val appLaunchService = AppLaunchService("))
        assertTrue(executor.contains("openAndroid = appLaunchService::open"))
    }

    @Test
    fun unconstructedCommittedExecutionStackIsRemoved() {
        val harnessDir = projectFile("app/src/main/java/com/droidance/agent/harness")
        listOf(
            "CommittedCoreDeviceToolHandlers.kt",
            "CommittedCoreLocalToolHandlers.kt",
            "CommittedCoreToolHandlers.kt",
            "CommittedDeviceExecutionBackends.kt",
            "CommittedToolHandlerRegistry.kt",
            "VerifiedCommittedDeviceToolHandler.kt"
        ).forEach { retiredFile ->
            assertFalse(harnessDir.resolve(retiredFile).exists())
        }
        assertTrue(projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/harness/DurableModelRoundCoordinator.kt").exists())
        assertTrue(projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/harness/ModelRoundContract.kt").exists())
        assertFalse(harnessDir.resolve("CommittedRoundExecutor.kt").exists())
        assertTrue(projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/harness/DetailAttachmentMaterializer.kt").exists())
    }

    @Test
    fun retiredScreenRegistrarsDoNotCreateParallelObservationTools() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val executor = com.droidance.agent.executorSources()
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()

        listOf(
            "ScreenCaptureToolRegistrar.kt",
            "ScreenStateToolRegistrar.kt",
            "SurfaceFlingerParser.kt"
        ).forEach { retiredFile ->
            assertFalse(toolsDir.resolve(retiredFile).exists())
        }
        listOf(
            "observe_screen", "screenshot", "crop_zoom", "read_screen",
            "get_foreground_app", "verify_element", "get_screen_state", "perceive", "sf_layers"
        ).forEach { retired ->
            assertFalse(catalog.contains("\"$retired\""))
        }
        assertFalse(executor.contains("ScreenCaptureToolRegistrar"))
        assertFalse(executor.contains("ScreenStateToolRegistrar"))
        assertFalse(executor.contains("SurfaceFlingerParser"))
        assertTrue(executor.contains("private suspend fun readBoundScreen()"))
        assertTrue(executor.contains("private suspend fun readBoundCrop(arguments: String)"))
    }

    @Test
    fun skillExecutionUsesTheTaskLocalProgramWithoutLegacyScriptEntrypoints() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val registrar = toolsDir.resolve("SkillToolRegistrar.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val taskRequest = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TurnRequest.kt").readText()
        val prompts = projectFile("app/src/main/assets/rules/utility_prompts.md").readText()

        assertFalse(toolsDir.resolve("ScriptToolRegistrar.kt").exists())
        assertFalse(projectFile("core/knowledge/src/main/kotlin/com/droidance/agent").resolve("ScriptExecutor.kt").exists())
        assertTrue(executor.contains("SkillToolRegistrar("))
        assertTrue(executor.contains("runProgram = programTool::executeSkill"))
        listOf("verify_skill", "run_skill").forEach { exposed ->
            assertTrue(registrar.contains("tool(\"$exposed\","))
        }
        listOf("compile_recipe", "run_script", "list_scripts").forEach { retired ->
            assertFalse(registrar.contains(retired))
        }
        assertFalse(executor.contains("scriptPatrolCallback"))
        assertFalse(runtime.contains("SCRIPT_PATROL"))
        assertFalse(taskRequest.contains("SCRIPT_PATROL"))
        assertFalse(prompts.contains("script_patrol_prompt"))
    }

    @Test
    fun notificationRegistrarOnlyRegistersCataloguedBackgroundTools() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val registrar = toolsDir.resolve("NotificationToolRegistrar.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()
        val registerAll = registrar.substringAfter("fun registerAll()")
            .substringBefore("private fun registerReadNotifications")

        listOf(
            "registerReadNotifications()",
            "registerClickNotification()",
            "registerDismissNotification()",
            "registerManageAppTakeover()"
        ).forEach { registration ->
            assertTrue(registerAll.contains(registration))
        }
        listOf(
            "read_notifications", "click_notification",
            "dismiss_notification", "manage_app_takeover"
        ).forEach { exposed ->
            assertTrue(catalog.contains("\"$exposed\""))
        }
        listOf("manage_files", "send_channel_image", "send_channel_file").forEach { retired ->
            assertFalse(registrar.contains(retired))
            assertFalse(catalog.contains("\"$retired\""))
        }
        assertFalse(registrar.contains("ChannelManager"))
        assertFalse(executor.contains("ChannelManager"))
    }

    @Test
    fun retiredIntentFileToolsAreNotRegisteredOrExposed() {
        val executor = com.droidance.agent.executorSources()
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertFalse(projectFile("app/src/main/java/com/droidance/tools").resolve("IntentFileToolRegistrar.kt").exists())
        assertFalse(executor.contains("IntentFileToolRegistrar"))
        listOf(
            "set_timer", "create_calendar_event", "share_text", "navigate_to",
            "open_url", "download_file", "push_file", "pull_file"
        ).forEach { retired ->
            assertFalse(catalog.contains("\"$retired\""))
        }
        assertFalse(policy.contains("- set_timer"))
    }

    @Test
    fun retiredDeviceControlToolsAreNotRegisteredOrExposed() {
        val executor = com.droidance.agent.executorSources()
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()
        val policy = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertFalse(projectFile("app/src/main/java/com/droidance/tools").resolve("DeviceControlToolRegistrar.kt").exists())
        assertFalse(executor.contains("DeviceControlToolRegistrar"))
        listOf(
            "toggle_wifi", "toggle_bluetooth", "toggle_airplane", "toggle_dnd",
            "toggle_rotation", "toggle_flashlight", "set_brightness", "set_volume",
            "set_screen_timeout", "screen_on", "screen_off", "kill_app", "get_battery",
            "get_running_apps", "install_apk", "grant_permission"
        ).forEach { retired ->
            assertFalse(catalog.contains("\"$retired\""))
        }
        assertFalse(policy.contains("- kill_app"))
        assertFalse(policy.contains("- toggle_airplane"))
    }

    @Test
    fun retiredSystemShellToolsAreNotRegisteredOrExposed() {
        val executor = com.droidance.agent.executorSources()
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()

        assertFalse(projectFile("app/src/main/java/com/droidance/tools").resolve("SystemShellToolRegistrar.kt").exists())
        assertFalse(executor.contains("SystemShellToolRegistrar"))
        listOf(
            "force_stop_app", "clear_app_data", "start_activity", "send_intent_broadcast",
            "get_current_activity", "media_control", "set_system_setting", "get_system_setting",
            "media_play_pause", "media_next", "media_prev", "toggle_hotspot",
            "set_ringtone_mode", "expand_notifications", "collapse_notifications",
            "clear_all_notifications", "send_custom_broadcast"
        ).forEach { retired ->
            assertFalse(catalog.contains("\"$retired\""))
        }
    }

    @Test
    fun retiredContentProviderToolsAreNotRegisteredOrExposed() {
        val executor = com.droidance.agent.executorSources()
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()

        assertFalse(projectFile("app/src/main/java/com/droidance/tools").resolve("ContentProviderToolRegistrar.kt").exists())
        assertFalse(executor.contains("ContentProviderToolRegistrar"))
        assertTrue(executor.contains("AndroidContentQueryService("))
        listOf(
            "add_contact", "update_contact", "delete_contact", "read_call_log",
            "read_calendar_events", "insert_calendar_event", "delete_calendar_event",
            "read_media_files"
        ).forEach { retired ->
            assertFalse(catalog.contains("\"$retired\""))
        }
    }

    @Test
    fun retiredBrowserCdpToolsAreNotRegisteredOrExposed() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val executor = com.droidance.agent.executorSources()
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()

        assertFalse(toolsDir.resolve("BrowserToolRegistrar.kt").exists())
        assertFalse(toolsDir.resolve("BrowserCdpTool.kt").exists())
        assertFalse(executor.contains("BrowserToolRegistrar"))
        assertFalse(executor.contains("browserCdp"))
        listOf(
            "browser_navigate", "browser_eval", "browser_click",
            "browser_type", "browser_elements", "browser_info"
        ).forEach { retired ->
            assertFalse(catalog.contains("\"$retired\""))
        }
    }

    @Test
    fun legacyRecipesHaveNoRuntimeOrMigrationPath() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val memoryDir = projectFile("app/src/main/java/com/droidance/memory")
        val executor = com.droidance.agent.executorSources()
        val app = projectFile("app/src/main/java/com/droidance/DroidanceApp.kt").readText()

        assertFalse(toolsDir.resolve("RecipeToolRegistrar.kt").exists())
        assertFalse(memoryDir.resolve("RecipeManager.kt").exists())
        assertFalse(executor.contains("RecipeToolRegistrar"))
        assertFalse(executor.contains("recipeManager"))
        assertFalse(memoryDir.resolve("LegacyRecipeMigrator.kt").exists())
        assertFalse(app.contains("legacyRecipeMigrator"))
        assertFalse(app.contains("\"recipes\""))
    }

    @Test
    fun rootInputManagerKeepsHumanizationSeparate() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()

        assertTrue(source.contains("Humanizer"))
        assertTrue(source.contains("executeContinuousPath"))
        assertTrue(source.contains("sidecarBridge.injectTouch"))
        assertTrue(projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readLines().size < 1260)
        assertTrue(!source.contains("internal object Humanizer"))
    }

    @Test
    fun directActionsReuseVerifiedPostActionFrameWithoutWrapperExecution() {
        val executor = com.droidance.agent.executorSources()
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()

        assertTrue(executor.contains("private suspend fun execute("))
        assertTrue(executor.contains("binding: ToolBinding"))
        assertTrue(
            executor.contains(
                "if (!toolNotExecuted && !sequenceContinues && autoCaptureAfterTool && shouldAutoCaptureAfterTool(toolCall, result))"
            )
        )
        assertTrue(executor.contains("consumeReusablePostActionFrame(boundDisplayId)"))
        assertFalse(executor.contains("MobileActionRouter("))
        assertFalse(executor.contains("observeMobileActionScreen()"))
        assertTrue(observe.contains("suspend fun execute(providedFrame: DeviceScreenFrame? = null): ToolExecutionResult"))
        assertTrue(observe.contains("val frame = providedFrame ?: if (executionContext.isVirtualSpace)"))
        assertTrue(observe.contains("captureWithFrameRecovery("))
        assertFalse(observe.contains("VisualFrameSampler("))
    }

    @Test
    fun unchangedTapDoesNotTriggerHarnessOwnedPrecisionCrop() {
        val executor = com.droidance.agent.executorSources()
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()

        assertFalse(executor.contains("precisionRecoveryTracker"))
        assertFalse(executor.contains("preparePrecisionObservation("))
        assertFalse(observe.contains("detailMode = \"auto_precision\""))
        assertTrue(observe.contains("fun executeCropZoom(arguments: String, original: ByteArray)"))
        assertTrue(projectFile("app/src/main/java/com/droidance/tools/observe/RetainedImageCrop.kt").readText().contains("zoomed.compress(Bitmap.CompressFormat.PNG, 100, output)"))
        assertFalse(observe.contains("VisionFrameScaler.withGrid("))
    }

    @Test
    fun ocrTextIsAnAnchorRatherThanADeclaredClickableControl() {
        val executor = com.droidance.agent.executorSources()
        assertFalse(executor.contains("cacheOcrBlocksAsDumpElements"))
        assertFalse(executor.contains("target?.className == \"OCR\""))
        assertFalse(executor.contains("ocr_anchor_unverified"))
    }

    @Test
    fun postActionAwaitReportsFrameAvailabilityInsteadOfOnlyJobCompletion() {
        val executor = com.droidance.agent.executorSources()
        val observations = projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText()
        val awaitBody = observations
            .substringAfter("suspend fun awaitPendingScreenshot")
            .substringBefore("/** 最近一次 screenshot")

        assertTrue(awaitBody.contains("currentPendingObservation()"))
        assertFalse(awaitBody.contains("job.join(); true"))

        val bindingBody = observations
            .substringAfter("fun currentPendingObservation()")
            .substringBefore("@Volatile")
        assertTrue(bindingBody.contains("execution.executionId"))
        assertTrue(bindingBody.contains("execution.backendSessionId"))
        assertTrue(bindingBody.contains("execution.displayId"))
    }

    @Test
    fun modelRoundLeasesTheCurrentObservationUntilAStateChangingAction() {
        val operator = com.droidance.agent.coordinationSources()
        val frameBinding = operator
            .substringAfter("val newerObservation = toolExecutor.consumePendingObservation()")
            .substringBefore("// 发模型前原地压缩窗口外的旧大块历史")

        assertTrue(frameBinding.contains("currentObservationLease = newerObservation"))
        assertTrue(frameBinding.contains("val pendingObservation = currentObservationLease"))
        assertTrue(frameBinding.contains("toolExecutor.bindModelFrame(pendingObservation)"))
        assertFalse(frameBinding.contains("toolExecutor.bindCurrentModelFrame("))

        assertTrue(operator.contains("selectedCall?.contract?.effect == ToolEffect.STATE_CHANGING"))
        assertTrue(operator.contains("currentObservationLease = null"))
        assertTrue(operator.contains("toolExecutor.invalidateModelFrameBinding()"))
        assertTrue(operator.contains("code = \"STALE_OBSERVATION\""))
    }

    @Test
    fun screenActionRequiresAndConsumesTheCurrentBoundObservation() {
        val executor = com.droidance.agent.executorSources()
        val executionBody = executor
            .substringAfter("private suspend fun execute(")
            .substringBefore("fun schedulesPostActionCapture")

        assertTrue(executionBody.contains("val boundExecution = checkNotNull(executionContext)"))
        assertTrue(executionBody.contains("hasCurrentModelObservationLease(boundExecution)"))
        assertTrue(executionBody.contains("checkPreActionObservation(boundExecution)"))
        assertTrue(executionBody.contains("invocationState.screenSequenceGeometry != geometry"))
        assertTrue(executionBody.contains("code = \"STALE_OBSERVATION\""))
        assertTrue(executionBody.contains("code = \"TOOL_NOT_FOUND\""))
        assertTrue(executionBody.contains("code = \"CHANNEL_DOWN\""))
        assertFalse(executionBody.contains("Call android_read with op=screen first"))
        assertFalse(executionBody.contains("Available tools:"))
        val loop = com.droidance.agent.coordinationSources()
        assertTrue(loop.contains("result.diagnosticCode == \"CHANNEL_DOWN\""))
        assertTrue(executionBody.contains("if (UnifiedDeviceToolProtocol.isScreenAction(toolCall.name))"))
        assertTrue(executionBody.contains("invalidateModelFrameBinding()"))

        val observations = projectFile("app/src/main/java/com/droidance/tools/ToolObservationSession.kt").readText()
        val bindingBody = observations
            .substringAfter("fun bindModelFrame(observation: ObservationEnvelope)")
            .substringBefore("fun hasCurrentModelObservationLease")
        assertTrue(bindingBody.contains("observation.backendSessionId == execution.backendSessionId"))
        assertTrue(bindingBody.contains("observation.displayId == execution.displayId"))
        assertTrue(bindingBody.contains("rotationDegrees = observation.rotationDegrees"))
        assertTrue(bindingBody.contains("currentModelObservationLease = ObservationLease("))
        assertTrue(observations.contains("displaySnapshotResolver.capture(execution.displayId).rotationDegrees"))
        assertTrue(observations.contains("lease.rotationDegrees == currentRotation"))
    }

    @Test
    fun inputDeliveryIsCorrelatedWithTheActiveToolCall() {
        val executor = com.droidance.agent.executorSources()
        val recorder = executor
            .substringAfter("private suspend fun recordExecutedActionResult(")
            .substringBefore("fun currentExecutionDisplayId")
        val finishEvent = executor
            .substringAfter("fun finishEvent(result: ToolExecutionResult): ToolExecutionResult {")
            .substringBefore("operationRecorder.recordToolExecution(")

        assertTrue(recorder.contains("coroutineContext[ToolInvocation]?.callId"))
        assertTrue(executor.contains("withContext(ToolInvocation(eventToolCallId) + observationTiming + batchProgress + tapFeedback)"))
        assertTrue(recorder.contains("coroutineContext[TapFeedbackCapture]?.record(result, coroutineContext[TapRequest])"))
        assertFalse(executor.contains("activeToolCallId"))
        assertTrue(recorder.contains("actionDeliveryByToolCall.getOrPut(callId)"))
        assertTrue(finishEvent.contains("synchronized(invocationState.actionDeliveryByToolCall)"))
        assertTrue(finishEvent.contains("invocationState.actionDeliveryByToolCall.remove(eventToolCallId)?.all { it }"))
        assertFalse(executor.contains("consumeActionDeliveryEvidence"))
        val loop = com.droidance.agent.coordinationSources()
        assertTrue(executor.contains("result = retained"))
        assertFalse(loop.contains("consumeActionDeliveryEvidence(call.callId)"))
    }

    @Test
    fun frozenContextReplacementIsAllowedWithinTurn() {
        val source = com.droidance.agent.executorSources()
        assertTrue(source.contains("current.executionId == frozen.executionId"))
        assertTrue(source.contains("executionContextStore.finish(current.executionId)"))
    }

    @Test
    fun virtualSpaceExecutionDoesNotInheritHostDeviceKeyguardLockedState() {
        val executor = com.droidance.agent.executorSources()
        assertTrue(executor.contains("if (executionContextStore.current()?.isVirtualSpace == true)"))
        assertTrue(executor.contains("deviceLocked = {"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            if (File(current, "settings.gradle.kts").isFile || File(current, "settings.gradle").isFile) {
                return File(current, path)
            }
            current = current.parentFile ?: break
        }
        error("Cannot locate repository root from $userDir")
    }
}
