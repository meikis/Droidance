// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.TurnSessionContext
import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.event.AgentEventDraft
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.core.knowledge.skill.SkillDocumentStorage
import com.droidance.core.knowledge.skill.SkillEngine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class SkillToolRegistrarTest {
    @Test fun guidedAssessmentSurvivesPolicyToggleAndSettlesTheRunWithoutRepeatingInstructions() = runBlocking {
        withFixture { fixture ->
            val draft = fixture.engine.saveDraft(skillMarkdown())
            fixture.engine.publishDraft(draft.id, draft.revision)
            val started = fixture.call("run_skill", buildJsonObject {
                put("name", draft.name); put("revision", draft.revision); put("checkpoint_key", "guided")
            })
            assertTrue(started.isVerifiedSuccess)
            val result = Json.parseToJsonElement(started.outputText).jsonObject
            assertFalse(result.containsKey("instructions"))
            assertEquals(draft.revision, result["instructions_ref"]!!.jsonObject["revision"]!!.jsonPrimitive.content)
            fixture.engine.setImplicitInvocation(draft.id, false)
            fixture.programEvent("""{"phase":"device_finished","data":{"image_ref":"observed-result"},
                "artifacts":[{"content_ref":"observed-result","mime_type":"image/png"}]}""")
            val assessed = fixture.call("verify_skill", buildJsonObject {
                put("run_id", result["skill_run_id"]!!); put("outcome", "succeeded")
                put("summary", "Checked the current observed result.")
                put("evidence_refs", JsonArray(listOf(JsonPrimitive("observed-result"))))
            })
            assertTrue(assessed.outputText, assessed.isVerifiedSuccess)
            assertEquals("ended", fixture.engine.executionRecord(draft.id, "guided")!!["state"]!!.jsonPrimitive.content)
            assertEquals(1, fixture.engine.assessments(draft.id, false).size)
            fixture.owner.clear()
            assertEquals("succeeded", fixture.engine.executionRecord(draft.id, "guided")!!["assessment_outcome"]!!.jsonPrimitive.content)
        }
    }

    @Test fun unknownCanBeRecordedWithoutInventingObservationEvidence() = runBlocking { withFixture { fixture ->
        val draft = fixture.engine.saveDraft(skillMarkdown())
        val run = fixture.call("run_skill", buildJsonObject {
            put("name", draft.name); put("revision", draft.revision); put("draft", true); put("checkpoint_key", "unknown")
        })
        val runId = Json.parseToJsonElement(run.outputText).jsonObject["skill_run_id"]!!
        val result = fixture.call("verify_skill", buildJsonObject {
            put("run_id", runId); put("outcome", "unknown"); put("summary", "No result observation was obtained.")
            put("evidence_refs", JsonArray(emptyList()))
        })
        assertTrue(result.outputText, result.isVerifiedSuccess)
        assertEquals("unknown", fixture.engine.executionRecord(draft.id, "unknown")!!["assessment_outcome"]!!.jsonPrimitive.content)
    } }

    @Test fun pendingItemNeedsCurrentExternalEvidenceAndReconciliationNeverRunsTheProgram() = runBlocking {
        withFixture { fixture ->
            val skill = fixture.engine.saveDraft(skillMarkdown(), mapOf("scripts/main.js" to "print('result');"))
            val snapshot = fixture.engine.findExecutionSnapshot(skill.id, true)!!
            val token = fixture.engine.beginExecution(snapshot, kotlinx.serialization.json.JsonObject(emptyMap()), "recover",
                entryPath = "scripts/main.js", sourceThreadId = fixture.owner.threadId, sourceTurnId = fixture.owner.turnId)
            fixture.engine.checkpoint(token, "item", "begin", "original-worker")
            val started = fixture.programEvent("""{"phase":"device_started","data":{"handle":"original-worker","child_id":"child","tool":"android_act","arguments_ref":"args"}}""")
            fixture.engine.recordCheckpointCall(token, "child", "android_act", "args", started.eventId, "started")
            fixture.programEvent("""{"phase":"device_finished","data":{"image_ref":"before-terminal"},"artifacts":[{"content_ref":"before-terminal","mime_type":"image/png"}]}""")
            fixture.programEvent("""{"phase":"terminal","data":{"handle":"original-worker","status":"interrupted","stdout_ref":"local-output"},"artifacts":[{"content_ref":"local-output","mime_type":"text/plain"}]}""")
            fixture.engine.settleExecution(token, "interrupted", "original-worker")

            suspend fun reconcile(decision: String, vararg refs: String) = fixture.call("run_skill", buildJsonObject {
                put("name", skill.name); put("revision", snapshot.revision); put("draft", true); put("checkpoint_key", "recover")
                put("reconcile", buildJsonObject {
                    put("item_key", "item"); put("decision", decision); put("summary", "Compared original call and current result.")
                    put("evidence_refs", JsonArray(refs.map(::JsonPrimitive)))
                })
            })

            assertEquals(ToolExecutionStatus.FAILED, reconcile("completed", "before-terminal").executionStatus)
            assertEquals(ToolExecutionStatus.FAILED, reconcile("completed", "local-output").executionStatus)
            assertEquals(ToolExecutionStatus.FAILED, reconcile("completed").executionStatus)
            assertTrue(reconcile("unknown").isVerifiedSuccess)
            assertEquals("pending", fixture.engine.checkpoint(token, "item", "read")["state"]!!.jsonPrimitive.content)
            fixture.engine.saveDraft(skillMarkdown() + "\nRevised recovery instructions.\n", expectedRevision = snapshot.revision)
            val inspected = fixture.call("run_skill", buildJsonObject {
                put("name", skill.name); put("revision", snapshot.revision); put("checkpoint_key", "recover"); put("inspect_only", true)
            })
            assertTrue(inspected.outputText, inspected.isVerifiedSuccess)
            fixture.programEvent("""{"phase":"device_finished","data":{"image_ref":"current-result"},"artifacts":[{"content_ref":"current-result","mime_type":"image/png"}]}""")
            val confirmed = reconcile("completed", "current-result")
            assertTrue(confirmed.outputText, confirmed.isVerifiedSuccess)
            assertEquals("completed", fixture.engine.checkpoint(token, "item", "read")["state"]!!.jsonPrimitive.content)
            assertEquals(0, fixture.programCalls)
        }
    }

    @Test fun canceledBeforeDispatchCanBeReconciledWithoutDiscardingItsOriginalReceipt() = runBlocking {
        withFixture { fixture ->
            val skill = fixture.engine.saveDraft(skillMarkdown(), mapOf("scripts/main.js" to "print('result');"))
            val snapshot = fixture.engine.findExecutionSnapshot(skill.id, true)!!
            val token = fixture.engine.beginExecution(snapshot, kotlinx.serialization.json.JsonObject(emptyMap()), "not-dispatched",
                entryPath = "scripts/main.js", sourceThreadId = fixture.owner.threadId, sourceTurnId = fixture.owner.turnId)
            fixture.engine.checkpoint(token, "item", "begin", "worker")
            val data = "\"handle\":\"worker\",\"child_id\":\"child\",\"tool\":\"android_act\",\"arguments_ref\":\"args\""
            val started = fixture.programEvent("""{"phase":"device_started","data":{$data}}""")
            fixture.engine.recordCheckpointCall(token, "child", "android_act", "args", started.eventId, "started")
            val stopped = fixture.programEvent("""{"phase":"device_not_executed","data":{$data,"dispatched":false}}""")
            fixture.engine.recordCheckpointCall(token, "child", "android_act", "args", stopped.eventId, "failed")
            fixture.programEvent("""{"phase":"terminal","data":{"handle":"worker","status":"canceled"}}""")
            fixture.engine.settleExecution(token, "canceled", "worker")
            fixture.programEvent("""{"phase":"device_finished","data":{"image_ref":"current"},"artifacts":[{"content_ref":"current","mime_type":"image/png"}]}""")
            val result = fixture.call("run_skill", buildJsonObject {
                put("name", skill.name); put("revision", snapshot.revision); put("checkpoint_key", "not-dispatched")
                put("reconcile", buildJsonObject {
                    put("item_key", "item"); put("decision", "not_executed"); put("summary", "Current result and original non-dispatch agree.")
                    put("evidence_refs", JsonArray(listOf(JsonPrimitive("current"))))
                })
            })
            assertTrue(result.outputText, result.isVerifiedSuccess)
            assertEquals("absent", fixture.engine.checkpoint(token, "item", "read")["state"]!!.jsonPrimitive.content)
            assertTrue(result.outputText.contains(stopped.eventId))
            assertEquals(0, fixture.programCalls)
        }
    }

    @Test fun stdoutIsComputationEvidenceOnlyForTheStartedSkillScript() = runBlocking {
        withFixture(scripted = true) { fixture ->
            val draft = fixture.engine.saveDraft(skillMarkdown(), mapOf("scripts/main.js" to "print(2 + 2);"))
            val run = fixture.call("run_skill", buildJsonObject {
                put("name", draft.name); put("revision", draft.revision); put("draft", true)
                put("checkpoint_key", "script"); put("path", "scripts/main.js")
            })
            val runId = Json.parseToJsonElement(run.outputText).jsonObject["skill_run_id"]!!
            fixture.programEvent("""{"phase":"terminal","data":{"handle":"another","status":"exited","stdout_ref":"unrelated"},
                "artifacts":[{"content_ref":"unrelated","mime_type":"text/plain"}]}""")
            suspend fun assess(kind: String, ref: String) = fixture.call("verify_skill", buildJsonObject {
                put("run_id", runId); put("outcome", "succeeded"); put("summary", "Checked the result.")
                put("evidence_kind", kind); put("evidence_refs", JsonArray(listOf(JsonPrimitive(ref))))
            })
            assertEquals(ToolExecutionStatus.FAILED, assess("external", "script-output").executionStatus)
            assertEquals(ToolExecutionStatus.FAILED, assess("computation", "unrelated").executionStatus)
            val calculated = assess("computation", "script-output")
            assertTrue(calculated.outputText, calculated.isVerifiedSuccess)
            val assessment = Json.parseToJsonElement(fixture.engine.assessments(draft.id, true).single()).jsonObject
            assertEquals("computation", assessment["evidence_kind"]!!.jsonPrimitive.content)
        }
    }

    @Test fun filePagesAvoidRepeatedAssessmentsAndDetailsRemainRetrievable() = runBlocking { withFixture { fixture ->
        fixture.engine.saveDraft(skillMarkdown(), mapOf("references/long.md" to "x".repeat(13000)))
        val snapshot = fixture.engine.findExecutionSnapshot("demo", true)!!
        fixture.engine.recordAssessment(snapshot, buildJsonObject {
            put("summary", "detail ".repeat(100)); put("outcome", "unknown")
        })
        suspend fun read(path: String, offset: Int = 0, mode: String = "file") = fixture.call("read_skill_file", buildJsonObject {
            put("name", "demo"); put("draft", true); put("revision", snapshot.revision)
            put("path", path); put("offset", offset); put("mode", mode)
        })
        val summary = Json.parseToJsonElement(read("SKILL.md").outputText).jsonObject["assessment_summary"]!!.toString()
        assertTrue(summary.length < 500)
        val page = read("references/long.md", 12000)
        assertFalse(page.outputText.contains("assessment_summary"))
        assertEquals(1000L, page.metrics["skill_read_chars"])
        val details = read("SKILL.md", mode = "assessments")
        val original = Json.parseToJsonElement(details.outputText).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(original.contains("detail ".repeat(100)))
        assertEquals(1L, details.metrics["skill_assessment_reads"])
    } }

    @Test
    fun realRegistrarExposesPackageAuthoringAndExecutionThroughCataloguedTools() = runBlocking { withFixture { fixture ->
        assertEquals(setOf("save_skill", "read_skill_file", "skill_source", "run_skill", "verify_skill"), fixture.tools.keys)
        fixture.tools.forEach { (name, tool) ->
            assertEquals(name, tool.definition.name)
            assertTrue("Registered tool must be declared in the operator contract: $name",
                name in ToolCatalog.operatorToolNames)
        }
        listOf("compile_recipe", "run_script", "list_scripts", DeviceToolNames.SCREEN_ACT,
            DeviceToolNames.ANDROID_ACT, DeviceToolNames.SHELL).forEach { retiredOrDevice ->
            assertFalse(fixture.tools.containsKey(retiredOrDevice))
        }
    } }

    @Test
    fun saveAndReadUseTheExactRevisionFromTheRealPackage() = runBlocking { withFixture { fixture ->
        val markdown = skillMarkdown()
        val saved = fixture.call("save_skill", buildJsonObject {
            put("markdown", markdown)
            put("files", buildJsonObject {
                put("scripts/workflow.js", "print('real package');")
                put("references/guide.md", "Read the current task evidence.")
            })
        })
        assertTrue(saved.isVerifiedSuccess)
        val snapshot = requireNotNull(fixture.engine.findExecutionSnapshot("demo", true))
        val response = Json.parseToJsonElement(saved.outputText).jsonObject
        assertEquals(snapshot.revision, response["revision"]!!.jsonPrimitive.content)

        val read = fixture.call("read_skill_file", buildJsonObject {
            put("name", "demo")
            put("path", "scripts/workflow.js")
            put("draft", true)
            put("revision", snapshot.revision)
        })
        assertTrue(read.isVerifiedSuccess)
        assertTrue(read.outputText.contains("print('real package');"))
        assertTrue(read.outputText.contains(snapshot.revision))

        val stale = fixture.call("read_skill_file", buildJsonObject {
            put("name", "demo")
            put("draft", true)
            put("revision", "0".repeat(64))
        })
        assertEquals(ToolExecutionStatus.FAILED, stale.executionStatus)
        assertTrue(stale.outputText.contains("SKILL_REVISION_CHANGED"))
    } }

    @Test
    fun nonObjectFilesAndParametersAreRejectedBeforeExecution() = runBlocking { withFixture { fixture ->
        val badFiles = fixture.call("save_skill", buildJsonObject {
            put("markdown", skillMarkdown())
            put("files", JsonPrimitive("must be an object"))
        })
        assertEquals(ToolExecutionStatus.FAILED, badFiles.executionStatus)
        assertTrue(badFiles.outputText.contains("SKILL_FILES_INVALID"))

        val saved = fixture.call("save_skill", buildJsonObject {
            put("markdown", skillMarkdown(parameters = """parameters: {query: {type: string}}"""))
            put("files", buildJsonObject { put("scripts/workflow.js", "print(parameters.query);") })
        })
        assertTrue(saved.isVerifiedSuccess)
        val snapshot = requireNotNull(fixture.engine.findExecutionSnapshot("demo", true))
        val invalidParameters = fixture.call("run_skill", buildJsonObject {
            put("name", "demo")
            put("draft", true)
            put("revision", snapshot.revision)
            put("path", "scripts/workflow.js")
            put("parameters", JsonArray(emptyList()))
            put("checkpoint_key", "invalid-parameters")
        })
        assertEquals(ToolExecutionStatus.FAILED, invalidParameters.executionStatus)
        assertTrue(invalidParameters.outputText.contains("SKILL_PARAMETERS_INVALID"))
    } }

    @Test
    fun inspectOnlyReadsTheDurableRecordWithoutCallingProgram() = runBlocking { withFixture { fixture ->
        val saved = fixture.call("save_skill", buildJsonObject {
            put("markdown", skillMarkdown(requiredCapabilities = "[missing_capability]"))
            put("files", buildJsonObject { put("scripts/workflow.js", "print('must not run');") })
        })
        assertTrue(saved.isVerifiedSuccess)
        val snapshot = requireNotNull(fixture.engine.findExecutionSnapshot("demo", true))
        val inspected = fixture.call("run_skill", buildJsonObject {
            put("name", "demo")
            put("draft", true)
            put("revision", snapshot.revision)
            put("path", "scripts/workflow.js")
            put("checkpoint_key", "inspect-only")
            put("inspect_only", true)
        })
        assertTrue(inspected.isVerifiedSuccess)
        assertEquals("null", inspected.outputText)
        assertTrue(fixture.engine.executionRecord("demo", "inspect-only") == null)
    } }

    @Test
    fun missingCapabilityRejectsScriptBeforeProgramOrExecutionEffects() = runBlocking { withFixture { fixture ->
        assertTrue(fixture.call("save_skill", buildJsonObject {
            put("markdown", skillMarkdown(requiredCapabilities = "[missing_capability]"))
            put("files", buildJsonObject { put("scripts/workflow.js", "print('must not run');") })
        }).isVerifiedSuccess)
        val snapshot = requireNotNull(fixture.engine.findExecutionSnapshot("demo", true))
        val result = fixture.call("run_skill", buildJsonObject {
            put("name", "demo")
            put("draft", true)
            put("revision", snapshot.revision)
            put("path", "scripts/workflow.js")
            put("checkpoint_key", "missing-capability")
            put("device_access", true)
        })
        assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
        assertTrue(result.outputText.contains("SKILL_CAPABILITY_UNAVAILABLE"))
        assertEquals(0, fixture.programCalls)
        assertTrue(fixture.engine.executionRecord("demo", "missing-capability") == null)
        assertFalse(fixture.events.eventsForTurn(fixture.owner.turnId).any {
            it.eventKind == AgentEventKind.PROGRAM_EXECUTION && it.payloadJson.contains("skill_started")
        })
    } }

    @Test
    fun verificationRejectsHistoricalAndRegistrarAuthoredEvidence() = runBlocking { withFixture { fixture ->
        val saved = fixture.call("save_skill", buildJsonObject { put("markdown", skillMarkdown()) })
        assertTrue(saved.isVerifiedSuccess)
        val snapshot = requireNotNull(fixture.engine.findExecutionSnapshot("demo", true))
        val run = fixture.call("run_skill", buildJsonObject {
            put("name", "demo")
            put("draft", true)
            put("revision", snapshot.revision)
            put("checkpoint_key", "guided-run")
        })
        assertTrue(run.isVerifiedSuccess)
        val runId = Json.parseToJsonElement(run.outputText).jsonObject["skill_run_id"]!!.jsonPrimitive.content
        val currentEvents = fixture.events.eventsForTurn(fixture.owner.turnId)
        val started = currentEvents.single { event ->
            event.eventKind == AgentEventKind.PROGRAM_EXECUTION && event.payloadJson.contains("skill_started")
        }

        val selfAuthored = fixture.call("verify_skill", buildJsonObject {
            put("run_id", runId)
            put("outcome", "unknown")
            put("summary", "Only the registrar start record was inspected.")
            put("evidence_refs", JsonArray(listOf(JsonPrimitive(started.eventId))))
        })
        assertEquals(ToolExecutionStatus.FAILED, selfAuthored.executionStatus)
        assertTrue(selfAuthored.outputText.contains("SKILL_ASSESSMENT_EVIDENCE_INVALID"))

        val historical = fixture.events.append(
            AgentEventDraft(
                eventKind = AgentEventKind.PROGRAM_EXECUTION,
                threadId = fixture.owner.threadId,
                turnId = "historical-turn",
                payloadJson = """{"phase":"device_finished","data":{"image_ref":"old-image"}}""",
                idempotencyKey = "historical-program-event"
            )
        )
        val oldEvidence = fixture.call("verify_skill", buildJsonObject {
            put("run_id", runId)
            put("outcome", "unknown")
            put("summary", "A previous turn was inspected.")
            put("evidence_refs", JsonArray(listOf(JsonPrimitive(historical.eventId))))
        })
        assertEquals(ToolExecutionStatus.FAILED, oldEvidence.executionStatus)
        assertTrue(oldEvidence.outputText.contains("SKILL_ASSESSMENT_EVIDENCE_INVALID"))
    } }

    @Test
    fun selectedExperienceRevisionIsFrozenIntoTheDraftAndExportedZip() = runBlocking {
        val reference = "experience:price_filter:7"
        val frozenSource = "# Frozen historical method\n\nExact revision 7; verify current behavior before use.\n"
        var currentSource = frozenSource
        withFixture(experienceSnapshot = { requested -> if (requested == reference) currentSource else null }) { fixture ->
            val saved = fixture.call("save_skill", buildJsonObject {
                put("markdown", skillMarkdown())
                put("source_experience_refs", JsonArray(listOf(JsonPrimitive(reference))))
            })
            assertTrue(saved.isVerifiedSuccess)
            val snapshot = requireNotNull(fixture.engine.findExecutionSnapshot("demo", true))
            assertEquals(frozenSource, snapshot.files.getValue("references/experience-0.md"))

            currentSource = "# Revision 8\n\nThe source memory changed after drafting.\n"
            val draftAfterSourceChange = requireNotNull(fixture.engine.findExecutionSnapshot("demo", true))
            assertEquals(frozenSource, draftAfterSourceChange.files.getValue("references/experience-0.md"))

            val zippedFiles = ZipInputStream(fixture.engine.exportPackage("demo", true).inputStream()).use { zip ->
                buildMap {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                    }
                }
            }
            assertEquals(frozenSource, zippedFiles.getValue("references/experience-0.md"))
        }
    }

    private suspend fun <T> withFixture(
        scripted: Boolean = false,
        experienceSnapshot: suspend (String) -> String? = { null },
        block: suspend (Fixture) -> T
    ): T {
        val fixture = Fixture(experienceSnapshot, scripted)
        fixture.open()
        return try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val experienceSnapshot: suspend (String) -> String?, private val scripted: Boolean) : AutoCloseable {
        val storage = TempStorage()
        val engine = SkillEngine(storage, com.droidance.skill.RhinoSkillScriptValidator())
        val owner = TurnSessionContext("skill-tool-turn", "skill-tool-thread")
        val events = InMemoryAgentEventStore()
        val blobRoot: Path = Files.createTempDirectory("skill-tool-blobs-")
        val blobs = FileBlobStore(blobRoot.toFile())
        val tools = linkedMapOf<String, Tool>()
        var programCalls = 0

        suspend fun open() {
            events.createThread(owner.threadId, "Skill tool test")
            val hash = "d".repeat(64)
            events.startTurn(
                TurnEventScope(owner.threadId, owner.turnId),
                ModelSessionIdentity(owner.threadId, "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS, hash, hash, "fixture", hash),
                "fixture"
            )
            SkillToolRegistrar(
                register = { tool -> tools[tool.name] = tool },
                engine = engine,
                rules = { it },
                availableCapabilities = { setOf("android_read") },
                session = { owner },
                events = events,
                blobs = blobs,
                runProgram = { _, _, _, _ ->
                    programCalls++
                    check(scripted) { "Unexpected Program dispatch" }
                    programEvent("""{"phase":"terminal","data":{"handle":"script","status":"exited","stdout_ref":"script-output"},
                        "artifacts":[{"content_ref":"script-output","mime_type":"text/plain"}]}""")
                    ToolProtocolResult.success("""{"handle":"script","status":"exited"}""")
                },
                experienceSnapshot = experienceSnapshot,
                readLearning = { ToolProtocolResult.success("learning") }
            ).registerAll()
        }

        suspend fun call(name: String, arguments: kotlinx.serialization.json.JsonObject): com.droidance.core.agent.event.ToolExecutionResult =
            tools.getValue(name).execute(arguments.toString())

        suspend fun programEvent(payload: String): com.droidance.core.agent.event.AgentEventEnvelope =
            events.append(AgentEventDraft(AgentEventKind.PROGRAM_EXECUTION, owner.threadId, owner.turnId,
                payloadJson = payload, idempotencyKey = java.util.UUID.randomUUID().toString()))

        override fun close() {
            owner.clear()
            blobRoot.toFile().deleteRecursively()
            storage.close()
        }
    }

    private class TempStorage : SkillDocumentStorage, AutoCloseable {
        private val root = Files.createTempDirectory("skill-tool-docs-")

        override fun readFile(relativePath: String): String? = resolve(relativePath).takeIf(Files::isRegularFile)?.let {
            String(Files.readAllBytes(it), Charsets.UTF_8)
        }

        override fun writeFile(relativePath: String, content: String) {
            val path = resolve(relativePath)
            Files.createDirectories(path.parent)
            Files.write(path, content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }

        override fun exists(relativePath: String): Boolean = Files.exists(resolve(relativePath))

        override fun readFrontMatter(relativePath: String): String? {
            val content = readFile(relativePath)?.replace("\r\n", "\n") ?: return null
            if (!content.startsWith("---\n")) return null
            val end = content.indexOf("\n---", 4)
            return if (end < 0) null else content.substring(4, end).trim()
        }

        override fun listFiles(directoryPath: String): List<String> {
            val directory = resolve(directoryPath)
            if (!Files.isDirectory(directory)) return emptyList()
            Files.list(directory).use { children -> return children.map { it.fileName.toString() }.sorted().toList() }
        }

        override fun replaceDirectory(relativePath: String, files: Map<String, String>) {
            deleteDirectory(relativePath)
            Files.createDirectories(resolve(relativePath))
            files.forEach { (path, content) -> writeFile("$relativePath/$path", content) }
        }

        override fun deleteDirectory(relativePath: String): Boolean {
            val directory = resolve(relativePath)
            if (!Files.exists(directory)) return false
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            return true
        }

        override fun close() {
            deleteDirectory("")
        }

        private fun resolve(relativePath: String): Path {
            val path = root.resolve(relativePath).normalize()
            require(path.startsWith(root))
            return path
        }
    }

    private fun skillMarkdown(
        parameters: String = "",
        requiredCapabilities: String = ""
    ): String = buildString {
        appendLine("---")
        appendLine("name: demo")
        appendLine("version: 1")
        appendLine("description: \"A reusable test skill\"")
        if (requiredCapabilities.isNotBlank()) appendLine("required_capabilities: $requiredCapabilities")
        if (parameters.isNotBlank()) appendLine(parameters)
        appendLine("---")
        appendLine("Read the current task evidence before acting.")
    }
}
