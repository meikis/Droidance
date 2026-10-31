// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.program

import com.droidance.core.device.program.*

import com.droidance.program.*

import android.content.Context
import android.os.SystemClock
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.agent.TurnSessionContext
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.device.ExecutionContext
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*
import com.droidance.tools.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
private data class ProgramPolicy(val maxCodeChars: Int, val maxRuns: Int,
    val maxOutputChars: Int, val pageChars: Int, val timeoutMs: Long) {
    fun validate() {
        require(maxCodeChars in 1..65536 && maxRuns in 1..32)
        require(maxOutputChars in 1..2097152 && pageChars in 1..16000)
        require(timeoutMs in 1000..300000)
    }
}

internal fun programFailureState(error: Throwable, deadlineExpired: Boolean, invocationActive: Boolean): String = when {
    deadlineExpired || error is TimeoutCancellationException && invocationActive -> "timed_out"
    error is CancellationException -> "canceled"
    else -> "failed"
}

/** Task-local worker; device requests pause until the Agent explicitly binds device access. */
class ProgramTool(
    private val context: Context,
    private val rules: (String) -> String,
    private val workspace: TaskWorkspace,
    private val workspacePolicy: () -> WorkspacePolicy,
    private val readArtifact: suspend (String) -> ToolExecutionResult,
    private val session: () -> TurnSessionContext,
    private val execution: () -> ExecutionContext?,
    private val observation: () -> ObservationEnvelope?,
    private val modelObservationSequence: () -> Long?,
    private val deviceCall: suspend (String, JsonObject, String) -> ToolExecutionResult,
    private val events: AgentEventStore,
    private val blobs: BlobStore,
    private val skillEngine: com.droidance.core.knowledge.skill.SkillEngine? = null
) : Tool {
    override val name = "program"
    override val tier = ToolTier.CORE
    override val category = ToolCategory.SYSTEM
    override val definition get() = ToolDefinition(name, rules("description"), linkedMapOf(
        "device_access" to ToolParameter("boolean", "Grant backend access to this run; default false. Use run or poll to bind an unbound run.", false),
        "op" to ToolParameter("string", "Environment/process operation", true,
            listOf("environment", "run", "poll", "cancel")),
        "path" to ToolParameter("string", "Relative workspace path", false),
        "code" to ToolParameter("string", "JavaScript source; mutually exclusive with path in run", false),
        "handle" to ToolParameter("string", "Exact task-owned run handle", false),
        "offset" to ToolParameter("integer", "UTF-16 output/file cursor, default zero", false),
        "resume" to ToolParameter("object", "Explicit decision for a returned visual boundary. Required after inspecting a fresh observation.", false, properties = mapOf(
            "boundary_id" to ToolParameter("string", "Exact returned boundary id"),
            "pending_action_hash" to ToolParameter("string", "Exact pending request hash"),
            "observation_ref" to ToolParameter("string", "Fresh currently bound observation actually inspected"),
            "decision" to ToolParameter("string", "Continue this exact pending request, or discard remaining script and replan", true, listOf("continue", "replan"))
        )),
        "stdin" to ToolParameter("string", "Input lines for run/poll", false)
    ))
    private class Run(val handle: String, val connection: ProgramConnection, var binding: ExecutionContext?,
        val source: String, val deadline: Long, val policy: ProgramPolicy,
        val skill: com.droidance.core.knowledge.skill.SkillExecutionSnapshot? = null, val skillToken: String? = null) {
        val output = StringBuilder()
        val input = java.util.ArrayDeque<String>()
        var pending: ProgramConnection.Request? = null
        var outcome = "running"
        var failure = ""
        var sequence = 0
        var workspaceSequence = 0
        var needsDevice = false
        var needsObservation = false
        var observationRevision = 0L
        var lastObservationChild: String? = null
        var decisionSequence: Long? = null
        var resumedBoundaries = 0L
        var replannedBoundaries = 0L
        var unknownEffects = 0L
        var hostRequests = 0L
        var hostServiceMs = 0L
        val receipts = mutableListOf<JsonObject>()
        var activeChild: ChildAttempt? = null
        var failedChild: JsonObject? = null
        var terminalValue: JsonObject? = null
        var terminalDraft: AgentEventDraft? = null
        var terminalEvent: AgentEventEnvelope? = null
        var skillSettled = false
    }
    private class ChildAttempt(val id: String, val tool: String, val argumentsRef: String) {
        var started: AgentEventEnvelope? = null
        var dispatched = false
        var result: ToolExecutionResult? = null
        var finishedDraft: AgentEventDraft? = null
        var finished: AgentEventEnvelope? = null
        var receipt: JsonObject? = null
    }
    private class State : AutoCloseable {
        val runs = linkedMapOf<String, Run>()
        override fun close() { runs.values.forEach { it.connection.close() } }
    }
    private fun policy() = Json.decodeFromString<ProgramPolicy>(rules("policy")).also { it.validate() }
    suspend fun executeSkill(arguments: String, snapshot: com.droidance.core.knowledge.skill.SkillExecutionSnapshot,
                             parameters: JsonObject, checkpointKey: String): ToolExecutionResult =
        executeInternal(arguments, snapshot, parameters, checkpointKey)

    override suspend fun execute(arguments: String): ToolExecutionResult = executeInternal(arguments)

    /** Called before the task's tool binding is released. Closing a run never clears item checkpoints. */
    suspend fun release(owner: TurnSessionContext) {
        val state = owner.getTyped<State>() ?: return
        var failure: Throwable? = null
        withContext(NonCancellable) {
            state.runs.values.forEach { run ->
                try {
                    finalizeRun(owner, run, if (run.outcome != "running") run.outcome
                        else if (SystemClock.elapsedRealtime() >= run.deadline) "timed_out" else "interrupted",
                        "task_resources_released", run.policy)
                } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error else previous.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    private suspend fun executeInternal(arguments: String,
        skill: com.droidance.core.knowledge.skill.SkillExecutionSnapshot? = null,
        parameters: JsonObject = JsonObject(emptyMap()), checkpointKey: String = ""): ToolExecutionResult {
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return ToolProtocolResult.invalidArguments()
        val owner = session()
        val p = policy()
        val state = owner.getOrPutTyped { State() }
        val initialObservationRevision = state.runs.values.sumOf { it.observationRevision }
        val scope = TurnEventScope(owner.threadId, owner.turnId)
        var activeRun: Run? = null
        fun required(key: String) = input[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("MISSING_$key")
        fun offset() = (input["offset"]?.jsonPrimitive?.intOrNull ?: 0).also { require(it >= 0) }
        return try {
            val result = when (required("op")) {
                "environment" -> buildJsonObject {
                    put("available", android.os.Build.VERSION.SDK_INT >= 29); put("minimum_api", 29)
                    put("description", rules("environment")); put("limits", Json.parseToJsonElement(rules("policy")))
                    put("device_bound", execution() != null)
                    execution()?.let { put("display_id", it.displayId); put("backend", it.mode.name) }
                    put("workspace_scope", "durable task files; fresh processes; explicit history reads only")
                }
                "run" -> {
                    require(state.runs.size < p.maxRuns) { "RUN_LIMIT" }
                    require(state.runs.values.none { it.outcome == "running" }) { "RUN_ALREADY_ACTIVE" }
                    require(("code" in input) xor ("path" in input)) { "SUPPLY_CODE_OR_PATH" }
                    val binding = if (input["device_access"]?.jsonPrimitive?.booleanOrNull == true)
                        execution() ?: error("DEVICE_BINDING_REQUIRED") else null
                    var code = input["code"]?.jsonPrimitive?.contentOrNull ?: workspace.read(scope, required("path"), workspacePolicy())
                    require(code.length <= p.maxCodeChars) { "CODE_LIMIT" }
                    if (skill != null) code = "var parameters = JSON.parse(" + JsonPrimitive(parameters.toString()) + ");\n" + code
                    require(code.length <= p.maxCodeChars) { "CODE_LIMIT" }
                    val source = blobs.put(code.toByteArray()).contentRef
                    val token = skill?.let { requireNotNull(skillEngine).beginExecution(it, parameters, checkpointKey,
                        input["skill_resume"]?.jsonPrimitive?.booleanOrNull == true, input["skill_entry"]?.jsonPrimitive?.contentOrNull,
                        sourceThreadId = owner.threadId, sourceTurnId = owner.turnId) }
                    val run = Run(UUID.randomUUID().toString(), ProgramConnection(context), binding, source,
                        SystemClock.elapsedRealtime() + p.timeoutMs, p, skill, token)
                    state.runs[run.handle] = run
                    activeRun = run
                    addInput(run, input, p)
                    record(owner, "started", buildJsonObject { put("handle", run.handle); put("source_ref", source) })
                    run.decisionSequence = modelObservationSequence()
                    run.connection.start(code, p.timeoutMs)
                    pump(owner, run, p)
                    snapshot(owner, run, offset(), p)
                }
                "poll", "cancel" -> {
                    val run = state.runs[required("handle")] ?: throw IllegalArgumentException("UNKNOWN_HANDLE")
                    activeRun = run
                    if (required("op") == "cancel") {
                        finalizeRun(owner, run, if (SystemClock.elapsedRealtime() >= run.deadline) "timed_out" else "canceled",
                            "explicit_cancel", p)
                    } else if (run.outcome == "running") {
                        if (run.needsObservation && SystemClock.elapsedRealtime() < run.deadline &&
                            run.connection.status in setOf("running", "starting")) {
                            val ack = input["resume"] as? JsonObject
                                ?: return ToolProtocolResult.notExecuted("PROGRAM_RESUME_DECISION_REQUIRED", retryable = true)
                            val frame = observation()
                            val decisionSequence = modelObservationSequence()
                            val boundary = boundary(run)
                            if (ack["boundary_id"] != boundary["boundary_id"] ||
                                ack["pending_action_hash"] != boundary["pending_action_hash"]) {
                                return ToolProtocolResult.notExecuted("PROGRAM_RESUME_BOUNDARY_MISMATCH", retryable = true)
                            }
                            val decision = (ack["decision"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                            if (decision !in setOf("continue", "replan")) return ToolProtocolResult.invalidArgument("resume.decision", "unsupported_value")
                            if (frame == null || frame.imageBase64 == null || decisionSequence == null ||
                                decisionSequence == run.decisionSequence || frame.sequence != decisionSequence ||
                                (ack["observation_ref"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull != frame.observationId ||
                                run.binding?.let { it.executionId != frame.executionId || it.backendSessionId != frame.backendSessionId || it.displayId != frame.displayId } == true) {
                                return ToolProtocolResult.notExecuted("NEW_MODEL_OBSERVATION_REQUIRED", retryable = true)
                            }
                            record(owner, "resume_decision", buildJsonObject {
                                put("handle", run.handle); put("boundary", boundary); put("decision", decision)
                                put("observation_ref", frame.observationId)
                            })
                            if (decision == "replan") {
                                run.replannedBoundaries++
                                finalizeRun(owner, run, "replanned", "agent_replanned", p)
                            } else run.resumedBoundaries++
                            run.needsObservation = false
                            run.decisionSequence = decisionSequence
                        }
                        if (input["device_access"]?.jsonPrimitive?.booleanOrNull == true && run.binding == null) {
                            run.binding = execution() ?: error("DEVICE_BINDING_REQUIRED")
                            run.needsDevice = false
                        }
                        if (run.outcome == "running") { addInput(run, input, p); pump(owner, run, p) }
                    }
                    snapshot(owner, run, offset(), p)
                }
                else -> throw IllegalArgumentException("UNKNOWN_OPERATION")
            }
            val changed = state.runs.values.sumOf { it.observationRevision } > initialObservationRevision
            val observed = observation()
            val captured = changed && observed?.imageBase64 != null &&
                state.runs.values.any { it.lastObservationChild != null && it.lastObservationChild == observed.actionId }
            val failed = result["status"]?.jsonPrimitive?.content in setOf("failed", "timed_out", "device_stopped", "interrupted")
            val handle = result["handle"]?.jsonPrimitive?.contentOrNull
            val progRef = handle?.let { "prog_${it.replace("-", "").take(8)}" }
            val stdoutRef = result["stdout_ref"]?.jsonPrimitive?.contentOrNull
            val srcRef = result["source_ref"]?.jsonPrimitive?.contentOrNull
            val previewText = (result["stdout"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: result.toString().take(500)
            ToolProtocolResult.success(result.toString()).copy(
                executionStatus = if (failed) ToolExecutionStatus.FAILED else ToolExecutionStatus.SUCCEEDED,
                diagnosticCode = if (failed) "PROGRAM_RUN_FAILED" else null,
                observationId = progRef,
                sourceRef = progRef,
                data = result,
                dataRef = stdoutRef ?: srcRef,
                preview = previewText,
                metrics = mapOf(
                    "program_observation_changed" to if (changed) 1L else 0L,
                    "program_observation_available" to if (captured) 1L else 0L
                ))
        } catch (cancel: CancellationException) {
            val invocationActive = currentCoroutineContext().isActive
            val budgetExpired = currentCoroutineContext()[ToolExecutionBudget]?.remainingMs() == 0L
            withContext(NonCancellable) {
                state.runs.values.filter { it.outcome == "running" || it === activeRun }.forEach { run ->
                    try {
                        finalizeRun(owner, run, programFailureState(cancel,
                            budgetExpired || SystemClock.elapsedRealtime() >= run.deadline, invocationActive),
                            cancel.message ?: "invocation_canceled", p)
                    } catch (cleanup: Throwable) { cancel.addSuppressed(cleanup) }
                }
                try {
                    record(owner, "revoked", buildJsonObject {
                        put("reason", "invocation_canceled")
                        put("handles", JsonArray(state.runs.keys.map(::JsonPrimitive)))
                    })
                } catch (cleanup: Throwable) { cancel.addSuppressed(cleanup) }
            }
            throw cancel
        } catch (error: Exception) {
            val run = activeRun
            if (run == null) ToolProtocolResult.failed("PROGRAM_ERROR", false, error) else {
                try {
                    val value = finalizeRun(owner, run, "failed", error.message.orEmpty(), p)
                    ToolProtocolResult.failed("PROGRAM_ERROR", false, error).copy(
                        outputText = value.toString(), data = value,
                        dataRef = value["stdout_ref"]?.jsonPrimitive?.contentOrNull,
                        sourceRef = "prog_${run.handle.replace("-", "").take(8)}")
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                    ToolProtocolResult.failed("PROGRAM_ERROR", false, error)
                }
            }
        } catch (error: Throwable) {
            activeRun?.let { run ->
                try { finalizeRun(owner, run, "interrupted", error.message.orEmpty(), p) }
                catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }
    private fun addInput(run: Run, args: JsonObject, p: ProgramPolicy) {
        args["stdin"]?.jsonPrimitive?.contentOrNull?.let { text ->
            require(text.length + run.input.sumOf { it.length } <= p.maxCodeChars) { "STDIN_LIMIT" }
            text.split('\n').forEach(run.input::addLast)
        }
    }
    private suspend fun pump(owner: TurnSessionContext, run: Run, p: ProgramPolicy) {
        // One interruptible task invocation services deterministic work until a real
        // decision boundary. Output pagination never pauses host request processing.
        while (run.outcome == "running") {
            currentCoroutineContext().ensureActive()
            check(session() === owner && (run.binding == null || execution() == run.binding)) { "STALE_PROGRAM_BINDING" }
            if (SystemClock.elapsedRealtime() >= run.deadline || currentCoroutineContext()[ToolExecutionBudget]?.remainingMs() == 0L) {
                run.outcome = "timed_out"; run.connection.close(); break
            }
            if (run.connection.status !in setOf("running", "starting")) {
                run.outcome = run.connection.status; run.failure = run.connection.error; run.connection.close(); break
            }
            val request = run.pending ?: withContext(Dispatchers.IO) { run.connection.requests.poll(20, TimeUnit.MILLISECONDS) }
                ?: continue
            run.pending = request
            if (request.operation in setOf("android", "stdin") && run.needsObservation) break
            if (request.operation == "stdin" && run.input.isEmpty()) break
            if (request.operation == "android") {
                val call = Json.parseToJsonElement(request.arguments).jsonObject
                val needsBackend = UnifiedDeviceToolProtocol.requirements(
                    call.getValue("tool").jsonPrimitive.content, call.getValue("args").toString()).requiresBackend
                if (needsBackend && (run.binding == null || currentCoroutineContext()[ToolExecutionBudget]?.deviceAccessAllowed == false)) {
                    run.needsDevice = true; break
                }
                if (needsBackend) run.needsDevice = false
            }
            val serviceStarted = SystemClock.elapsedRealtime()
            run.hostRequests++
            try {
                val args = Json.parseToJsonElement(request.arguments).jsonObject
                val value: JsonElement = when (request.operation) {
                    "skill_checkpoint" -> requireNotNull(skillEngine).checkpoint(
                        requireNotNull(run.skillToken) { "PROGRAM_HAS_NO_SKILL" },
                        args.getValue("key").jsonPrimitive.content, args.getValue("operation").jsonPrimitive.content,
                        programHandle = run.handle)
                    "skill_file" -> {
                        val snapshot = requireNotNull(run.skill) { "PROGRAM_HAS_NO_SKILL" }
                        val path = args.getValue("path").jsonPrimitive.content
                        JsonPrimitive(snapshot.files[path] ?: error("SKILL_FILE_NOT_FOUND"))
                    }
                    "read" -> JsonPrimitive(workspace.read(TurnEventScope(owner.threadId, owner.turnId), args.getValue("path").jsonPrimitive.content, workspacePolicy()))
                    "write" -> {
                        val path = args.getValue("path").jsonPrimitive.content
                        val text = args.getValue("text").jsonPrimitive.content
                        workspace.write(TurnEventScope(owner.threadId, owner.turnId), path, text,
                            append = false, expectedRef = null, writeId = "${run.handle}:${++run.workspaceSequence}",
                            policy = workspacePolicy())
                        JsonNull
                    }
                    "artifact" -> {
                        val result = readArtifact(buildJsonObject {
                            put("mode", "artifact"); put("ref", args.getValue("ref"))
                            put("offset", args["offset"] ?: JsonPrimitive(0))
                        }.toString())
                        check(result.isVerifiedSuccess) { result.diagnosticCode ?: "ARTIFACT_UNAVAILABLE" }
                        check(result.historicalAttachment == null) { "IMAGE_REQUIRES_MODEL_INSPECTION" }
                        Json.parseToJsonElement(result.outputText)
                    }
                    "list" -> JsonArray(workspace.list(TurnEventScope(owner.threadId, owner.turnId)).map { JsonPrimitive(it.path) })
                    "stdout" -> {
                        val text = args.getValue("text").jsonPrimitive.content + "\n"
                        require(run.output.length + text.length <= p.maxOutputChars) { "OUTPUT_LIMIT" }
                        run.output.append(text); JsonNull
                    }
                    "stdin" -> JsonPrimitive(run.input.removeFirst())
                    "android" -> {
                        val tool = args.getValue("tool").jsonPrimitive.content
                        require(tool in setOf("android_read", "android_act", "screen_act", "shell")) { "CAPABILITY_NOT_EXPOSED" }
                        val toolArgs = args.getValue("args").jsonObject
                        val child = "${run.handle}:${++run.sequence}"
                        val attempt = ChildAttempt(child, tool, blobs.put(toolArgs.toString().toByteArray()).contentRef)
                        run.activeChild = attempt
                        attempt.started = record(owner, "device_started", buildJsonObject {
                            put("handle", run.handle); put("child_id", child); put("tool", tool)
                            put("arguments_ref", attempt.argumentsRef)
                            if (tool == "shell") put("arguments", toolArgs)
                        })
                        run.skillToken?.let { token -> skillEngine?.recordCheckpointCall(token, child, tool,
                            attempt.argumentsRef, requireNotNull(attempt.started).eventId, "started") }
                        val result = try {
                            val remaining = minOf(run.deadline - SystemClock.elapsedRealtime(),
                                currentCoroutineContext()[ToolExecutionBudget]?.remainingMs() ?: Long.MAX_VALUE).coerceAtLeast(1)
                            withTimeout(remaining) {
                                withContext(coroutineContext[ToolExecutionBudget]?.constrain(remaining)
                                    ?: kotlin.coroutines.EmptyCoroutineContext) {
                                    ensureActive()
                                    attempt.dispatched = true
                                    deviceCall(tool, toolArgs, child)
                                }
                            }
                        } catch (error: Throwable) {
                            run.outcome = if (error is CancellationException) programFailureState(error,
                                SystemClock.elapsedRealtime() >= run.deadline ||
                                    currentCoroutineContext()[ToolExecutionBudget]?.remainingMs() == 0L,
                                currentCoroutineContext().isActive) else "device_stopped"
                            run.failure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                            throw error
                        }
                        attempt.result = result
                        if (UnifiedDeviceToolProtocol.isScreenAction(tool) ||
                            (tool == "shell" && (UnifiedDeviceToolProtocol.expectsScreenChange(tool, toolArgs.toString()) ||
                                result.executionStatus in setOf(ToolExecutionStatus.INTERRUPTED, ToolExecutionStatus.CANCELED))) ||
                            (tool == "android_act" && UnifiedDeviceToolProtocol.expectsScreenChange(tool, toolArgs.toString())) ||
                            UnifiedDeviceToolProtocol.isScreenObservation(tool, toolArgs.toString())) {
                            run.input.clear() // Old prequeued targets cannot cross a visual decision boundary.
                            run.needsObservation = true
                            run.observationRevision++
                            run.lastObservationChild = child
                        }
                        retainActiveChild(owner, run, "device_call_returned")
                        if (!allowsOrderedContinuation(result)) {
                            run.outcome = "device_stopped"; run.failure = result.outputText.take(p.pageChars)
                            run.connection.close()
                        }
                        Json.encodeToJsonElement(result)
                    }
                    else -> throw IllegalArgumentException("UNKNOWN_HOST_OPERATION")
                }
                request.answer.complete(buildJsonObject { put("value", value) }.toString())
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                request.answer.complete(buildJsonObject { put("error", error.message.orEmpty()) }.toString())
                if (request.operation in setOf("android", "stdout")) {
                    if (run.outcome == "running") run.outcome = if (request.operation == "android") "device_stopped" else "failed"
                    run.failure = error.message.orEmpty()
                    run.connection.close()
                }
            } finally {
                run.hostServiceMs += SystemClock.elapsedRealtime() - serviceStarted
                run.pending = null
            }
        }
    }
    private suspend fun retainActiveChild(owner: TurnSessionContext, run: Run, reason: String) {
        val attempt = run.activeChild ?: return
        val result = attempt.result
        val unknown = result?.let { it.actionEvidence?.delivery == ActionDeliveryEvidence.UNKNOWN ||
            it.executionStatus in setOf(ToolExecutionStatus.INTERRUPTED, ToolExecutionStatus.CANCELED) }
            ?: attempt.dispatched
        val phase = when {
            result != null -> "device_finished"
            unknown -> "device_unknown"
            else -> "device_not_executed"
        }
        if (attempt.finishedDraft == null) {
            val observed = observation()?.takeIf { it.actionId == attempt.id }
            val data = buildJsonObject {
                put("handle", run.handle); put("child_id", attempt.id); put("tool", attempt.tool)
                put("arguments_ref", attempt.argumentsRef)
                put("started_event_ref", attempt.started?.eventId)
                put("dispatched", attempt.dispatched)
                if (result == null) put("reason", reason) else {
                    put("result", Json.encodeToJsonElement(result))
                    if (attempt.tool == "shell") result.dataRef?.let { put("content_ref", it) }
                    observed?.imageBase64?.let { image ->
                        val bytes = android.util.Base64.decode(image, android.util.Base64.DEFAULT)
                        put("image_ref", blobs.put(bytes).contentRef)
                        put("image_mime", if (bytes.take(4) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte())) "image/png" else "image/jpeg")
                        put("observation_id", observed.observationId)
                    }
                }
            }
            attempt.finishedDraft = recordDraft(owner, phase, data, "program:${attempt.id}:settled", run.binding)
        }
        val settled = attempt.finished ?: events.append(requireNotNull(attempt.finishedDraft)).also { attempt.finished = it }
        if (attempt.receipt == null) {
            val receipt = buildJsonObject {
                put("event_ref", settled.eventId); put("sequence", settled.sequence)
                put("child_id", attempt.id); put("tool", attempt.tool); put("arguments_ref", attempt.argumentsRef)
                put("started_event_ref", attempt.started?.eventId)
                put("execution_status", result?.executionStatus?.name ?: if (unknown) "INTERRUPTED" else "NOT_EXECUTED")
                put("effect_status", if (unknown) "unknown" else if (result == null) "not_executed" else "reported")
                result?.let {
                    put("observation_id", it.observationId); it.sourceRef?.let { ref -> put("source_ref", ref) }
                    put("output_preview", it.outputText.take(1200))
                }
            }
            attempt.receipt = receipt
            run.receipts += receipt
            if (unknown) run.unknownEffects++
            if (result == null || !allowsOrderedContinuation(result)) run.failedChild = receipt
        }
        run.skillToken?.let { token -> skillEngine?.recordCheckpointCall(token, attempt.id, attempt.tool,
            attempt.argumentsRef, settled.eventId,
            if (unknown) "unknown" else if (result?.isVerifiedSuccess == true) "succeeded" else "failed") }
        run.activeChild = null
    }

    private suspend fun finalizeRun(owner: TurnSessionContext, run: Run, status: String,
                                    reason: String, p: ProgramPolicy): JsonObject = withContext(NonCancellable) {
        if (run.outcome == "running") {
            run.outcome = if (status == "timed_out") status else run.connection.status
                .takeIf { it in setOf("exited", "failed", "timed_out", "interrupted") } ?: status
        }
        check(run.outcome in setOf("exited", "failed", "canceled", "interrupted", "timed_out", "device_stopped", "replanned"))
        if (run.failure.isBlank() && run.outcome != "exited") run.failure = reason
        run.connection.close()
        var failure: Throwable? = null
        try { retainActiveChild(owner, run, reason) } catch (error: Throwable) { failure = error }
        try {
            // A recoverable child receipt must precede this worker's immutable terminal event.
            // Keep a failed retention retryable; never freeze an incomplete receipt index.
            if (failure == null) {
                if (run.terminalValue == null) run.terminalValue = snapshotValue(run, 0, p)
                if (run.terminalDraft == null) run.terminalDraft = recordDraft(owner, "terminal",
                    requireNotNull(run.terminalValue), "program:${run.handle}:terminal", run.binding)
                if (run.terminalEvent == null) run.terminalEvent = events.append(requireNotNull(run.terminalDraft))
            }
        } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else previous.addSuppressed(error)
        } finally {
            if (!run.skillSettled) {
                try {
                    run.skillToken?.let { token -> skillEngine?.settleExecution(token, run.outcome, run.handle) }
                    run.skillSettled = true
                } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error else previous.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
        JsonObject(requireNotNull(run.terminalValue) + ("terminal_event_ref" to JsonPrimitive(requireNotNull(run.terminalEvent).eventId)))
    }

    private fun boundary(run: Run): JsonObject {
        val pending = requireNotNull(run.pending)
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest((pending.operation + "\u0000" + pending.arguments).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("boundary_id", "${run.handle}:${run.observationRevision}")
            put("pending_action_hash", hash); put("pending_operation", pending.operation)
            put("pending_arguments", Json.parseToJsonElement(com.droidance.core.knowledge.memory.MemorySecretRedactor.redactJson(pending.arguments)))
            put("pending_executed", false); put("completed_child_calls", run.receipts.count { it["execution_status"]?.jsonPrimitive?.content == "SUCCEEDED" })
            put("observation_ref", observation()?.observationId)
        }
    }
    private suspend fun snapshot(owner: TurnSessionContext, run: Run, offset: Int, p: ProgramPolicy): JsonObject {
        if (run.outcome != "running") {
            val value = finalizeRun(owner, run, run.outcome, run.failure, p)
            return JsonObject(value + ("stdout" to page(run.output.toString(), offset, p.pageChars)))
        }
        val value = snapshotValue(run, offset, p)
        run.skillToken?.let { token -> skillEngine?.settleExecution(token, value.getValue("status").jsonPrimitive.content, run.handle) }
        record(owner, "snapshot", value)
        return value
    }
    private fun snapshotValue(run: Run, offset: Int, p: ProgramPolicy): JsonObject {
        val output = run.output.toString()
        val outputRef = blobs.put(output.toByteArray()).contentRef
        val receiptsRef = blobs.put(JsonArray(run.receipts).toString().toByteArray()).contentRef
        val value = buildJsonObject {
            put("worker", runCatching { Json.parseToJsonElement(run.connection.identity) }.getOrDefault(JsonNull))
            put("handle", run.handle); put("status", when {
                run.outcome == "running" && run.needsDevice -> "waiting_for_device"
                run.outcome == "running" && run.needsObservation -> "waiting_for_observation"
                run.outcome == "running" && run.pending?.operation == "stdin" -> "waiting_for_input"
                else -> run.outcome
            })
            if (run.outcome == "running" && run.needsObservation && run.pending != null) put("boundary", boundary(run))
            put("resumed_boundaries", run.resumedBoundaries); put("replanned_boundaries", run.replannedBoundaries)
            put("unknown_effects", run.unknownEffects + if (run.activeChild?.let {
                it.dispatched && it.result == null && it.receipt == null } == true) 1L else 0L)
            put("completed_child_calls", run.receipts.count { it["execution_status"]?.jsonPrimitive?.content == "SUCCEEDED" })
            put("remaining_script", if (run.outcome == "exited") "none" else "not_executed; dynamic requests are not enumerable before execution")
            put("host_requests", run.hostRequests); put("host_service_ms", run.hostServiceMs)
            put("error", run.failure); put("source_ref", run.source); put("stdout_ref", outputRef)
            put("receipts_ref", receiptsRef); run.failedChild?.let { put("failed_child", it) }
            run.activeChild?.let { child -> put("unsettled_child", buildJsonObject {
                put("child_id", child.id); put("tool", child.tool); put("arguments_ref", child.argumentsRef)
                put("started_event_ref", child.started?.eventId); put("dispatched", child.dispatched)
                put("effect_status", if (child.result != null) "reported" else if (child.dispatched) "unknown" else "not_executed")
                child.result?.let { put("result", Json.encodeToJsonElement(it)) }
            }) }
            put("stdout", page(output, offset, p.pageChars)); put("attempted_child_calls", run.sequence); put("recent_receipts", JsonArray(run.receipts.takeLast(8)))
        }
        return value
    }
    private suspend fun record(owner: TurnSessionContext, phase: String, data: JsonObject): AgentEventEnvelope =
        events.append(recordDraft(owner, phase, data, "program:${UUID.randomUUID()}", execution()))

    private suspend fun recordDraft(owner: TurnSessionContext, phase: String, data: JsonObject,
                                    idempotencyKey: String, binding: ExecutionContext?): AgentEventDraft =
        AgentEventDraft(AgentEventKind.PROGRAM_EXECUTION, owner.threadId, owner.turnId,
            payloadJson = buildJsonObject {
                put("phase", phase); put("parent_call_id", currentCoroutineContext()[ToolInvocation]?.callId.orEmpty())
                binding?.let {
                    put("backend_session_id", it.backendSessionId); put("display_id", it.displayId)
                    put("execution_id", it.executionId)
                }
                put("data", data)
                val body = blobs.put(data.toString().toByteArray())
                put("data_ref", body.contentRef)
                val artifactKeys = setOf("source_ref", "stdout_ref", "receipts_ref", "content_ref", "arguments_ref", "image_ref")
                val artifacts = data.filterKeys { it in artifactKeys }.map { (key, value) ->
                    val ref = value.jsonPrimitive.content
                    buildJsonObject {
                        put("content_ref", ref); put("size_bytes", blobs.read(ref, 8L * 1024 * 1024).size)
                        put("mime_type", if (key == "image_ref") data["image_mime"]?.jsonPrimitive?.content ?: "image/jpeg" else "text/plain")
                        if (key == "image_ref") put("observation_id", data["observation_id"] ?: JsonNull)
                    }
                } + buildJsonObject { put("content_ref", body.contentRef); put("size_bytes", body.sizeBytes); put("mime_type", "application/json") }
                put("artifacts", JsonArray(artifacts))
            }.toString(), idempotencyKey = idempotencyKey)
    private fun page(text: String, offset: Int, limit: Int): JsonObject {
        require(offset <= text.length) { "INVALID_CURSOR" }
        val end = minOf(text.length, offset + limit)
        return buildJsonObject { put("text", text.substring(offset, end)); put("next_offset", end); put("total_chars", text.length); put("has_more", end < text.length) }
    }
}
