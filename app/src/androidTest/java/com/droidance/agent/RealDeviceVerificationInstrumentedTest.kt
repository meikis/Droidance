// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*
import com.droidance.agent.device.*
import com.droidance.memory.MarkdownFileManager
import com.droidance.core.knowledge.skill.SkillEngine
import com.droidance.core.knowledge.tools.KnowledgeToolRegistrar
import com.droidance.core.agent.tools.ToolObservationReference
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises live Android device/network observations and their evidence references, plus
 * on-demand skill loading. Test-created ledger records validate protocol plumbing, not
 * model-driven business completion.
 */
@RunWith(AndroidJUnit4::class)
class RealDeviceVerificationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var fileManager: MarkdownFileManager

    @Before
    fun setUp() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        testDir = File(baseContext.cacheDir, "real-device-test-${UUID.randomUUID()}").also { it.mkdirs() }
        context = object : android.content.ContextWrapper(baseContext) {
            override fun getNoBackupFilesDir(): File = testDir
            override fun getExternalFilesDir(type: String?): File = testDir
        }
        fileManager = MarkdownFileManager(context)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun realDeviceNetworkObservationWiresEvidenceRefAndSatisfiesCompletion() = runBlocking {
        val store = ExecutionContextStore()
        val strategy = DeviceExecutionStrategy(
            DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE)
        )
        store.start(ExecutionContext.fromStrategy("test-turn", "probe", strategy, 0))

        // 1. Read real device platform / network state from physical Android system
        val reader = DeviceInfoReader(context, store)
        val rawDeviceInfo = reader.read()
        assertTrue("Outputs device status header", rawDeviceInfo.startsWith("[ANDROID_DEVICE_RESULT]"))
        val jsonPayload = Json.parseToJsonElement(rawDeviceInfo.substringAfter('\n')).jsonObject
        assertTrue("Model matches hardware", jsonPayload.getValue("model").jsonPrimitive.content == android.os.Build.MODEL)
        assertTrue("Has network addresses field", jsonPayload.containsKey("network_addresses"))

        // 2. Wrap as tool protocol result and generate grounded observation reference
        val toolResult = ToolProtocolResult.success(rawDeviceInfo)
        val groundedResult = ToolObservationReference.forGroundedCall(
            result = toolResult,
            groundsCompletion = true,
            turnId = "turn-real-device-1",
            callId = "call-network-query"
        )
        val observationRef = ToolObservationReference.fromResult(groundedResult)
        assertNotNull("Observation reference is generated for non-screen tool", observationRef)

        // 3. Coordinator tool message formatting
        val toolMessageContent = if (observationRef != null && !groundedResult.outputText.startsWith("[observation id=")) {
            "[observation id=\"$observationRef\" evidence_ref=\"$observationRef\" source=\"observed_state\"]\n${groundedResult.outputText}"
        } else {
            groundedResult.outputText
        }
        assertTrue("Tool message explicitly exposes evidence_ref", toolMessageContent.contains("evidence_ref=\"$observationRef\""))

        // 4. Record into TaskEvidenceLedger
        val ledger = TaskEvidenceLedger()
        ledger.recordObservation(
            observationId = observationRef!!,
            toolName = "android_read",
            rawText = groundedResult.outputText,
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )
        assertTrue("Ledger confirms completion evidence exists", ledger.hasCompletionEvidence(observationRef))
        assertTrue("Available evidence refs contains observationRef", ledger.completionEvidenceReferences().contains(observationRef))

        // 5. Completion claim with correct evidence_ref is accepted
        val successClaimJson = """{"status":"done","results":[{"text":"Wi-Fi and device status captured","evidence_refs":["$observationRef"]}],"unresolved_requirements":[]}"""
        val successExtracted = ActionControlProtocol.extract(listOf(
            com.droidance.core.model.llm.models.ToolCall("done-call", "done", successClaimJson)
        ))
        val successControl = successExtracted.controls.getValue("done-call")
        assertNull("Control has no invalidArgument", successControl.invalidArgument)
        assertTrue("Claims have verified evidence in ledger",
            ActionControlProtocol.completionReferencesValid(successControl, ledger::hasCompletionEvidence))

        // 6. Completion claim with invalid evidence_ref is rejected with available_evidence_refs
        val bogusClaimJson = """{"status":"done","results":[{"text":"Bogus result","evidence_refs":["bogus_ref_404"]}],"unresolved_requirements":[]}"""
        val bogusExtracted = ActionControlProtocol.extract(listOf(
            com.droidance.core.model.llm.models.ToolCall("bogus-call", "done", bogusClaimJson)
        ))
        val bogusControl = bogusExtracted.controls.getValue("bogus-call")
        assertFalse("Bogus claim fails evidence check",
            ActionControlProtocol.completionReferencesValid(bogusControl, ledger::hasCompletionEvidence))

        val availableRefs = ledger.completionEvidenceReferences().split(',').map(String::trim).filter(String::isNotBlank)
        val rejectionIssues = ActionControlProtocol.completionClaimIssues(
            bogusControl, availableRefs, ledger::unresolvedCompletionReferences
        )
        assertEquals("Available refs reported in rejection details",
            JsonArray(listOf(JsonPrimitive(observationRef))), rejectionIssues["available_evidence_refs"])
        assertEquals("Unresolved refs identified",
            JsonArray(listOf(JsonPrimitive("bogus_ref_404"))), rejectionIssues["unresolved_evidence_refs"])
    }

    @Test
    fun realSkillEngineSearchAndLoadOnDemand() = runBlocking {
        val skillEngine = SkillEngine(fileManager, com.droidance.skill.RhinoSkillScriptValidator())

        // Install two sample skills
        installSkill(skillEngine, "wifi-troubleshooter", "Diagnose and resolve Wi-Fi connection issues on Android", "Follow step 1: check network.")
        installSkill(skillEngine, "wechat-helper", "Automate WeChat messaging and chat navigation", "Follow step 1: open WeChat.")

        // 1. Search skills by keyword
        val wifiSkills = skillEngine.searchSkills("wifi")
        assertEquals(1, wifiSkills.size)
        assertEquals("wifi-troubleshooter", wifiSkills[0].name)

        val wechatSkills = skillEngine.searchSkills("wechat")
        assertEquals(1, wechatSkills.size)
        assertEquals("wechat-helper", wechatSkills[0].name)

        val allSkills = skillEngine.searchSkills("*")
        assertTrue("Wildcard lists all skills", allSkills.size >= 2)

        // 2. Test recall_skill tool execution via KnowledgeToolRegistrar
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

        // Test op="search"
        val searchResult = registeredTool!!.execute("""{"op":"search","query":"wifi"}""")
        assertTrue("Search succeeded", searchResult.isVerifiedSuccess)
        val searchJson = Json.parseToJsonElement(searchResult.outputText).jsonObject
        assertEquals(1, searchJson.getValue("count").jsonPrimitive.int)
        assertEquals("wifi-troubleshooter",
            searchJson.getValue("skills").jsonArray[0].jsonObject.getValue("name").jsonPrimitive.content)

        // Test op="load"
        val loadResult = registeredTool!!.execute("""{"op":"load","skill":"wifi-troubleshooter"}""")
        assertTrue("Load succeeded", loadResult.isVerifiedSuccess)
        assertTrue("Contains skill content", loadResult.outputText.contains("Follow step 1: check network."))
    }

    @Test
    fun realDeviceWifiStatusObservationAndCompletionResolution() = runBlocking {
        // Replicates the exact Wi-Fi task turn c106e50e-343a-4e1f-93ca-37a7d702a8fb
        // where cmd wifi status was executed on the device, producing real Wi-Fi network output.
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val wifiRawOutput = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("cmd wifi status")
        ).bufferedReader().use { it.readText() }

        assertTrue("Wi-Fi command produces output on physical device", wifiRawOutput.isNotBlank())
        assertTrue("Contains Wi-Fi status indicators", wifiRawOutput.contains("Wifi is"))

        // Build the observation payload as produced by android_read(op="network") or shell tool
        val observationPayload = buildJsonObject {
            put("source_kind", JsonPrimitive("android_wifi_status"))
            put("scope", JsonPrimitive("device"))
            put("command", JsonPrimitive("cmd wifi status"))
            put("captured_at_ms", JsonPrimitive(System.currentTimeMillis()))
            put("exit_code", JsonPrimitive(0))
            put("stdout", JsonPrimitive(wifiRawOutput.take(16_000)))
            put("stderr", JsonPrimitive(""))
        }.toString()

        val rawToolResult = ToolProtocolResult.success(observationPayload)
        val turnId = "turn-c106e50e-343a-4e1f-93ca-37a7d702a8fb"
        val callId = "call-wifi-read"

        // Step 1: ToolObservationReference attaches a stable observation reference
        val groundedResult = ToolObservationReference.forGroundedCall(
            result = rawToolResult,
            groundsCompletion = true,
            turnId = turnId,
            callId = callId
        )
        val observationRef = ToolObservationReference.fromResult(groundedResult)
        assertNotNull("Observation reference is generated for real Wi-Fi observation", observationRef)

        // Step 2: TaskCoordinator formats the tool message exposing evidence_ref to the model
        val toolMessage = if (observationRef != null && !groundedResult.outputText.startsWith("[observation id=")) {
            "[observation id=\"$observationRef\" evidence_ref=\"$observationRef\" source=\"observed_state\"]\n${groundedResult.outputText}"
        } else {
            groundedResult.outputText
        }
        assertTrue("Exposes evidence_ref for model citation", toolMessage.startsWith("[observation id=\"$observationRef\" evidence_ref=\"$observationRef\""))

        // Step 3: Record into TaskEvidenceLedger
        val ledger = TaskEvidenceLedger()
        ledger.recordObservation(
            observationId = observationRef!!,
            toolName = "android_read",
            rawText = groundedResult.outputText,
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )
        assertTrue("Ledger tracks Wi-Fi observation as valid completion evidence", ledger.hasCompletionEvidence(observationRef))

        // Step 4: Model completes task citing the verified evidence_ref
        val completionClaim = """{"status":"done","results":[{"text":"Wi-Fi connected to SSID and active","evidence_refs":["$observationRef"]}],"unresolved_requirements":[]}"""
        val completionExtracted = ActionControlProtocol.extract(listOf(
            com.droidance.core.model.llm.models.ToolCall("completion-call", "done", completionClaim)
        ))
        val completionControl = completionExtracted.controls.getValue("completion-call")
        assertNull("Control arguments are valid", completionControl.invalidArgument)
        assertTrue("Task completion accepted without invalid retry",
            ActionControlProtocol.completionReferencesValid(completionControl, ledger::hasCompletionEvidence))

        // Step 5: If completion claims a non-existent reference, it provides available_evidence_refs pointing to the Wi-Fi observation
        val faultyCompletionClaim = """{"status":"done","results":[{"text":"Wi-Fi info","evidence_refs":["missing_ref"]}],"unresolved_requirements":[]}"""
        val faultyExtracted = ActionControlProtocol.extract(listOf(
            com.droidance.core.model.llm.models.ToolCall("faulty-call", "done", faultyCompletionClaim)
        ))
        val faultyControl = faultyExtracted.controls.getValue("faulty-call")
        assertFalse("Faulty completion is rejected",
            ActionControlProtocol.completionReferencesValid(faultyControl, ledger::hasCompletionEvidence))

        val availableEvidence = ledger.completionEvidenceReferences().split(',').map(String::trim).filter(String::isNotBlank)
        val issues = ActionControlProtocol.completionClaimIssues(
            faultyControl, availableEvidence, ledger::unresolvedCompletionReferences
        )
        assertTrue("Diagnostic points model to valid Wi-Fi evidence ref",
            issues["available_evidence_refs"]?.jsonArray?.contains(JsonPrimitive(observationRef)) == true)
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
