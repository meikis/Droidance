// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.core.device.agent.device.DeviceShellOutcome
import com.droidance.core.device.agent.device.FreshPixelSample
import com.droidance.core.device.agent.device.InputFocusReadiness
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.debug.ExecutionPathClassifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDeviceToolProtocolTest {
    @Test
    fun exposesBackendAgnosticDevicePrimitives() {
        assertEquals(
            linkedSetOf("android_read", "android_act", "shell", "screen_act"),
            DeviceToolNames.deviceToolNames
        )
        ToolCatalog.operatorToolNamesFor(
            com.droidance.core.agent.device.RuntimeMode.STANDARD,
            com.droidance.core.agent.device.ExecutionSpace.VIRTUAL_SPACE
        ).let { air ->
            val pro = ToolCatalog.operatorToolNamesFor(
                com.droidance.core.agent.device.RuntimeMode.ULTRA,
                com.droidance.core.agent.device.ExecutionSpace.VIRTUAL_SPACE
            )
            assertFalse(pro.containsAll(DeviceToolNames.deviceToolNames))
            assertTrue(air.containsAll(DeviceToolNames.deviceToolNames))
        }
        listOf(
            "observe_screen", "crop_zoom", "query_android_content", "open_app", "run_shell",
            "click", "swipe", "scroll", "type_text", "press_key", "screen_actions"
        ).forEach { assertFalse(it in ToolCatalog.operatorToolNames) }
    }

    @Test
    fun retiredModeDoesNotExposeDeviceShellSchemas() {
        val air = ToolCatalog.operatorToolNamesFor(
            com.droidance.core.agent.device.RuntimeMode.STANDARD,
            com.droidance.core.agent.device.ExecutionSpace.VIRTUAL_SPACE
        )
        val pro = ToolCatalog.operatorToolNamesFor(
            com.droidance.core.agent.device.RuntimeMode.ULTRA,
            com.droidance.core.agent.device.ExecutionSpace.VIRTUAL_SPACE
        )

        assertFalse(pro.containsAll(DeviceToolNames.deviceToolNames))
        assertTrue("shell" in air)
        assertTrue(air.containsAll(setOf("android_read", "android_act", "screen_act")))
    }

    @Test
    fun operationRequirementsBindOnlyTheRequiredExecutionScope() {
        val contentRead = UnifiedDeviceToolProtocol.requirements(
            "android_read", """{"op":"content","uri":"content://sms"}"""
        )
        assertEquals(ExecutionScope.APP_UID, contentRead.scope)
        assertFalse(contentRead.requiresBackend)
        assertFalse(contentRead.requiresObservation)
        assertFalse(contentRead.mayChangeDisplay)

        val deviceRead = UnifiedDeviceToolProtocol.requirements(
            "android_read", """{"op":"device"}"""
        )
        assertEquals(ExecutionScope.APP_UID, deviceRead.scope)
        assertFalse(deviceRead.requiresBackend)

        val capabilitiesRead = UnifiedDeviceToolProtocol.requirements(
            "android_read", """{"op":"capabilities","package_name":"com.example"}"""
        )
        assertEquals(ExecutionScope.APP_UID, capabilitiesRead.scope)
        assertFalse(capabilitiesRead.requiresBackend)

        val networkRead = UnifiedDeviceToolProtocol.requirements(
            "android_read", """{"op":"network"}"""
        )
        assertEquals(ExecutionScope.DEVICE_SHELL, networkRead.scope)
        assertTrue(networkRead.requiresBackend)

        val systemRead = UnifiedDeviceToolProtocol.requirements(
            "android_read", """{"op":"system","query":"time"}"""
        )
        assertEquals(ExecutionScope.DEVICE_SHELL, systemRead.scope)
        assertTrue(systemRead.requiresBackend)
        assertFalse(systemRead.requiresObservation)

        val clipboardRead = UnifiedDeviceToolProtocol.requirements(
            "android_read", """{"op":"clipboard"}"""
        )
        assertEquals(ExecutionScope.DEVICE_SHELL, clipboardRead.scope)
        assertTrue(clipboardRead.requiresBackend)
        assertFalse(clipboardRead.requiresObservation)
        assertFalse(clipboardRead.mayChangeDisplay)

        val contentWrite = UnifiedDeviceToolProtocol.requirements(
            "android_act", """{"op":"content","uri":"content://demo"}"""
        )
        assertEquals(ExecutionScope.APP_UID, contentWrite.scope)
        assertFalse(contentWrite.requiresBackend)
        assertFalse(contentWrite.requiresObservation)
        assertFalse(contentWrite.mayChangeDisplay)

        val visualAction = UnifiedDeviceToolProtocol.requirements(
            "screen_act", """{"actions":[{"op":"click","x":10,"y":20}]}"""
        )
        assertEquals(ExecutionScope.DISPLAY, visualAction.scope)
        assertTrue(visualAction.requiresBackend)
        assertTrue(visualAction.requiresObservation)
        assertTrue(visualAction.mayChangeDisplay)

        val textAction = UnifiedDeviceToolProtocol.requirements(
            "screen_act", """{"actions":[{"op":"type_text","text":"fish tank","x":500,"y":120}]}"""
        )
        assertEquals(ExecutionScope.DISPLAY, textAction.scope)
        assertTrue(textAction.requiresBackend)
        assertTrue(textAction.requiresObservation)

        val localWait = UnifiedDeviceToolProtocol.requirements("wait", """{"ms":3000}""")
        assertEquals(ExecutionScope.TURN_LOCAL, localWait.scope)
        assertFalse(localWait.requiresBackend)
        assertFalse(localWait.requiresObservation)
        assertFalse(localWait.mayChangeDisplay)
    }

    @Test
    fun argumentDependentToolSetMatchesTheOnlyOperationSpecificBindings() {
        assertEquals(
            setOf("android_read", "android_act", "program", "run_skill"),
            UnifiedDeviceToolProtocol.argumentDependentTools
        )
    }

    @Test
    fun programScopeDependsOnExplicitDeviceAccessFlag() {
        val local = UnifiedDeviceToolProtocol.requirements("program", """{"code":"1 + 1"}""")
        assertEquals(ExecutionScope.TURN_LOCAL, local.scope)
        assertFalse(local.requiresBackend)

        val device = UnifiedDeviceToolProtocol.requirements(
            "program", """{"code":"android.read()","device_access":true}"""
        )
        assertEquals(ExecutionScope.DISPLAY, device.scope)
        assertTrue(device.requiresBackend)
        assertTrue(device.mayChangeDisplay)
    }

    @Test
    fun clickAndTextClassificationIncludesProviderNativeBatches() {
        assertTrue(
            UnifiedDeviceToolProtocol.containsClickAction(
                "screen_act",
                """{"actions":[{"op":"find_and_click","query":"field"}]}"""
            )
        )
        assertTrue(
            UnifiedDeviceToolProtocol.containsClickAction(
                "screen_act",
                """{"actions":[{"op":"click","x":1,"y":2},{"op":"type_text","text":"value"}]}"""
            )
        )
        assertTrue(
            UnifiedDeviceToolProtocol.containsTextInputAction(
                "screen_act",
                """{"actions":[{"op":"type_text","text":"value"}]}"""
            )
        )
        assertFalse(
            UnifiedDeviceToolProtocol.containsClickAction(
                "screen_act",
                """{"actions":[{"op":"type_text","text":"value"}]}"""
            )
        )
    }

    @Test
    fun screenActBatchAllowsTargetIdInActions() {
        val policy = com.droidance.core.agent.config.ScreenActionBatchPolicy(
            enabled = true,
            allowedOperations = listOf("click", "type_text", "press_key", "scroll", "wait", "screenshot"),
            defaultWaitMs = 100L,
            minWaitMs = 10L,
            maxWaitMs = 5000L,
            maxTotalWaitMs = 10000L, maxCaptureImages = 10, maxCaptureBytes = 8 * 1024 * 1024L, maxDragRepeat = 64
        )
        val args = """
            {
                "actions": [
                    {"op":"click", "target_id": "text_1"},
                    {"op":"type_text", "target_id": "text_2", "text": "hello", "clear": true}
                ]
            }
        """.trimIndent()
        val plan = ScreenActionBatchPlanner.plan(args, policy)
        assertTrue(plan is ScreenActionBatchPlan.Ready)
        val ready = plan as ScreenActionBatchPlan.Ready
        assertEquals(2, ready.actions.size)
        assertTrue(ready.actions[0].argumentsJson.contains("text_1"))
        assertTrue(ready.actions[1].argumentsJson.contains("text_2"))
    }

    @Test
    fun frameWaitIsRecognizedOnlyForScreenActionToolsWithPositiveMilliseconds() {
        assertTrue(
            UnifiedDeviceToolProtocol.requestsFrameWait(
                "screen_act",
                """{"wait_for_frame_ms":250,"actions":[{"op":"click"}]}"""
            )
        )
        assertTrue(
            UnifiedDeviceToolProtocol.requestsFrameWait(
                "screen_act",
                """{"wait_for_frame_ms":1,"actions":[{"op":"click","x":1,"y":2}]}"""
            )
        )
        assertFalse(
            UnifiedDeviceToolProtocol.requestsFrameWait(
                "screen_act",
                """{"wait_for_frame_ms":0,"actions":[{"op":"click"}]}"""
            )
        )
        assertFalse(
            UnifiedDeviceToolProtocol.requestsFrameWait(
                "android_act",
                """{"wait_for_frame_ms":250}"""
            )
        )
        assertFalse(UnifiedDeviceToolProtocol.requestsFrameWait("screen_act", "not-json"))
    }

    @Test
    fun onlyPureWaitOrScreenshotBatchesAreClassifiedAsObservationOnly() {
        assertTrue(UnifiedDeviceToolProtocol.isObservationOnlyAction(
            "screen_act", """{"actions":[{"op":"wait","ms":100},{"op":"screenshot"}]}"""
        ))
        assertTrue(UnifiedDeviceToolProtocol.isObservationOnlyAction(
            "screen_act", """{"actions":[{"op":"screenshot"},{"op":"wait","ms":20}]}"""
        ))
        assertFalse(UnifiedDeviceToolProtocol.isObservationOnlyAction(
            "screen_act", """{"actions":[{"op":"screenshot"},{"op":"click","x":1,"y":2}]}"""
        ))
        assertFalse(UnifiedDeviceToolProtocol.isObservationOnlyAction(
            "screen_act", """{"actions":[{"op":"wait","ms":10},{"type":"unknown"}]}"""
        ))
        assertFalse(UnifiedDeviceToolProtocol.isObservationOnlyAction(
            "screen_act", """{"actions":[{"op":"wait","ms":10},{}]}"""
        ))
        assertFalse(UnifiedDeviceToolProtocol.isObservationOnlyAction(
            "screen_act", """{"actions":null}"""
        ))
        assertTrue(UnifiedDeviceToolProtocol.isObservationOnlyAction("screen_act", """{"actions":[{"op":"wait","ms":10}]}"""))
        assertTrue(UnifiedDeviceToolProtocol.isObservationOnlyAction("screen_act", """{"actions":[{"op":"screenshot"}]}"""))
        assertFalse(UnifiedDeviceToolProtocol.isObservationOnlyAction("screen_act", "not-json"))
        assertFalse(UnifiedDeviceToolProtocol.isObservationOnlyAction(
            "android_act", """{"actions":[{"op":"wait","ms":10}]}"""
        ))
    }

    @Test
    fun delegatesOnlyWhitelistedOperationsAndStripsProtocolOp() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val shellCalls = mutableListOf<Pair<String, Long>>()
        val exposed = linkedMapOf<String, Tool>()
        UnifiedDeviceToolRegistrar(
            scrollDirectionDescription = "Test scroll direction",
            batchDescription = "Test batch policy",
            registerTool = { tool -> exposed[tool.name] = tool },
            deviceBackend = recordingBackend(shellCalls),
            readApps = { ToolProtocolResult.success("apps") },
            readDevice = { calls += "device_service" to "{}"; ToolProtocolResult.success("device") },
            discoverCapabilities = { ToolProtocolResult.success("capabilities") },
            readContent = { calls += "content_service" to it; ToolProtocolResult.success("content") },
            readScreen = { calls += "screen_service" to "{}"; ToolProtocolResult.success("screen") },
            readCrop = { calls += "crop_service" to it; ToolProtocolResult.success("crop") },
            openAndroid = { calls += "open_service" to it; ToolProtocolResult.success("open") },
            screenActions = recordingScreenActions(calls),
            androidReadDescription = "Test android read",
            androidActDescription = "Test android act",
            shellPolicy = com.droidance.core.agent.config.ShellExecutionPolicy(65536, 15000, 120000),
            shellDescription = "Test shell",
            textInputClearDescription = "Test explicit edit mode",
            screenActDescription = "Test screen act"
        ).registerAll()

        assertEquals(DeviceToolNames.deviceToolNames, exposed.keys)
        val screenParameters = exposed.getValue("screen_act").definition.parameters
        val primitives = screenParameters.getValue("actions").items!!.properties!!
        assertFalse(screenParameters.containsKey("op"))
        assertTrue(screenParameters.getValue("actions").required)
        assertFalse(primitives.getValue("op").enumValues.orEmpty().contains("find_and_click"))
        assertTrue(primitives.getValue("op").enumValues.orEmpty().containsAll(
            listOf("type_text", "wait", "screenshot", "swipe", "drag", "path")))
        assertTrue(primitives.containsKey("text"))
        assertTrue(primitives.containsKey("clear"))
        assertTrue(primitives.getValue("target_id").description.contains("Never invent"))
        exposed.getValue("android_read").execute("""{"op":"device"}""")
        exposed.getValue("android_read").execute("""{"op":"content","uri":"content://demo"}""")
        exposed.getValue("android_read").execute("""{"op":"screen"}""")
        val missingCropSource = exposed.getValue("android_read").execute("""{"op":"crop","left":1,"top":2,"right":3,"bottom":4}""")
        assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED, missingCropSource.executionStatus)
        assertTrue(calls.any { it.first == "crop_service" })
        exposed.getValue("android_read").execute("""{"op":"crop","source_ref":"observed-image","left":1,"top":2,"right":3,"bottom":4}""")
        exposed.getValue("android_act").execute("""{"op":"open","intent_action":"android.intent.action.VIEW"}""")
        exposed.getValue("shell").execute("""{"command":"settings get system demo","expect_screen_change":false}""")
        exposed.getValue("screen_act").execute("""{"actions":[{"op":"click","x":10,"y":20}]}""")
        exposed.getValue("screen_act").execute("""{"actions":[{"op":"swipe","startX":1,"startY":2,"endX":3,"endY":4}]}""")
        exposed.getValue("screen_act").execute("""{"actions":[{"op":"scroll","direction":"down","amount":"small","region":{"left":0,"top":100,"right":300,"bottom":900}}]}""")
        exposed.getValue("screen_act").execute("""{"actions":[{"op":"type_text","text":"demo","clear":true,"x":10,"y":20}]}""")
        exposed.getValue("screen_act").execute("""{"actions":[{"op":"press_key","key":"BACK"}]}""")
        exposed.getValue("screen_act").execute("""{"actions":[{"op":"screenshot"}]}""")

        assertEquals(
            listOf(
                "device_service",
                "content_service",
                "screen_service",
                "crop_service",
                "crop_service",
                "open_service",
                "screen_actions",
                "screen_actions",
                "screen_actions",
                "screen_actions",
                "screen_actions",
                "screen_actions"
            ),
            calls.map(Pair<String, String>::first)
        )
        assertEquals(listOf("settings get system demo" to 15_000L), shellCalls)
        val compoundShell = exposed.getValue("shell").execute("""{"command":"id | whoami"}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, compoundShell.executionStatus)
        assertEquals(
            listOf(
                "settings get system demo" to 15_000L,
                "id | whoami" to 15_000L
            ),
            shellCalls
        )
        val invalidShell = exposed.getValue("shell").execute("""{"command":""}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, invalidShell.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", invalidShell.diagnosticCode)
        assertTrue(invalidShell.outputText.contains("ARGUMENT_SCHEMA_MISMATCH"))
        calls.forEach { (_, arguments) ->
            assertFalse("op must not leak into a low-level executor", "op" in Json.parseToJsonElement(arguments).jsonObject)
        }
        val invalidScreenAction = exposed.getValue("screen_act").execute("""{"op":"unknown"}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, invalidScreenAction.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", invalidScreenAction.diagnosticCode)
        assertTrue(invalidScreenAction.outputText.contains("ARGUMENT_SCHEMA_MISMATCH"))
    }

    @Test
    fun nativeAlarmTimerAndMediaActionsNeverInvokeScreenTools() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val shellCalls = mutableListOf<Pair<String, Long>>()
        val exposed = linkedMapOf<String, Tool>()
        UnifiedDeviceToolRegistrar(
            scrollDirectionDescription = "Test scroll direction",
            batchDescription = "Test batch policy",
            registerTool = { tool -> exposed[tool.name] = tool },
            deviceBackend = recordingBackend(shellCalls),
            readApps = { ToolProtocolResult.success("apps") },
            readDevice = { ToolProtocolResult.success("device") },
            discoverCapabilities = { ToolProtocolResult.success("capabilities") },
            readContent = { ToolProtocolResult.success("content") },
            readScreen = { ToolProtocolResult.success("screen") },
            readCrop = { ToolProtocolResult.success("crop") },
            openAndroid = { calls += "open_service" to it; ToolProtocolResult.success("open") },
            screenActions = recordingScreenActions(calls),
            androidReadDescription = "Test android read",
            androidActDescription = "Test android act",
            shellPolicy = com.droidance.core.agent.config.ShellExecutionPolicy(65536, 15000, 120000),
            shellDescription = "Test shell",
            textInputClearDescription = "Test explicit edit mode",
            screenActDescription = "Test screen act"
        ).registerAll()

        listOf(
            "android.intent.action.SET_ALARM",
            "android.intent.action.SET_TIMER",
            "android.media.action.MEDIA_PLAY_FROM_SEARCH"
        ).forEach { action ->
            exposed.getValue("android_act").execute(
                """{"op":"open","intent_action":"$action","string_extras":{"android.intent.extra.alarm.MESSAGE":"demo"}}"""
            )
        }

        assertEquals(listOf("open_service", "open_service", "open_service"), calls.map { it.first })
        assertTrue(calls.none { (name, _) -> name in setOf("click", "swipe", "drag", "path", "scroll", "type_text", "screen_actions") })
        assertTrue(shellCalls.isEmpty())
        assertEquals(
            listOf(
                "android.intent.action.SET_ALARM",
                "android.intent.action.SET_TIMER",
                "android.media.action.MEDIA_PLAY_FROM_SEARCH"
            ),
            calls.map { (_, arguments) ->
                Json.parseToJsonElement(arguments).jsonObject.getValue("intent_action").jsonPrimitive.content
            }
        )
    }

    @Test
    fun preservesNativeFirstExecutionPathEvidenceThroughUnifiedNames() {
        assertEquals(
            "android_api",
            ExecutionPathClassifier.classify("android_read", """{"op":"content","uri":"content://sms"}""")
        )
        assertEquals(
            "native_intent",
            ExecutionPathClassifier.classify("android_act", """{"op":"open","intent_action":"android.intent.action.SET_ALARM"}""")
        )
        assertEquals("shell", ExecutionPathClassifier.classify("shell", """{"command":"id"}"""))
        assertEquals("gui", ExecutionPathClassifier.classify("screen_act", """{"actions":[{"op":"click","x":1,"y":2}]}"""))
        assertEquals("gui", ExecutionPathClassifier.classify("screen_act", """{"actions":[]}"""))
        assertTrue(
            UnifiedDeviceToolProtocol.isGroundedNativeContractReceipt(
                "android_act",
                ToolExecutionResult(
                    outputText = "[NATIVE_CONTRACT_ACCEPTED] action and typed extras",
                    executionStatus = ToolExecutionStatus.SUCCEEDED,
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                    platformContractAccepted = true
                )
            )
        )
        assertFalse(
            UnifiedDeviceToolProtocol.isGroundedNativeContractReceipt(
                "android_act",
                ToolExecutionResult(
                    outputText = "[NATIVE_ACTION_ACCEPTED_UNVERIFIED] action",
                    executionStatus = ToolExecutionStatus.SUCCEEDED,
                    evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                    platformContractAccepted = true
                )
            )
        )
        assertFalse(UnifiedDeviceToolProtocol.expectsScreenChange("android_act", """{"op":"content","action":"insert"}"""))
        assertTrue(UnifiedDeviceToolProtocol.expectsScreenChange("android_act", """{"op":"open","intent_action":"android.intent.action.VIEW"}"""))
        assertFalse(UnifiedDeviceToolProtocol.expectsScreenChange("android_act", """{"op":"open","expect_screen_change":false}"""))
    }

    private fun recordingScreenActions(calls: MutableList<Pair<String, String>>): ScreenActionService =
        object : ScreenActionService {
            private fun record(name: String, arguments: String): ToolExecutionResult {
                calls += name to arguments
                return ToolProtocolResult.success(name)
            }

            override suspend fun click(arguments: String) = record("click", arguments)
            override suspend fun findAndClick(arguments: String) = record("find_and_click", arguments)
            override suspend fun swipe(arguments: String) = record("swipe", arguments)
            override suspend fun drag(arguments: String) = record("drag", arguments)
            override suspend fun path(arguments: String) = record("path", arguments)
            override suspend fun scroll(arguments: String) = record("scroll", arguments)
            override suspend fun typeText(arguments: String) = record("type_text", arguments)
            override suspend fun pressKey(arguments: String) = record("press_key", arguments)
            override suspend fun batch(arguments: String) = record("screen_actions", arguments)
        }

    private fun recordingBackend(calls: MutableList<Pair<String, Long>>): DeviceBackend =
        object : DeviceBackend {
            override val activeDisplayId: Int? = null
            override suspend fun captureScreen(forceFresh: Boolean): DeviceScreenFrame? =
                error("not used")
            override suspend fun samplePixels(points: List<Pair<Int, Int>>): FreshPixelSample? =
                error("not used")
            override suspend fun sampleScreen(): VisualFrameSampler.Frame? = null
            override fun lastScreenFrame(): DeviceScreenFrame? = error("not used")
            override fun invalidateScreenCache() = error("not used")
            override fun screenFailureMessage(): String = error("not used")
            override suspend fun tap(x: Int, y: Int, precision: TapPrecision): DeviceInputResult =
                error("not used")
            override suspend fun swipe(
                x1: Int,
                y1: Int,
                x2: Int,
                y2: Int,
                durationMs: Int
            ): DeviceInputResult = error("not used")
            override suspend fun drag(
                x1: Int,
                y1: Int,
                x2: Int,
                y2: Int,
                durationMs: Int
            ): DeviceInputResult = error("not used")
            override suspend fun tracePath(points: List<Pair<Int, Int>>, durationMs: Int): DeviceInputResult =
                error("not used")
            override suspend fun pressKey(keyCode: Int): DeviceInputResult = error("not used")
            override suspend fun setKeyboardVisible(visible: Boolean): DeviceInputResult = error("not used")
            override suspend fun awaitInputFocus(timeoutMs: Long): InputFocusReadiness = error("not used")
            override suspend fun typeText(
                text: String,
                clearExisting: Boolean,
                expectedFocusTarget: String,
                method: TextInputMethod,
                focusPoint: Pair<Int, Int>?
            ): DeviceInputResult = error("not used")
            override suspend fun openPackage(
                packageName: String,
                launchMode: com.droidance.core.agent.device.AppLaunchMode,
                intentFlags: Int
            ): com.droidance.core.device.agent.device.DeviceAppLaunchOutcome = error("not used")
            override suspend fun openIntentCommand(command: String): DeviceShellOutcome = error("not used")
            override suspend fun forceStopApp(packageName: String): DeviceShellOutcome = error("not used")

            override suspend fun querySystem(request: com.droidance.core.device.agent.device.SystemStateQuery): com.droidance.core.device.agent.device.SystemQueryOutcome = error("not used")
            override suspend fun readClipboard(): ToolExecutionResult = error("not used")

            override suspend fun shell(command: String, timeoutMs: Long): DeviceShellOutcome {
                calls += command to timeoutMs
                return DeviceShellOutcome(
                    backend = "recording",
                    exitCode = 0,
                    stdout = "ok",
                    stderr = ""
                )
            }
        }
}
