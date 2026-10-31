// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.device.agent.device.FakeDeviceBackend
import com.droidance.core.device.agent.device.DeviceShellOutcome
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.harness.ToolArgumentSchemaValidator
import com.droidance.core.model.llm.OpenAIResponsesProtocol
import com.droidance.core.model.llm.OpenAIResponsesTurnState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnifiedDeviceToolRegistrarTest {
    @Test fun nativeKeyboardModeIsAgentSelectedAndNeverTypesOrSubmitsText() = runBlocking {
        val tool = registeredTools.getValue(DeviceToolNames.ANDROID_ACT)
        assertEquals(ToolExecutionStatus.SUCCEEDED, tool.execute("""{"op":"keyboard","visible":true}""").executionStatus)
        assertEquals(ToolExecutionStatus.SUCCEEDED, tool.execute("""{"op":"keyboard","visible":false}""").executionStatus)
        assertEquals(listOf(true, false), fakeBackend.keyboardRequests)
        for (arguments in listOf("""{"op":"keyboard"}""", """{"op":"keyboard","visible":"true"}""",
                """{"op":"keyboard","visible":true,"text":"unexpected"}""")) {
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, tool.execute(arguments).executionStatus)
        }
        assertEquals(listOf(true, false), fakeBackend.keyboardRequests)
        assertTrue(fakeBackend.typedTexts.isEmpty())
        assertTrue(fakeBackend.keyCalls.isEmpty())
        val requirements = UnifiedDeviceToolProtocol.requirements(DeviceToolNames.ANDROID_ACT,
            """{"op":"keyboard","visible":true}""")
        assertEquals(ExecutionScope.DISPLAY, requirements.scope)
        assertTrue(requirements.requiresBackend)
        assertTrue(requirements.mayChangeDisplay)
    }

    @Test fun appInventoryReadDoesNotUseTheDisplayOrShellExecutionBackend() = runBlocking {
        val tool = registeredTools.getValue(DeviceToolNames.ANDROID_READ)
        assertEquals("apps", tool.execute("""{"op":"apps","query":"Notes","offset":20,"limit":20}""").outputText)
        assertEquals(null, fakeBackend.lastExecutedShellCommand)
        val requirements = UnifiedDeviceToolProtocol.requirements(DeviceToolNames.ANDROID_READ, """{"op":"apps"}""")
        assertEquals(ExecutionScope.APP_UID, requirements.scope)
        assertFalse(requirements.requiresBackend)
        assertFalse(requirements.mayChangeDisplay)
    }
    @Test fun clipboardReadPreservesTheBoundReceiptAndExactCharactersWithoutOtherDeviceCalls() = runBlocking {
        val exactText = " \uD83D\uDCDF e\u0301\u200D\uFE0F\n"
        val facts = buildJsonObject {
            put("source_kind", "android_clipboard")
            put("scope", "reader_user_clipboard")
            put("reader_uid", 2000)
            put("reader_user_id", 0)
            put("clipboard_requested_device_id", 0)
            put("execution_display_id", 7)
            put("clip_timestamp_ms", 0)
            put("items", buildJsonArray {
                add(buildJsonObject { put("index", 0); put("text", exactText) })
                add(buildJsonObject { put("index", 1); put("text", JsonNull); put("has_uri", true) })
            })
        }
        fakeBackend.clipboardOutcome = ToolProtocolResult.success(facts.toString()).copy(data = facts)
        val read = registeredTools.getValue(DeviceToolNames.ANDROID_READ)
        assertTrue("clipboard" in read.definition.parameters.getValue("op").enumValues.orEmpty())
        val result = read.execute("""{"op":"clipboard"}""")
        org.junit.Assert.assertSame(fakeBackend.clipboardOutcome, result)
        val receipt = Json.parseToJsonElement(result.outputText).jsonObject
        assertEquals(exactText, receipt.getValue("items").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(JsonNull, receipt.getValue("items").jsonArray[1].jsonObject.getValue("text"))
        assertEquals(0, receipt.getValue("clip_timestamp_ms").jsonPrimitive.long)
        assertEquals(0, receipt.getValue("clipboard_requested_device_id").jsonPrimitive.int)
        assertEquals(7, receipt.getValue("execution_display_id").jsonPrimitive.int)
        assertEquals(1, fakeBackend.clipboardReadCalls)
        assertNull(fakeBackend.lastExecutedShellCommand)
        assertTrue(fakeBackend.tapCalls.isEmpty())
        assertTrue(fakeBackend.swipeCalls.isEmpty())
        assertTrue(fakeBackend.keyCalls.isEmpty())
        assertTrue(fakeBackend.typedTexts.isEmpty())
        val requirements = UnifiedDeviceToolProtocol.requirements(DeviceToolNames.ANDROID_READ, """{"op":"clipboard"}""")
        assertEquals(ExecutionScope.DEVICE_SHELL, requirements.scope)
        assertTrue(requirements.requiresBackend)
        assertFalse(requirements.requiresObservation)
        assertFalse(requirements.mayChangeDisplay)
    }

    @Test fun unreadableClipboardPreservesUnknownAndDeniedResultsWithoutSubstitution() = runBlocking {
        val read = registeredTools.getValue(DeviceToolNames.ANDROID_READ)
        for (diagnostic in listOf("CLIPBOARD_NO_READABLE_CLIP", "CLIPBOARD_READ_DENIED", "CLIPBOARD_READ_DELIVERY_UNKNOWN")) {
            val facts = buildJsonObject {
                put("source_kind", "android_clipboard")
                put("status", "unknown")
                put("items", JsonNull)
                put("diagnostic_code", diagnostic)
            }
            fakeBackend.clipboardOutcome = ToolExecutionResult(facts.toString(), ToolExecutionStatus.FAILED,
                com.droidance.core.agent.event.ToolEvidenceStatus.UNKNOWN, diagnosticCode = diagnostic, data = facts)
            val result = read.execute("""{"op":"clipboard"}""")
            org.junit.Assert.assertSame(fakeBackend.clipboardOutcome, result)
            assertEquals(diagnostic, result.diagnosticCode)
            assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
            assertEquals(JsonNull, result.data?.jsonObject?.get("items"))
        }
        assertEquals(3, fakeBackend.clipboardReadCalls)
        assertNull(fakeBackend.lastExecutedShellCommand)
        assertTrue(fakeBackend.typedTexts.isEmpty())
    }
    @Test fun unsupportedIntentFieldsAreRejectedBeforeLookupInsteadOfLosingTheDestination() = runBlocking {
        val tool = registeredTools.getValue(DeviceToolNames.ANDROID_ACT)
        val rejected = tool.execute("""{"op":"open","package_name":"example.app","data-uri":"https://example.test/"}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejected.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", rejected.diagnosticCode)
        assertTrue(openedPayloads.isEmpty())
        val details = Json.parseToJsonElement(rejected.outputText).jsonObject.getValue("details").jsonObject
        assertEquals(listOf("data-uri"), details.getValue("unsupported_fields").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(details.getValue("allowed_fields").jsonArray.any { it.jsonPrimitive.content == "uri" })
        val corrected = tool.execute("""{"op":"open","package_name":"example.app","uri":"https://example.test/"}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, corrected.executionStatus)
        assertEquals(1, openedPayloads.size)
    }

    @Test fun deviceWideReadRecordsItsExecutionDisplayWithoutInferringOwnershipFromOutput() = runBlocking {
        fakeBackend.activeDisplayId = 7
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 0, "Display 2: unrelated output", "")
        val read = registeredTools.getValue("android_read")
        val payload = Json.parseToJsonElement(read.execute("""{"op":"system","service":"display"}""").outputText).jsonObject
        assertEquals("device", payload.getValue("scope").jsonPrimitive.content)
        assertEquals(7, payload.getValue("execution_display_id").jsonPrimitive.int)
        assertEquals(fakeBackend.shellOutcome.stdout, payload.getValue("stdout").jsonPrimitive.content)
        val shell = registeredTools.getValue("shell").execute("""{"command":"wm size","expect_screen_change":false}""")
        assertTrue(shell.outputText.contains("\"scope\":\"device\"") && shell.outputText.contains("\"execution_display_id\":7"))
        fakeBackend.activeDisplayId = null
        val unbound = Json.parseToJsonElement(read.execute("""{"op":"system","service":"display"}""").outputText).jsonObject
        assertEquals(JsonNull, unbound.getValue("execution_display_id"))
    }

    @Test fun literalSelectionRecoversNativeTailPastTheBoundedBackendAndRetainsFailure() = runBlocking {
        val prefix = (1..12_000).joinToString("\n") { "unrelated row $it" }
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 0,
            "$prefix\nOBJECT_X\nrequested field: 7\nstatus: active\n", "")
        val tool = registeredTools.getValue("android_read")
        val original = Json.parseToJsonElement(tool.execute("""{"op":"system","service":"audio"}""").outputText).jsonObject
        assertTrue(original.getValue("backend_truncated").jsonPrimitive.boolean)
        assertFalse(original.getValue("stdout").jsonPrimitive.content.contains("requested field"))
        val selected = Json.parseToJsonElement(tool.execute(
            """{"op":"system","service":"audio","line_filter":"requested field","context_lines":1}""").outputText).jsonObject
        assertTrue(selected.getValue("stdout").jsonPrimitive.content.contains("12001: OBJECT_X"))
        assertTrue(selected.getValue("stdout").jsonPrimitive.content.contains("12003: status: active"))
        assertFalse(selected.getValue("truncated").jsonPrimitive.boolean)
        assertEquals("before_bounded_transport", selected.getValue("selection_stage").jsonPrimitive.content)
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 0, "Permission Denial: cannot dump", "")
        assertEquals("SYSTEM_QUERY_PERMISSION_DENIED", tool.execute(
            """{"op":"system","service":"audio","line_filter":"requested field"}""").diagnosticCode)
    }

    @Test fun filteredContextRecoversLateFieldsWithOriginalLineNumbers() = runBlocking {
        val prefix = (1..1000).joinToString("\n") { "irrelevant line $it" }
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 0,
            "$prefix\nOBJECT_A\nvalue: 7\nunit: steps\nOBJECT_B\nvalue: 2", "")
        val tool = registeredTools.getValue("android_read")
        val original = Json.parseToJsonElement(tool.execute(
            """{"op":"system","service":"audio"}""").outputText).jsonObject
        assertEquals(fakeBackend.shellOutcome.stdout, original.getValue("stdout").jsonPrimitive.content)
        assertTrue(original.getValue("stdout").jsonPrimitive.content.length > 16_000)
        assertFalse(original.getValue("stdout_truncated").jsonPrimitive.boolean)
        assertFalse(original.containsKey("recovery"))
        val recovered = tool.execute("""{"op":"system","service":"audio","line_filter":"OBJECT_A","context_lines":2}""").outputText
        assertTrue(recovered.contains("1001: OBJECT_A"))
        assertTrue(recovered.contains("1002: value: 7"))
        assertTrue(recovered.contains("1003: unit: steps"))
        org.junit.Assert.assertFalse(recovered.contains("OBJECT_B"))
        assertTrue(recovered.contains("\"truncated\":false"))
    }

    @Test fun invalidContextDoesNotQueryBackend() = runBlocking {
        val tool = registeredTools.getValue("android_read")
        for (extra in listOf("\"context_lines\":41,\"line_filter\":\"x\"",
            "\"context_lines\":-1", "\"context_lines\":2", "\"context_lines\":\"bad\"")) {
            assertEquals(ToolExecutionStatus.NOT_EXECUTED,
                tool.execute("{\"op\":\"system\",$extra}").executionStatus)
        }
        assertEquals(null, fakeBackend.lastExecutedShellCommand)
    }

    @Test fun overlappingContextIsNotDuplicatedAndBackendTruncationRemainsVisible() = runBlocking {
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 0,
            "header\nmatch one\nmatch two\nfooter\n", "", stdoutTruncated = true)
        val result = registeredTools.getValue("android_read").execute(
            """{"op":"system","service":"audio","line_filter":"match","context_lines":1}""").outputText
        assertTrue(result.contains("1: header"))
        assertTrue(result.contains("4: footer"))
        assertEquals(1, "2: match one".toRegex().findAll(result).count())
        assertTrue(result.contains("\"backend_truncated\":true"))
    }

    @Test fun currentNetworkReadUsesBoundedPlatformStatusInsteadOfMixedServiceHistory() = runBlocking {
        fakeBackend.activeDisplayId = 4
        fakeBackend.shellOutcome = DeviceShellOutcome("bound_sidecar", 0, "Wifi is connected to example", "")
        val result = registeredTools.getValue("android_read").execute("""{"op":"network"}""")
        assertEquals("cmd wifi status", fakeBackend.lastExecutedShellCommand)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(result.outputText.contains("android_wifi_status"))
        assertTrue(fakeBackend.tapCalls.isEmpty())
    }
    @Test fun zeroExitPlatformFailuresCannotBecomeEvidence() = runBlocking {
        val cases = mapOf(
            "Permission Denial: can't dump service" to "SYSTEM_QUERY_PERMISSION_DENIED",
            "Can't find service: missing" to "SYSTEM_QUERY_SERVICE_UNAVAILABLE",
            "*** SERVICE DUMP TIMEOUT EXPIRED ***" to "SYSTEM_QUERY_TIMEOUT"
        )
        for ((output, expected) in cases) {
            fakeBackend.shellOutcome = DeviceShellOutcome("bound_sidecar", 0, output, "")
            val result = registeredTools.getValue("android_read").execute("""{"op":"system","service":"wifi"}""")
            assertEquals(expected, result.diagnosticCode)
            assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        }
    }

    @Test fun noMatchingLinesAndUpstreamTruncationAreExplicit() = runBlocking {
        val tool = registeredTools.getValue("android_read")
        fakeBackend.shellOutcome = DeviceShellOutcome("bound_sidecar", 0, "network: connected", "")
        assertEquals("SYSTEM_QUERY_NO_MATCH", tool.execute(
            """{"op":"system","service":"wifi","line_filter":"SSID"}""").diagnosticCode)
        fakeBackend.shellOutcome = DeviceShellOutcome("bound_sidecar", 0,
            "SSID: example", "", stdoutTruncated = true)
        val result = tool.execute("""{"op":"system","service":"wifi","line_filter":"SSID"}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(result.outputText.contains("\"truncated\":true"))
    }

    @Test fun systemReadRetainsBackendReceiptWithoutAdditionalClipping() = runBlocking {
        fakeBackend.activeDisplayId = 4
        fakeBackend.shellOutcome = DeviceShellOutcome("bound_sidecar", 0, "x".repeat(16_100), "")
        val result = registeredTools.getValue("android_read").execute("""{"op":"system","service":"battery"}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("dumpsys -t 5 battery", fakeBackend.lastExecutedShellCommand)
        assertTrue(result.outputText.contains("\"scope\":\"device\""))
        val receipt = Json.parseToJsonElement(result.outputText).jsonObject
        assertEquals(fakeBackend.shellOutcome.stdout, receipt.getValue("stdout").jsonPrimitive.content)
        assertFalse(receipt.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(fakeBackend.tapCalls.isEmpty())
        assertTrue(fakeBackend.swipeCalls.isEmpty())
    }

    @Test fun systemReadCannotPassMutationArgumentsOrControlSyntax() = runBlocking {
        for (service in listOf("battery set level 5", "wifi;id", "-l", "wifi\ninput")) {
            val args = kotlinx.serialization.json.buildJsonObject {
                put("op", kotlinx.serialization.json.JsonPrimitive("system"))
                put("service", kotlinx.serialization.json.JsonPrimitive(service))
            }
            assertEquals(ToolExecutionStatus.NOT_EXECUTED,
                registeredTools.getValue("android_read").execute(args.toString()).executionStatus)
        }
        assertEquals(null, fakeBackend.lastExecutedShellCommand)
    }

    @Test fun systemReadPreservesFailureAndFilteredScope() = runBlocking {
        val tool = registeredTools.getValue("android_read")
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 1, "", "permission denied")
        assertEquals(ToolExecutionStatus.FAILED, tool.execute("""{"op":"system"}""").executionStatus)
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 0, "SSID: example\nother: value", "")
        val result = tool.execute("""{"op":"system","service":"wifi","line_filter":"SSID"}""")
        assertTrue(result.outputText.contains("\"filtered\":true"))
        assertTrue(result.outputText.contains("SSID: example"))
        org.junit.Assert.assertFalse(result.outputText.contains("other: value"))
    }

    @Test fun capabilitiesDiscoveryDoesNotTouchTheScreenOrShell() = runBlocking {
        val result = registeredTools.getValue("android_read").execute("""{"op":"capabilities"}""")
        assertEquals("capabilities", result.outputText)
        assertEquals(null, fakeBackend.lastExecutedShellCommand)
        assertTrue(fakeBackend.tapCalls.isEmpty())
    }
    private val fakeBackend = FakeDeviceBackend()
    private val registeredTools = mutableMapOf<String, Tool>()
    private val openedPayloads = mutableListOf<String>()

    private val dummyScreenActionService = object : ScreenActionService {
        override suspend fun click(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("clicked: $arguments")
        override suspend fun findAndClick(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("found and clicked: $arguments")
        override suspend fun swipe(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("swiped: $arguments")
        override suspend fun drag(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("dragged: $arguments")
        override suspend fun path(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("pathed: $arguments")
        override suspend fun scroll(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("scrolled: $arguments")
        override suspend fun typeText(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("typed: $arguments")
        override suspend fun pressKey(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("pressed: $arguments")
        override suspend fun batch(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("batched: $arguments")
    }

    @Before
    fun setUp() {
        UnifiedDeviceToolRegistrar(
            registerTool = { registeredTools[it.name] = it },
            deviceBackend = fakeBackend,
            readApps = { ToolProtocolResult.success("apps") },
            readDevice = { ToolProtocolResult.success("device_info") },
            discoverCapabilities = { ToolProtocolResult.success("capabilities") },
            readContent = { payload -> ToolProtocolResult.success("content: $payload") },
            readScreen = { ToolProtocolResult.success("screen") },
            readCrop = { payload -> ToolProtocolResult.success("crop: $payload") },
            openAndroid = { payload ->
                openedPayloads += payload
                ToolProtocolResult.success("opened: $payload")
            },
            scrollDirectionDescription = "scroll direction",
            batchDescription = "batch description",
            screenActions = dummyScreenActionService,
            androidReadDescription = "android read test description",
            androidActDescription = "android act test description",
            shellPolicy = com.droidance.core.agent.config.ShellExecutionPolicy(65536, 15000, 120000),
            shellDescription = "shell test description",
            textInputClearDescription = "Test explicit edit mode",
            screenActDescription = "screen act test description"
        ).registerAll()
    }

    @Test
    fun registersAllUnifiedDeviceTools() {
        assertEquals(
            setOf(
                DeviceToolNames.ANDROID_READ,
                DeviceToolNames.ANDROID_ACT,
                DeviceToolNames.SHELL,
                DeviceToolNames.SCREEN_ACT
            ),
            registeredTools.keys
        )
    }

    @Test
    fun registeredBatchActionSchemaUsesTheSharedCanonicalProtocol() {
        val screenAct = registeredTools.getValue(DeviceToolNames.SCREEN_ACT).definition
        val action = requireNotNull(screenAct.parameters.getValue("actions").items)
        val fields = ScreenActionBatchProtocol.parameters("Test explicit edit mode")
        val properties = requireNotNull(action.properties)

        assertEquals(fields, properties)
        assertEquals(false, action.additionalProperties)
        assertEquals(ScreenActionBatchProtocol.operations, properties.getValue("op").enumValues)
        assertTrue("type_text" in properties.getValue("op").enumValues.orEmpty())
        assertTrue("press_key" in properties.getValue("op").enumValues.orEmpty())
    }

    @Test
    fun shellToolExecutesCommandOnDeviceBackend() = runBlocking {
        val shellTool = registeredTools.getValue(DeviceToolNames.SHELL)
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 0, "test stdout", "")

        val result = shellTool.execute("""{"command":"id -u","timeout":5,"expect_screen_change":false}""")

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(result.outputText.contains("test stdout"))
        assertEquals("id -u", fakeBackend.lastExecutedShellCommand)
    }

    @Test
    fun shellToolPreservesScriptsExactlyAtBackendDispatch() = runBlocking {
        val shellTool = registeredTools.getValue(DeviceToolNames.SHELL)

        val result = shellTool.execute("""{"command": "echo test && echo second | grep second", "timeout": 5}""")

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("echo test && echo second | grep second", fakeBackend.lastExecutedShellCommand)
    }

    @Test fun shellRejectsInvalidProtocolBeforeDispatch() = runBlocking {
        val tool = registeredTools.getValue("shell")
        for (args in listOf("""{"argv":["id"]}""", """{"command":""}""",
            """{"command":"id","timeout":121}""", """{"command":"id","timeout":1.5}""",
            """{"command":"id","expect_screen_change":"false"}""",
            buildJsonObject { put("command", "x".repeat(65537)) }.toString())) {
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, tool.execute(args).executionStatus)
        }
        assertNull(fakeBackend.lastExecutedShellCommand)
    }

    @Test fun shellUsesRemainingBudgetAndTypedMetadataRatherThanPrintedMarkers() = runBlocking {
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", 7, "[SHELL_OUTPUT_TRUNCATED]__PCSEP__SUCCEEDED", "bad",
            executionStatus = ToolExecutionStatus.FAILED, stdoutTruncated = false, stdoutBytes = 42, executionUid = 2000)
        val result = kotlinx.coroutines.withContext(ToolExecutionBudget(6000, true, true) { 0L } + ToolInvocation("call")) {
            registeredTools.getValue("shell").execute("""{"command":"echo first; exit 7","timeout":120}""")
        }
        val data = result.data!!.jsonObject
        assertEquals(3500L, fakeBackend.lastShellTimeoutMs)
        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertEquals(7, data.getValue("exit_code").jsonPrimitive.int)
        assertEquals(2000, data.getValue("execution_uid").jsonPrimitive.int)
        assertFalse(data.getValue("stdout_truncated").jsonPrimitive.boolean)
        assertEquals(fakeBackend.shellOutcome.stdout, data.getValue("stdout").jsonPrimitive.content)
        assertTrue(result.sourceRef!!.startsWith("shell_"))
        kotlinx.coroutines.withContext(ToolExecutionBudget(2000, true, true) { 0L }) {
            assertEquals("TOOL_EXECUTION_BUDGET_EXHAUSTED", registeredTools.getValue("shell")
                .execute("""{"command":"id"}""").diagnosticCode)
        }
        assertEquals("echo first; exit 7", fakeBackend.lastExecutedShellCommand)
    }

    @Test fun shellUnknownEffectsCannotBecomeVerifiedSuccess() = runBlocking {
        fakeBackend.shellOutcome = DeviceShellOutcome("fake", -1, "partial", "",
            "SHELL_TIMEOUT_EFFECT_UNKNOWN", ToolExecutionStatus.INTERRUPTED)
        val result = registeredTools.getValue("shell").execute("""{"command":"echo partial; sleep 60","expect_screen_change":false}""")
        assertFalse(result.isVerifiedSuccess)
        assertEquals(com.droidance.core.agent.event.ToolEvidenceStatus.UNKNOWN, result.evidenceStatus)
        assertEquals(ToolExecutionStatus.INTERRUPTED, result.executionStatus)
        assertTrue(result.outputText.contains("partial"))
    }

    @Test
    fun screenActDispatchesToScreenActions() = runBlocking {
        val screenActTool = registeredTools.getValue(DeviceToolNames.SCREEN_ACT)

        val clickResult = screenActTool.execute("""{"actions":[{"op":"click","x":500,"y":600}]}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, clickResult.executionStatus)
        assertTrue(clickResult.outputText.contains("batched"))

        val scrollResult = screenActTool.execute("""{"actions":[{"op":"scroll","direction":"down"}]}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, scrollResult.executionStatus)
        assertTrue(scrollResult.outputText.contains("batched"))
    }

    @Test
    fun androidReadDispatchesToAppropriatePort() = runBlocking {
        val readTool = registeredTools.getValue(DeviceToolNames.ANDROID_READ)

        val deviceResult = readTool.execute("""{"op": "device"}""")
        assertEquals("device_info", deviceResult.outputText)

        val screenResult = readTool.execute("""{"op": "screen"}""")
        assertEquals("screen", screenResult.outputText)
    }

    @Test
    fun androidActDispatchesToOpenAndroid() = runBlocking {
        val actTool = registeredTools.getValue(DeviceToolNames.ANDROID_ACT)

        val openResult = actTool.execute("""{"op": "open", "package_name": "com.example"}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, openResult.executionStatus)
        assertTrue(openResult.outputText.contains("opened"))
    }

    @Test fun publicDescriptionsCannotBeMisusedAsIntentActionsOrReachTheLauncher() = runBlocking {
        val tool = registeredTools.getValue(DeviceToolNames.ANDROID_ACT)
        val invalid = tool.execute("""{"op":"open","package_name":"com.example","action":"Open the app"}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, invalid.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", invalid.diagnosticCode)
        assertTrue(openedPayloads.isEmpty())
        assertTrue(tool.definition.parameters.getValue("action").enumValues.orEmpty().contains("insert"))
        assertTrue(tool.definition.parameters.containsKey("intent_action"))
        val repaired = """{"op":"open","package_name":"com.example","intent_action":"android.intent.action.MAIN"}"""
        assertEquals(ToolExecutionStatus.SUCCEEDED, tool.execute(repaired).executionStatus)
        assertEquals("android.intent.action.MAIN", Json.parseToJsonElement(openedPayloads.single())
            .jsonObject.getValue("intent_action").jsonPrimitive.content)
    }

    @Test
    fun conflictingCatalogAndExplicitDestinationsNeverReachTheLaunchPort() = runBlocking {
        val tool = registeredTools.getValue(DeviceToolNames.ANDROID_ACT)
        val arguments = """{"op":"open","package_name":"com.example","route_action":"route","uri":"example://first","activity":"com.example/.Destination","intent_action":"example.ACTION"}"""
        val rejected = tool.execute(arguments)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejected.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", rejected.diagnosticCode)
        val receipt = Json.parseToJsonElement(rejected.outputText).jsonObject
        assertTrue(receipt.getValue("retryable").jsonPrimitive.boolean)
        val details = receipt.getValue("details").jsonObject
        assertEquals(setOf("uri", "activity", "intent_action"), details.getValue("conflicting_fields")
            .jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(openedPayloads.isEmpty())
        assertNull(fakeBackend.lastExecutedShellCommand)

        val changedUri = arguments.replace("example://first", "example://second")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, tool.execute(changedUri).executionStatus)
        assertTrue(openedPayloads.isEmpty())

        val catalog = """{"op":"open","package_name":"com.example","route_action":"route","route_params":{"destination":"example"},"launch_mode":"resume"}"""
        val explicit = """{"op":"open","package_name":"com.example","uri":"example://second","intent_action":"example.ACTION","activity":"com.example/.Destination"}"""
        for (chosen in listOf(catalog, explicit)) {
            assertEquals(ToolExecutionStatus.SUCCEEDED, tool.execute(chosen).executionStatus)
            assertEquals(Json.parseToJsonElement(chosen).jsonObject.filterKeys { it != "op" },
                Json.parseToJsonElement(openedPayloads.last()).jsonObject)
        }
        assertEquals(2, openedPayloads.size)
    }

    @Test
    fun catalogOptionsAreRejectedByFieldAndNoAppTargetIsNotDispatched() = runBlocking {
        val tool = registeredTools.getValue(DeviceToolNames.ANDROID_ACT)
        val options = mapOf("categories" to "[\"example.CATEGORY\"]", "mime_type" to "\"text/plain\"",
            "string_extras" to "{\"label\":\"value\"}", "int_extras" to "{\"count\":1}",
            "long_extras" to "{\"time\":2}", "boolean_extras" to "{\"enabled\":false}",
            "expect_screen_change" to "false", "completion_evidence" to "\"platform_contract\"")
        for ((field, value) in options) {
            val result = tool.execute("""{"op":"open","app_name":"Example","route_action":"route","$field":$value}""")
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
            val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
            assertEquals(listOf(field), details.getValue("conflicting_fields").jsonArray.map { it.jsonPrimitive.content })
        }
        val missingTarget = tool.execute("""{"op":"open","route_action":"route"}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, missingTarget.executionStatus)
        assertEquals("route_action_requires_app_target", Json.parseToJsonElement(missingTarget.outputText)
            .jsonObject.getValue("details").jsonObject.getValue("issue").jsonPrimitive.content)
        assertTrue(openedPayloads.isEmpty())
    }

    @Test
    fun nullableOptionalFieldsDoNotConflictWithAnExplicitlyChosenCatalogRoute() = runBlocking {
        val result = registeredTools.getValue(DeviceToolNames.ANDROID_ACT).execute(
            """{"op":"open","package_name":"com.example","route_action":"route","uri":null,"intent_action":null,"activity":null}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(1, openedPayloads.size)
    }

    @Test
    fun explicitComponentIsExposedOnTheProviderWireAndForwardedToTheLaunchPort() = runBlocking {
        val tool = registeredTools.getValue(DeviceToolNames.ANDROID_ACT)
        val request = OpenAIResponsesProtocol.buildRequest(
            model = "test-model", messages = emptyList(), tools = listOf(tool.definition),
            systemPrompt = null, supportsVision = false, maxOutputTokens = null,
            state = OpenAIResponsesTurnState()
        )
        val schema = request.getValue("tools").jsonArray.single().jsonObject
            .getValue("parameters").jsonObject
        assertEquals("string", schema.getValue("properties").jsonObject
            .getValue("activity").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(schema.getValue("required").jsonArray.any { it.jsonPrimitive.content == "activity" })
        val args = """{"op":"open","package_name":"com.example","activity":"com.example/.EditorActivity","launch_mode":"fresh"}"""
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(tool.definition.parameters, args))
        val result = tool.execute(args)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        val forwarded = Json.parseToJsonElement(result.outputText.removePrefix("opened: ")).jsonObject
        assertEquals("com.example/.EditorActivity", forwarded.getValue("activity").jsonPrimitive.content)
        assertEquals("fresh", forwarded.getValue("launch_mode").jsonPrimitive.content)
        assertFalse("op" in forwarded)
        assertTrue(ToolArgumentSchemaValidator.mismatchDetail(tool.definition.parameters,
            """{"op":"open","activity":7}""").orEmpty().contains("field activity expected string"))
    }
}
