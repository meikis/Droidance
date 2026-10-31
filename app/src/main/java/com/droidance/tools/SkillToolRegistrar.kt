// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.TurnSessionContext
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.knowledge.skill.*
import com.droidance.core.knowledge.memory.ExperienceEvidenceSources
import com.droidance.skill.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID

/** All authoring and assessment decisions stay in the ordinary Agent loop. */
class SkillToolRegistrar(
    private val register: (Tool) -> Unit,
    private val engine: SkillEngine,
    private val rules: (String) -> String,
    private val availableCapabilities: () -> Set<String>,
    private val session: () -> TurnSessionContext,
    private val events: AgentEventStore,
    private val blobs: BlobStore,
    private val runProgram: suspend (String, SkillExecutionSnapshot, JsonObject, String) -> ToolExecutionResult,
    private val experienceSnapshot: suspend (String) -> String?,
    private val readLearning: suspend (String) -> ToolExecutionResult
) {
    private fun turnState() = session().getOrPutTyped { SkillTurnSession(engine) }

    fun registerAll() {
        tool("save_skill", rules("save"), mapOf(
            "markdown" to ToolParameter("string", "Complete SKILL.md with YAML metadata and English reusable instructions.", true),
            "files" to ToolParameter("object", "Optional scripts/*.js and references/*.md text files; full replacement, not a patch.", false),
            "source_experience_refs" to ToolParameter("array", "Exact enabled experience:id:revision refs to freeze into this draft package.", false, items = ToolParameter("string", "Experience reference")),
            "expected_revision" to ToolParameter("string", "Exact pending draft revision, or active revision when no draft exists. Required for updates.", false)
        )) { args ->
            require(args["files"] == null || args["files"] is JsonObject) { "SKILL_FILES_INVALID" }
            val files = (args["files"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                require(value is JsonPrimitive && value.isString); value.content
            }
            val references = args["source_experience_refs"]?.takeUnless { it == JsonNull }?.let { value ->
                require(value is JsonArray && value.size <= 8) { "EXPERIENCE_REFERENCE_LIMIT" }
                value.map { it.jsonPrimitive.content }.distinct()
            }.orEmpty()
            val frozen = references.mapIndexed { index, ref ->
                "references/experience-$index.md" to requireNotNull(experienceSnapshot(ref)) { "EXPERIENCE_REFERENCE_UNAVAILABLE" }
            }.toMap()
            require(frozen.keys.none { it in files }) { "EXPERIENCE_FILE_CONFLICT" }
            require("SKILL.md" !in files)
            val saved = engine.saveDraft(args.required("markdown"), files + frozen, args.string("expected_revision"))
            val snapshot = requireNotNull(engine.findExecutionSnapshot(saved.id, true))
            ToolProtocolResult.success(buildJsonObject {
                put("skill", saved.name); put("version", saved.version); put("revision", snapshot.revision)
                put("status", "draft_saved"); put("active_version_unchanged", true)
            }.toString())
        }
        tool("read_skill_file", rules("read"), mapOf(
            "name" to ToolParameter("string", "Skill name, or skill-creator for the built-in authoring instructions.", true),
            "path" to ToolParameter("string", "Package path; default SKILL.md.", false),
            "draft" to ToolParameter("boolean", "Read pending draft instead of active version.", false),
            "mode" to ToolParameter("string", "Read a package file or paged local assessment records.", false, listOf("file", "assessments")),
            "revision" to ToolParameter("string", "Expected package revision.", false),
            "offset" to ToolParameter("integer", "UTF-16 character offset; default zero.", false)
        )) { args ->
            val name = args.required("name")
            val path = args.string("path") ?: "SKILL.md"
            val draft = args["draft"]?.jsonPrimitive?.booleanOrNull == true
            val mode = args.string("mode") ?: "file"
            require(mode in setOf("file", "assessments"))
            val snapshot = if (name == "skill-creator") null else requireNotNull(engine.findExecutionSnapshot(name, draft))
            val content = if (name == "skill-creator") {
                require(path == "SKILL.md" && mode == "file"); rules("creator")
            } else {
                val frozen = requireNotNull(snapshot)
                require(args.string("revision") == null || args.string("revision") == frozen.revision) { "SKILL_REVISION_CHANGED" }
                if (mode == "assessments") JsonArray(engine.assessments(frozen).map { Json.parseToJsonElement(it) }).toString()
                else frozen.files[path] ?: error("SKILL_FILE_NOT_FOUND")
            }
            val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
            require(offset in 0..content.length)
            var end = minOf(content.length, offset + 12000)
            if (end < content.length && end > 0 && content[end - 1].isHighSurrogate()) end--
            ToolProtocolResult.success(buildJsonObject {
                put("text", content.substring(offset, end)); put("next_offset", end); put("has_more", end < content.length)
                snapshot?.let {
                    put("revision", it.revision); put("version", it.skill.version)
                    if (offset == 0 && mode == "file" && path == "SKILL.md") {
                        put("files", JsonArray(it.files.keys.sorted().map(::JsonPrimitive)))
                        put("assessment_summary", JsonArray(engine.assessments(it).take(3).map { raw ->
                            val assessment = Json.parseToJsonElement(raw).jsonObject
                            buildJsonObject {
                                listOf("run_id", "outcome", "evidence_kind", "assessed_at", "thread_id", "turn_id").forEach { key ->
                                    assessment[key]?.let { value -> put(key, value) }
                                }
                                put("summary", assessment["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400))
                            }
                        }))
                    }
                }
            }.toString()).copy(metrics = mapOf(
                "skill_file_reads" to if (mode == "file") 1L else 0L,
                "skill_assessment_reads" to if (mode == "assessments") 1L else 0L,
                "skill_read_chars" to (end - offset).toLong()
            ))
        }
        tool("skill_source", rules("source"), mapOf(
            "session_id" to ToolParameter("string", "Exact demonstration session identifier supplied by the user interface.", true),
            "mode" to ToolParameter("string", "Paged facts, one full action, or a recorded image.", false, listOf("list", "step", "image")),
            "after" to ToolParameter("integer", "Exclusive action index, default zero.", false),
            "action_index" to ToolParameter("integer", "Action whose facts or before/after frame is requested.", false),
            "offset" to ToolParameter("integer", "UTF-16 cursor for step facts.", false),
            "role" to ToolParameter("string", "Frame role.", false, listOf("before", "after"))
        )) { readLearning(it.toString()) }
        tool("run_skill", rules("run"), mapOf(
            "name" to ToolParameter("string", "Skill name.", true),
            "revision" to ToolParameter("string", "Exact revision read before execution.", true),
            "path" to ToolParameter("string", "Optional JavaScript package path under scripts/. Omit for Markdown-guided execution.", false),
            "parameters" to ToolParameter("object", "Typed input matching the Skill metadata.", false),
            "draft" to ToolParameter("boolean", "Explicitly try a pending draft.", false),
            "inspect_only" to ToolParameter("boolean", "Inspect the durable operation and item checkpoints without running it.", false),
            "reconcile" to ToolParameter("object", "Record this Agent's evidence-backed assessment of one pending item after its worker ended; performs no device action.", false, properties = mapOf(
                "item_key" to ToolParameter("string", "Exact pending item identity.", true),
                "decision" to ToolParameter("string", "Whether current external evidence establishes completion, non-execution, or remains unknown.", true,
                    listOf("completed", "not_executed", "unknown")),
                "summary" to ToolParameter("string", "What was checked against the original call and current result, including remaining limits.", true),
                "evidence_refs" to ToolParameter("array", "Original current-task external observations after the original worker ended. Required for a resolved decision.", true,
                    items = ToolParameter("string", "Current external evidence reference."))
            )),
            "resume" to ToolParameter("boolean", "Explicitly resume a terminal script run using identical revision, path, parameters and per-item checkpoints. Pending item effects remain blocked.", false),
            "checkpoint_key" to ToolParameter("string", "Stable operation identity. Keep it when recovering uncertain effects.", true),
            "device_access" to ToolParameter("boolean", "Request the current bound device backend, like program.", false)
        )) { args ->
            val reconciliation = args["reconcile"]?.takeUnless { it == JsonNull }
            val inspectOnly = args["inspect_only"]?.jsonPrimitive?.booleanOrNull == true
            if (inspectOnly || reconciliation != null) {
                val operation = engine.executionRecord(args.required("name"), args.required("checkpoint_key"))
                require(operation == null || operation["revision"]?.jsonPrimitive?.content == args.required("revision")) {
                    "SKILL_CHECKPOINT_IDENTITY_CHANGED"
                }
                if (inspectOnly && reconciliation == null) return@tool ToolProtocolResult.success((operation ?: JsonNull).toString())
            }
            reconciliation?.let { value ->
                require(value is JsonObject && args["inspect_only"]?.jsonPrimitive?.booleanOrNull != true &&
                    args["resume"]?.jsonPrimitive?.booleanOrNull != true && args.string("path") == null) { "SKILL_RECONCILIATION_ARGUMENTS_INVALID" }
                return@tool reconcileCheckpoint(args.required("name"), args.required("revision"), args.required("checkpoint_key"), value)
            }
            val snapshot = requireNotNull(engine.findExecutionSnapshot(args.required("name"), args["draft"]?.jsonPrimitive?.booleanOrNull == true))
            require(snapshot.revision == args.required("revision")) { "SKILL_REVISION_CHANGED" }
            require(missingRequiredCapabilities(snapshot.skill, availableCapabilities()).isEmpty()) { "SKILL_CAPABILITY_UNAVAILABLE" }
            val path = args.string("path")
            require(path != null || args["resume"]?.jsonPrimitive?.booleanOrNull != true) { "SKILL_RESUME_REQUIRES_SCRIPT" }
            if (path != null) require(path.startsWith("scripts/") && path.endsWith(".js"))
            require(path == null || path in snapshot.files) { "SKILL_FILE_NOT_FOUND" }
            require(args["parameters"] == null || args["parameters"] is JsonObject) { "SKILL_PARAMETERS_INVALID" }
            val parameters = args["parameters"] as? JsonObject ?: JsonObject(emptyMap())
            engine.validateParameters(snapshot.skill, parameters)
            val run = UUID.randomUUID().toString()
            val binding = buildJsonObject {
                put("run_id", run); put("skill", snapshot.skill.id); put("revision", snapshot.revision)
                put("draft", snapshot.skill.isDraft); put("parameters", parameters)
                put("checkpoint_key", args.required("checkpoint_key")); put("path", path)
                put("selection", if (turnState().isExplicit(snapshot)) "explicit" else "agent")
                put("resume", args["resume"]?.jsonPrimitive?.booleanOrNull == true)
            }
            val guidedToken = if (path == null) engine.beginExecution(snapshot, parameters, args.required("checkpoint_key"),
                sourceThreadId = session().threadId, sourceTurnId = session().turnId) else null
            turnState().trackRun(run, snapshot, guidedToken)
            record("skill_started", binding)
            if (path == null) return@tool ToolProtocolResult.success(buildJsonObject {
                put("skill_run_id", run); put("revision", snapshot.revision)
                put("instructions_ref", buildJsonObject {
                    put("name", snapshot.skill.name); put("revision", snapshot.revision)
                    put("draft", snapshot.skill.isDraft); put("path", "SKILL.md")
                })
                put("parameters", parameters)
                put("status", "agent_guided"); put("goal_verified", false)
            }.toString())
            val result = runProgram(buildJsonObject {
                put("op", "run"); put("code", snapshot.files.getValue(path))
                put("skill_entry", path); put("skill_resume", args["resume"] ?: JsonPrimitive(false))
                put("device_access", args["device_access"] ?: JsonPrimitive(false))
            }.toString(), snapshot, parameters, args.required("checkpoint_key"))
            val programResult = runCatching { Json.parseToJsonElement(result.outputText).jsonObject }.getOrNull()
            record("skill_program", buildJsonObject {
                put("run_id", run); put("handle", programResult?.get("handle") ?: JsonNull)
                put("execution_status", result.executionStatus.name)
            })
            result.copy(outputText = buildJsonObject {
                put("skill_run_id", run); put("revision", snapshot.revision)
                put("program", runCatching { Json.parseToJsonElement(result.outputText) }.getOrElse { JsonPrimitive(result.outputText) })
                put("goal_verified", false)
            }.toString())
        }
        tool("verify_skill", rules("verify"), mapOf(
            "run_id" to ToolParameter("string", "Exact skill_run_id from run_skill in this task.", true),
            "outcome" to ToolParameter("string", "Agent assessment after reading original result evidence.", true, listOf("succeeded", "failed", "unknown")),
            "summary" to ToolParameter("string", "What was actually checked, the environment and limits of this assessment.", true),
            "evidence_kind" to ToolParameter("string", "external for real-world results; computation only for a local calculation produced by this Skill's script.", false, listOf("external", "computation")),
            "evidence_refs" to ToolParameter("array", "Current task observation, event or artifact references actually inspected.", true)
        )) { args ->
            val owner = session()
            val history = events.eventsForTurn(owner.turnId)
            val programEvents = ProgramEvidence.events(history, owner.threadId, owner.turnId)
            val startEvent = programEvents.single {
                val payload = Json.parseToJsonElement(it.payloadJson).jsonObject
                payload["phase"]?.jsonPrimitive?.content == "skill_started" &&
                    payload["data"]?.jsonObject?.get("run_id")?.jsonPrimitive?.content == args.required("run_id")
            }
            val started = Json.parseToJsonElement(startEvent.payloadJson).jsonObject.getValue("data").jsonObject
            val later = history.filter { it.sequence > startEvent.sequence }
            val refs = (args["evidence_refs"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val outcome = args.required("outcome")
            require(outcome in setOf("succeeded", "failed", "unknown"))
            val evidenceKind = args.string("evidence_kind") ?: "external"
            require(evidenceKind in setOf("external", "computation"))
            require(refs.size <= 32 && (outcome == "unknown" || refs.isNotEmpty())) { "SKILL_ASSESSMENT_EVIDENCE_REQUIRED" }
            require(args.required("summary").length <= 2000) { "SKILL_ASSESSMENT_SUMMARY_LIMIT" }
            val link = programEvents.map { Json.parseToJsonElement(it.payloadJson).jsonObject }
                    .lastOrNull { it["phase"]?.jsonPrimitive?.content == "skill_program" && it["data"]?.jsonObject?.get("run_id")?.jsonPrimitive?.content == args.required("run_id") }
                    ?.get("data")?.jsonObject
            val handle = link?.get("handle")?.jsonPrimitive?.contentOrNull
            val external = ExperienceEvidenceSources(history, owner.threadId, owner.turnId, Long.MAX_VALUE, startEvent.sequence)
            require(refs.all { ref ->
                if (evidenceKind == "external") external.resolve(ref) != null
                else handle != null && ProgramEvidence.events(later, owner.threadId, owner.turnId).any { event ->
                    val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
                    val data = payload["data"]?.jsonObject
                    payload["phase"]?.jsonPrimitive?.content == "terminal" &&
                        data?.get("handle")?.jsonPrimitive?.contentOrNull == handle &&
                        data["status"]?.jsonPrimitive?.content == "exited" &&
                        data["stdout_ref"]?.jsonPrimitive?.contentOrNull == ref &&
                        ProgramEvidence.artifacts(event).any { it["content_ref"]?.jsonPrimitive?.contentOrNull == ref }
                }
            }) { "SKILL_ASSESSMENT_EVIDENCE_INVALID" }
            if (outcome == "succeeded" && started["path"] != null && started["path"] != JsonNull) {
                require(handle != null) { "SKILL_PROGRAM_NOT_SETTLED" }
                val state = programEvents.map { Json.parseToJsonElement(it.payloadJson).jsonObject }
                    .lastOrNull { it["phase"]?.jsonPrimitive?.content == "terminal" && it["data"]?.jsonObject?.get("handle")?.jsonPrimitive?.content == handle }
                    ?.get("data")?.jsonObject?.get("status")?.jsonPrimitive?.content
                require(state == "exited") { "SKILL_PROGRAM_NOT_COMPLETED" }
            }
            val snapshot = requireNotNull(turnState().snapshot(args.required("run_id")))
            require(snapshot.revision == started.getValue("revision").jsonPrimitive.content) { "SKILL_CHANGED_DURING_VERIFICATION" }
            val assessment = buildJsonObject {
                put("run_id", args.required("run_id")); put("revision", snapshot.revision)
                put("thread_id", owner.threadId); put("turn_id", owner.turnId)
                put("parameters", started.getValue("parameters")); put("outcome", outcome)
                put("summary", args.required("summary")); put("evidence_refs", JsonArray(refs.map(::JsonPrimitive)))
                put("evidence_kind", evidenceKind)
                put("assessed_at", System.currentTimeMillis()); put("assessed_by", "task_agent")
            }
            engine.recordAssessment(snapshot, assessment)
            turnState().assessGuided(args.required("run_id"), outcome)
            record("skill_assessed", assessment)
            ToolProtocolResult.success("Skill assessment recorded for this exact revision and task. It does not complete the user's task or publish a draft.")
        }
    }

    private suspend fun reconcileCheckpoint(name: String, revision: String, checkpointKey: String,
                                            proposal: JsonObject): ToolExecutionResult {
        val owner = session()
        val operation = requireNotNull(engine.executionRecord(name, checkpointKey)) { "SKILL_RUN_NOT_FOUND" }
        require(operation["revision"]?.jsonPrimitive?.content == revision) { "SKILL_CHECKPOINT_IDENTITY_CHANGED" }
        val skillId = operation.required("skill")
        val key = proposal.required("item_key")
        val item = requireNotNull(operation["items"]?.jsonObject?.get(key)?.jsonObject) { "SKILL_CHECKPOINT_NOT_FOUND" }
        val sourceThread = item.required("source_thread_id")
        val sourceTurn = item.required("source_turn_id")
        val handle = item.required("program_handle")
        val original = ProgramEvidence.events(events.eventsForTurn(sourceTurn), sourceThread, sourceTurn)
        val terminal = original.singleOrNull { event ->
            val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
            payload["phase"]?.jsonPrimitive?.content == "terminal" &&
                payload["data"]?.jsonObject?.get("handle")?.jsonPrimitive?.content == handle
        } ?: error("SKILL_RECONCILIATION_ORIGINAL_UNAVAILABLE")
        val calls = (item["calls"] as? JsonObject).orEmpty().values.map { it.jsonObject }
        val originalRefs = calls.flatMap { listOf(it.required("start_event_ref"), it.required("event_ref")) }.distinct()
        require(calls.all { call -> listOf("start_event_ref", "event_ref").all { field ->
            original.any { event ->
                val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
                val data = payload["data"] as? JsonObject
                event.eventId == call.required(field) && event.sequence < terminal.sequence &&
                    payload["phase"]?.jsonPrimitive?.content in (if (field == "start_event_ref") setOf("device_started")
                        else setOf("device_started", "device_finished", "device_unknown", "device_not_executed")) &&
                    data?.get("handle")?.jsonPrimitive?.content == handle &&
                    data["child_id"]?.jsonPrimitive?.content == call.required("child_id") &&
                    data["tool"]?.jsonPrimitive?.content == call.required("tool") &&
                    data["arguments_ref"]?.jsonPrimitive?.content == call.required("arguments_ref")
            }
        } }) { "SKILL_RECONCILIATION_ORIGINAL_UNAVAILABLE" }
        val decision = proposal.required("decision")
        require(decision in setOf("completed", "not_executed", "unknown")) { "SKILL_RECONCILIATION_INVALID" }
        val summary = proposal.required("summary")
        require(summary.length <= 2000) { "SKILL_RECONCILIATION_SUMMARY_LIMIT" }
        val references = requireNotNull(proposal["evidence_refs"] as? JsonArray) { "SKILL_RECONCILIATION_EVIDENCE_REQUIRED" }
        require(references.size <= 32 && (decision == "unknown" || references.isNotEmpty())) { "SKILL_RECONCILIATION_EVIDENCE_REQUIRED" }
        val refs = references.map { require(it is JsonPrimitive && it.isString); it.content }
        val history = events.eventsForTurn(owner.turnId)
        val evidence = ExperienceEvidenceSources(history, owner.threadId, owner.turnId, Long.MAX_VALUE,
            if (sourceThread == owner.threadId && sourceTurn == owner.turnId) terminal.sequence else 0)
        val sources = refs.map { ref -> requireNotNull(evidence.resolve(ref)) { "SKILL_RECONCILIATION_EVIDENCE_INVALID" }.first }
        require(sources.all { id -> history.any { it.eventId == id && it.occurredAt >= terminal.occurredAt } }) {
            "SKILL_RECONCILIATION_EVIDENCE_INVALID"
        }
        val resolution = buildJsonObject {
            put("thread_id", owner.threadId); put("turn_id", owner.turnId); put("summary", summary)
            put("assessed_at", System.currentTimeMillis())
            put("original_event_refs", JsonArray((originalRefs + terminal.eventId).map(::JsonPrimitive)))
            put("evidence_refs", JsonArray(refs.map(::JsonPrimitive)))
            put("source_event_refs", JsonArray(sources.distinct().map(::JsonPrimitive)))
        }
        val updated = engine.reconcileCheckpoint(skillId, checkpointKey, revision, key, decision, resolution)
        record("skill_checkpoint_reconciled", buildJsonObject {
            put("skill", skillId); put("revision", revision); put("checkpoint_key", checkpointKey)
            put("item_key", key); put("decision", decision); put("resolution", resolution)
        })
        return ToolProtocolResult.success(buildJsonObject {
            put("status", "checkpoint_reconciled"); put("item_key", key); put("item", updated)
            put("goal_verified", false); put("device_action_executed", false)
        }.toString())
    }

    private fun tool(name: String, description: String, parameters: Map<String, ToolParameter>, body: suspend (JsonObject) -> ToolExecutionResult) {
        register(object : Tool {
            override val tier = ToolTier.CORE
            override val name = name
            override val definition = ToolDefinition(name, description, parameters)
            override suspend fun execute(arguments: String): ToolExecutionResult = try {
                body(Json.parseToJsonElement(arguments).jsonObject)
            } catch (cancel: CancellationException) { throw cancel }
              catch (error: Exception) { ToolProtocolResult.failed("SKILL_OPERATION_FAILED", false, error) }
        })
    }
    private suspend fun record(phase: String, data: JsonObject) {
        val owner = session(); val blob = blobs.put(data.toString().toByteArray())
        events.append(AgentEventDraft(AgentEventKind.PROGRAM_EXECUTION, owner.threadId, owner.turnId,
            payloadJson = buildJsonObject {
                put("phase", phase); put("data", data); put("data_ref", blob.contentRef)
                put("artifacts", JsonArray(listOf(buildJsonObject {
                    put("content_ref", blob.contentRef); put("size_bytes", blob.sizeBytes); put("mime_type", "application/json")
                })))
            }.toString(), idempotencyKey = "skill:${UUID.randomUUID()}"))
    }
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.required(key: String) = string(key)?.takeIf { it.isNotBlank() } ?: error("MISSING_$key")
}
