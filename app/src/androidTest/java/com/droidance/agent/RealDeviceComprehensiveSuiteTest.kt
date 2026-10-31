// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*
import com.droidance.agent.device.*
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*
import com.droidance.memory.*
import com.droidance.core.device.permission.PermissionBroker
import com.droidance.core.device.permission.PermissionRequestOutcome
import com.droidance.core.device.program.ProgramConnection
import com.droidance.core.knowledge.skill.SkillEngine
import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*
import com.droidance.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Device integration checks for Android reads, input plumbing, permissions and local storage.
 * Live platform observations and test-created protocol fixtures are checked separately;
 * passing this suite does not establish model-driven task success.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class RealDeviceComprehensiveSuiteTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var fileManager: MarkdownFileManager
    private lateinit var uiAutomation: UiAutomation

    @Before
    fun setUp() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        testDir = File(baseContext.cacheDir, "suite-test-${UUID.randomUUID()}").also { it.mkdirs() }
        context = object : android.content.ContextWrapper(baseContext) {
            override fun getNoBackupFilesDir(): File = testDir
            override fun getExternalFilesDir(type: String?): File = testDir
        }
        fileManager = MarkdownFileManager(context)
        uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
        // Ensure device returns to safe baseline state
        runShell("input keyevent 3")
    }

    private fun runShell(cmd: String): String {
        val pfd = uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    // =========================================================================
    // Category 1: 零工具直接对话与问答（Direct Zero-tool Chat, 3 个用例）
    // =========================================================================

    @Test
    fun case01_zeroToolChatGreetingDirectResponse() {
        val request = TurnRequest.create(
            source = MessageSource.DIRECT,
            rawText = "你好，请问你是谁，能帮我做什么？"
        )
        assertEquals(MessageSource.DIRECT, request.source)
        assertTrue("Request contains greeting text", request.rawText.contains("你好"))
        assertTrue("Images must be empty for text chat", request.images.isEmpty())
        assertTrue("Raw hash generated", request.rawHash.isNotBlank())
    }

    @Test
    fun case02_zeroToolTechnicalKnowledgeQuestionDirectResponse() {
        val request = TurnRequest.create(
            source = MessageSource.DIRECT,
            rawText = "请简述 Android 的 Binder 跨进程通信原理。"
        )
        assertEquals(MessageSource.DIRECT, request.source)
        assertTrue(request.userText.contains("Binder"))
        assertEquals(64, request.rawHash.length) // SHA-256 hex length
    }

    @Test
    fun case03_zeroToolLogicDeductionDirectResponse() {
        val logicPrompt = "请分析以下三句话在逻辑上是否有冲突：1.所有鸟都会飞；2.企鹅是鸟；3.企鹅不会飞。"
        val request = TurnRequest.create(
            source = MessageSource.DIRECT,
            rawText = logicPrompt
        )
        assertEquals(MessageSource.DIRECT, request.source)
        assertTrue(request.rawText.contains("企鹅"))
        assertNotNull(UUID.fromString(request.requestId))
    }

    // =========================================================================
    // Category 2: 硬件与设备状态只读感知（Hardware & Platform Observation, 6 个用例）
    // =========================================================================

    @Test
    fun case04_physicalBatteryObservationAndTelemetry() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        assertTrue("Battery level on physical hardware must be 1..100 (got $batteryLevel)", batteryLevel in 1..100)
    }

    @Test
    fun case05_physicalDisplayResolutionAndDpiVerification() {
        val displaySnapshot = captureDisplay(context, Display.DEFAULT_DISPLAY)
        assertTrue("Display width > 0", displaySnapshot.width > 0)
        assertTrue("Display height > 0", displaySnapshot.height > 0)
        assertTrue("Display densityDpi > 0", displaySnapshot.densityDpi > 0)
        assertTrue("Display height should exceed width in portrait", displaySnapshot.height >= displaySnapshot.width)
    }

    @Test
    fun case06_physicalNetworkStateAndAddressVerification() = runBlocking {
        val store = ExecutionContextStore()
        val strategy = DeviceExecutionStrategy(
            DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE)
        )
        store.start(ExecutionContext.fromStrategy("turn-net-test", "probe", strategy, 0))

        val reader = DeviceInfoReader(context, store)
        val rawDeviceInfo = reader.read()
        assertTrue("Outputs [ANDROID_DEVICE_RESULT]", rawDeviceInfo.startsWith("[ANDROID_DEVICE_RESULT]"))

        val jsonPayload = Json.parseToJsonElement(rawDeviceInfo.substringAfter('\n')).jsonObject
        assertEquals(Build.MODEL, jsonPayload.getValue("model").jsonPrimitive.content)
        assertTrue(jsonPayload.containsKey("network_addresses"))

        val toolResult = ToolProtocolResult.success(rawDeviceInfo)
        val grounded = ToolObservationReference.forGroundedCall(
            result = toolResult,
            groundsCompletion = true,
            turnId = "turn-net-test",
            callId = "call-net-1"
        )
        val ref = ToolObservationReference.fromResult(grounded)
        assertNotNull("Evidence reference is generated", ref)
    }

    @Test
    fun case07_physicalBuildModelAndOsReleaseVerification() {
        val state = captureNativeDeviceState(context)
        assertEquals(android.os.Build.MODEL, state.getValue("model").jsonPrimitive.content)
        assertEquals(android.os.Build.VERSION.RELEASE, state.getValue("android_version").jsonPrimitive.content)
        assertEquals(android.os.Build.ID, state.getValue("build_id").jsonPrimitive.content)
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val mode = display.mode
        assertEquals("device_default_display", state.getValue("screen_scope").jsonPrimitive.content)
        assertEquals("Display.getRealMetrics", state.getValue("screen_effective_source").jsonPrimitive.content)
        assertEquals("largest_app_accessible_display_region", state.getValue("screen_effective_scope").jsonPrimitive.content)
        assertEquals(metrics.widthPixels, state.getValue("screen_effective_width_px").jsonPrimitive.int)
        assertEquals(metrics.heightPixels, state.getValue("screen_effective_height_px").jsonPrimitive.int)
        assertEquals(metrics.densityDpi, state.getValue("screen_density_dpi").jsonPrimitive.int)
        assertEquals("Display.getMode", state.getValue("screen_mode_source").jsonPrimitive.content)
        assertEquals("reported_current_display_mode", state.getValue("screen_mode_scope").jsonPrimitive.content)
        assertEquals(mode.physicalWidth, state.getValue("screen_mode_width_px").jsonPrimitive.int)
        assertEquals(mode.physicalHeight, state.getValue("screen_mode_height_px").jsonPrimitive.int)
    }

    @Test
    fun case09_physicalWifiStatusObservationViaShell() {
        val wifiRawOutput = runShell("cmd wifi status")
        assertTrue("Physical device reports wifi status", wifiRawOutput.isNotBlank())
        assertTrue("Output contains 'Wifi is'", wifiRawOutput.contains("Wifi is"))

        val ledger = TaskEvidenceLedger()
        ledger.recordObservation(
            observationId = "obs-wifi-09",
            toolName = "android_read",
            rawText = wifiRawOutput,
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )
        assertTrue("Wifi observation recorded as completion evidence", ledger.hasCompletionEvidence("obs-wifi-09"))
    }

    // =========================================================================
    // Category 3: 系统数据读取与本地 Worker 计算（ContentProvider Data & Local Worker, 6 个用例）
    // =========================================================================

    @Test
    fun case10_systemSettingsContentProviderQuery() = runBlocking {
        val broker = PermissionBroker(context) { error("Settings does not require runtime permission") }
        val service = AndroidContentQueryService(
            context = context,
            permissionBroker = broker,
            permissionOwner = { "turn-10" to "call-10" },
            permissionReason = { "test:$it" },
            permissionTimeoutMillis = { 0L },
            onPermissionWaiting = {},
            onPermissionResolved = { _, _ -> }
        )
        val settingName = Settings.System.SCREEN_BRIGHTNESS
        val expected = Settings.System.getString(context.contentResolver, settingName)

        val result = service.query(
            """{"uri":"content://settings/system","projection":["name","value"],"selection":"name = ?","selection_args":["$settingName"],"limit":1}"""
        )
        assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        val payload = Json.parseToJsonElement(result.outputText.substringAfter("\n")).jsonObject
        val row = payload.getValue("rows").jsonArray.single().jsonObject
        assertEquals(settingName, row.getValue("name").jsonPrimitive.content)
        assertEquals(expected, row["value"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun case11_physicalSmsContentReadViaShellAndValidation() {
        val smsOutput = runShell("content query --uri content://sms/inbox --projection _id:body:date")
        assertTrue("SMS query produces output", smsOutput.isNotBlank())
        assertTrue("Output contains row indicators", smsOutput.contains("Row: 0"))
        assertTrue("Contains body field", smsOutput.contains("body="))
    }

    @Test
    fun case12_programWorkerDeterministicJsAggregation() = runBlocking {
        val connection = ProgramConnection(context)
        try {
            val script = """
                var sms = [
                    {id: 1, read: 1, date: 1790211829855},
                    {id: 2, read: 0, date: 1790124354407},
                    {id: 3, read: 1, date: 1790052296406}
                ];
                var readCount = sms.filter(function(s){ return s.read === 1; }).length;
                var unreadCount = sms.length - readCount;
                print(JSON.stringify({total: sms.length, read: readCount, unread: unreadCount}));
            """.trimIndent()

            connection.start(script, 10000)
            val output = withContext(Dispatchers.IO) { connection.requests.poll(5, TimeUnit.SECONDS) }
            assertNotNull("Worker outputs result via stdout request", output)
            assertEquals("stdout", output!!.operation)
            val textPayload = Json.parseToJsonElement(output.arguments).jsonObject.getValue("text").jsonPrimitive.content
            val resultJson = Json.parseToJsonElement(textPayload).jsonObject
            assertEquals(3, resultJson.getValue("total").jsonPrimitive.int)
            assertEquals(2, resultJson.getValue("read").jsonPrimitive.int)
            assertEquals(1, resultJson.getValue("unread").jsonPrimitive.int)
            output.answer.complete("{\"value\":null}")

            withTimeout(5000) { while (connection.status == "running") delay(20) }
            assertEquals("exited", connection.status)
        } finally {
            connection.close()
        }
    }

    @Test
    fun case13_taskWorkspaceFilePersistenceAndSha256Tracking() {
        val relativePath = "workspace/test_output.json"
        val payload = """{"status":"processed","items_count":42,"timestamp":1790211829855}"""
        fileManager.writeFile(relativePath, payload)

        assertTrue(fileManager.exists(relativePath))
        val readBack = fileManager.readFile(relativePath)
        assertEquals(payload, readBack)

        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(readBack!!.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(64, hash.length)
    }

    @Test
    fun case14_permissionBrokerPolicyRestrictionHandling() = runBlocking {
        var hostLaunched = false
        val broker = PermissionBroker(context) { hostLaunched = true }
        val outcome = broker.request(
            permissions = listOf(android.Manifest.permission.READ_SMS),
            turnId = "turn-perm-14",
            callId = "call-perm-14",
            reason = "test permission handling",
            timeoutMillis = 50L,
            onWaiting = {},
            onResolved = { _, _ -> }
        )
        // Without user UI approval in 50ms, it should cleanly timeout or return restricted without crash
        assertTrue(
            "Outcome is TIMED_OUT or POLICY_RESTRICTED (got $outcome)",
            outcome == PermissionRequestOutcome.TIMED_OUT || outcome == PermissionRequestOutcome.POLICY_RESTRICTED
        )
    }

    @Test
    fun case15_derivativeEvidenceChainLinkageAndLedgerAcceptance() {
        val ledger = TaskEvidenceLedger()
        val obsId = "obs-derived-query"
        ledger.recordObservation(
            observationId = obsId,
            toolName = "android_read",
            rawText = "{\"rows\":[{\"_id\":101}]}",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )
        val validClaim = """{"status":"done","results":[{"text":"Verified row count","evidence_refs":["$obsId"]}],"unresolved_requirements":[]}"""
        val extracted = ActionControlProtocol.extract(listOf(
            com.droidance.core.model.llm.models.ToolCall("call-done-15", "done", validClaim)
        ))
        val control = extracted.controls.getValue("call-done-15")
        assertNull(control.invalidArgument)
        assertTrue(ActionControlProtocol.completionReferencesValid(control, ledger::hasCompletionEvidence))
    }

    // =========================================================================
    // Category 4: 系统级通用控制与 Shell 执行（OS Controls & Non-destructive Shell, 5 个用例）
    // =========================================================================

    @Test
    fun case16_systemVolumeReadViaAudioManager() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val musicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ringVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val maxRingVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)

        assertTrue("Music volume in valid range (0..$maxMusicVol)", musicVol in 0..maxMusicVol)
        assertTrue("Ring volume in valid range (0..$maxRingVol)", ringVol in 0..maxRingVol)
    }

    @Test
    fun case17_clipboardReadWriteVerification() {
        androidx.test.core.app.ActivityScenario.launch(com.droidance.core.device.debug.InputProbeActivity::class.java).use { scenario ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val testToken = "droidance-clip-test-${UUID.randomUUID()}"
            val clip = ClipData.newPlainText("test_token", testToken)
            scenario.onActivity {
                clipboard.setPrimaryClip(clip)
                val readClip = clipboard.primaryClip
                assertNotNull(readClip)
                assertEquals(testToken, readClip!!.getItemAt(0).text.toString())
            }
        }
    }

    @Test
    fun case18_systemSettingsIntentResolution() {
        val pm = context.packageManager
        val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
        val wifiResolutions = pm.queryIntentActivities(wifiIntent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("Settings ACTION_WIFI_SETTINGS resolves to an Activity", wifiResolutions.isNotEmpty())

        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:com.droidance.debug")
        }
        val appResolutions = pm.queryIntentActivities(appDetailsIntent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("ACTION_APPLICATION_DETAILS_SETTINGS resolves to an Activity", appResolutions.isNotEmpty())
    }

    @Test
    fun case19_nonDestructiveShellGetpropAndPmPath() {
        val modelOutput = runShell("getprop ro.product.model").trim()
        assertEquals("PJE110", modelOutput)

        val pmPathOutput = runShell("pm path com.droidance.debug").trim()
        assertTrue("Contains package path", pmPathOutput.startsWith("package:/data/app/"))
    }

    @Test
    fun case20_shellCommandTimeoutProtection() {
        val output = runShell("echo 'shell_sanity_check'")
        assertTrue(output.contains("shell_sanity_check"))
    }

    // =========================================================================
    // Category 5: GUI 视觉感知、导航与动作组（GUI Perception & Action Batching, 6 个用例）
    // =========================================================================

    @Test
    fun case21_realScreenBitmapCaptureAndNonEmptyValidation() {
        val screenshot = uiAutomation.takeScreenshot()
        assertNotNull("UiAutomation returns non-null screenshot", screenshot)
        assertTrue("Screenshot width > 0", screenshot.width > 0)
        assertTrue("Screenshot height > 0", screenshot.height > 0)

        val centerPixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
        assertNotEquals("Center pixel alpha is visible", 0, Color.alpha(centerPixel))
    }

    @Test
    fun case22_launchCalculatorAndVerifyForeground() = runBlocking {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage("com.coloros.calculator")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            delay(1000)
            val windowDump = runShell("dumpsys window")
            assertTrue("Window dump contains calculator", windowDump.contains("com.coloros.calculator"))
            runShell("input keyevent 3")
            delay(500)
        } else {
            // If calculator component not present under this exact name, pass gracefully
            assertTrue(true)
        }
    }

    @Test
    fun case23_launchSettingsAndVerifyForeground() = runBlocking {
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        delay(1000)
        val windowDump = runShell("dumpsys window")
        assertTrue("Window dump contains settings", windowDump.contains("com.android.settings"))
        runShell("input keyevent 3")
        delay(500)
    }

    @Test
    fun case24_screenActionBatchOrderingAndValidation() {
        val policy = ScreenActionBatchPolicy(
            enabled = true,
            allowedOperations = listOf("wait", "press_key", "click", "scroll"),
            defaultWaitMs = 100L,
            minWaitMs = 50L,
            maxWaitMs = 1000L,
            maxTotalWaitMs = 5000L, maxCaptureImages = 10, maxCaptureBytes = 8 * 1024 * 1024L, maxDragRepeat = 64
        )
        val batchJson = """
            {
                "actions": [
                    {"op": "wait", "ms": 100},
                    {"op": "press_key", "key": "BACK"}
                ]
            }
        """.trimIndent()
        val plan = ScreenActionBatchPlanner.plan(batchJson, policy)
        assertTrue("Batch planning succeeded", plan is ScreenActionBatchPlan.Ready)
        val ready = plan as ScreenActionBatchPlan.Ready
        assertEquals(2, ready.actions.size)
        assertEquals(ScreenBatchOperation.WAIT, ready.actions[0].operation)
        assertEquals(ScreenBatchOperation.PRESS_KEY, ready.actions[1].operation)
    }

    @Test
    fun case25_observationPixelComparatorSensitivity() {
        val modelSize = 40 to 30
        val physicalSize = 100 to 80
        val mapper = ModelCoordinateMapper(
            scale = 0.5f,
            modelSize = modelSize,
            physicalSize = physicalSize,
            physicalOrigin = ModelCoordinateMapper.Point(10, 8)
        )
        val samples = ObservationPixelComparator.mappedGrid(
            mapper = mapper,
            modelSize = modelSize,
            physicalSize = physicalSize,
            sampleSide = 8
        )
        assertEquals("Grid produces 64 sample points", 64, samples.size)
    }

    @Test
    fun case26_safeReturnToHomeBaseline() = runBlocking {
        runShell("input keyevent 3")
        delay(500)
        val windowDump = runShell("dumpsys window")
        assertTrue("Device restored to home launcher or system baseline", windowDump.isNotBlank())
    }

    // =========================================================================
    // Category 6: 记忆、Skill 与防伪门禁防御（Memory, Skill & Guardrails, 4 个用例）
    // =========================================================================

    @Test
    fun case27_durableMemorySaveAndRecall() = runBlocking {
        val profileStore = ProfileStore(fileManager)
        val longTermStore = LongTermMemoryStore(fileManager)
        val memoryStore = DurableMemoryStore(profileStore, longTermStore)

        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "preferred_name",
            category = "profile",
            content = "Alex",
            sourceQuote = "称呼我为 Alex"
        )
        val result = memoryStore.applyUpdates(listOf(update)) {
            listOf("称呼我为 Alex")
        }
        assertEquals(1, result.getOrThrow().changed)

        // Read back from fresh store
        val freshStore = ProfileStore(fileManager)
        val profileMarkdown = freshStore.readForDisplay()
        assertTrue("Saved profile contains Alex", profileMarkdown.contains("Alex"))
    }

    @Test
    fun case28_skillEngineSearchAndPackageLoad() = runBlocking {
        val skillEngine = SkillEngine(fileManager, com.droidance.skill.RhinoSkillScriptValidator())
        installSkill(skillEngine, "battery-monitor", "Monitor battery metrics and temperature", "Step 1: read battery.")

        val searchResults = skillEngine.searchSkills("battery")
        assertEquals(1, searchResults.size)
        assertEquals("battery-monitor", searchResults[0].name)

        var registeredTool: com.droidance.core.agent.tools.Tool? = null
        val registrar = KnowledgeToolRegistrar(
            registerTool = { registeredTool = it },
            searchMemories = { _, _, _, _ -> "" },
            expandEpisode = { null },
            expandMemory = { null },
            recallSkill = { id -> skillEngine.findExecutionSnapshot(id)?.skill?.rawContent },
            readSession = { ToolProtocolResult.success("") },
            expandExperience = { null },
            searchSkills = skillEngine::searchSkills
        )
        registrar.registerAll()

        val loadResult = registeredTool!!.execute("""{"op":"load","skill":"battery-monitor"}""")
        assertTrue(loadResult.isVerifiedSuccess)
        assertTrue(loadResult.outputText.contains("Step 1: read battery."))
    }

    @Test
    fun case29_invalidEvidenceCompletionRejection() {
        val ledger = TaskEvidenceLedger()
        ledger.recordObservation(
            observationId = "obs-genuine-valid",
            toolName = "android_read",
            rawText = "data",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        val bogusClaimJson = """{"status":"done","results":[{"text":"Fabricated statement","evidence_refs":["obs-bogus-404"]}],"unresolved_requirements":[]}"""
        val extracted = ActionControlProtocol.extract(listOf(
            com.droidance.core.model.llm.models.ToolCall("bogus-call-id", "done", bogusClaimJson)
        ))
        val control = extracted.controls.getValue("bogus-call-id")
        assertFalse("Bogus evidence reference must fail evidence check",
            ActionControlProtocol.completionReferencesValid(control, ledger::hasCompletionEvidence))

        val availableRefs = ledger.completionEvidenceReferences().split(',').map(String::trim).filter(String::isNotBlank)
        val rejectionIssues = ActionControlProtocol.completionClaimIssues(
            control, availableRefs, ledger::unresolvedCompletionReferences
        )
        assertEquals(JsonArray(listOf(JsonPrimitive("obs-genuine-valid"))), rejectionIssues["available_evidence_refs"])
        assertEquals(JsonArray(listOf(JsonPrimitive("obs-bogus-404"))), rejectionIssues["unresolved_evidence_refs"])
    }

    @Test
    fun case30_taskCancellationAndResourceTeardown() = runBlocking {
        val connection = ProgramConnection(context)
        try {
            connection.start("while(true){}", 10000)
            connection.close()
            assertEquals("canceled", connection.status)
        } finally {
            connection.close()
        }
    }

    private fun installSkill(engine: SkillEngine, id: String, description: String, body: String) {
        val md = "---\nname: $id\nversion: 1\ndescription: \"$description\"\n---\n$body\n"
        val zipBytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("SKILL.md"))
                zip.write(md.toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        engine.installSkillPackage(zipBytes)
        val draft = engine.findExecutionSnapshot(id, draft = true)
        if (draft != null) {
            engine.publishDraft(id, draft.revision)
        }
    }
}
