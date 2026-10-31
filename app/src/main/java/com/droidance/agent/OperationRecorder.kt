// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.tools.DeviceToolNames

import com.droidance.core.agent.event.ToolExecutionResult
import android.util.Log
import com.droidance.core.agent.event.LearningActionRecordedEventPayload
import com.droidance.core.agent.event.LearningActionSource
import com.droidance.core.agent.event.LearningSessionCompletedEventPayload
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.SKILL_LEARNING_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.SkillLearningActionProjection
import com.droidance.core.agent.event.SkillLearningSessionProjector
import com.droidance.memory.MarkdownFileManager
import com.droidance.core.agent.tools.UnifiedDeviceToolProtocol
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Skill learning session controller.
 *
 * EventStore is the sole durable fact source. The StateFlows are only a live UI projection of
 * successfully appended learning events. User demonstrations come from MotionEvents on the
 * retained virtual display; Agent executions may still be captured from ToolExecutor. Neither
 * path uses AccessibilityService or an App-specific recorder.
 */
@Singleton
class OperationRecorder @Inject constructor(
    private val eventStore: AgentEventStore,
    private val fileManager: MarkdownFileManager,
    private val blobs: com.droidance.core.agent.blob.BlobStore
) {
    companion object {
        private const val TAG = "OperationRecorder"
        private const val AGENT_CAPTURE_MODE = "agent_tool_eventstore"
        const val USER_DEMONSTRATION_CAPTURE_MODE = "user_virtual_space"
    }

    enum class RecordingState { IDLE, RECORDING }

    data class LearningSessionInfo(
        val skillName: String = "",
        val goal: String = "",
        val packageName: String = "",
        val appLabel: String = "",
        val captureMode: String = USER_DEMONSTRATION_CAPTURE_MODE
    )

    @Serializable
    data class RecordedOperation(
        val timestamp: Long = System.currentTimeMillis(),
        val actionType: String,
        val packageName: String = "",
        val activityName: String = "",
        val elementText: String = "",
        val elementDesc: String = "",
        val elementId: String = "",
        val elementBounds: String = "",
        val inputText: String = "",
        val scrollDirection: String = "",
        val beforeScreenshotPath: String = "",
        val screenshotPath: String = "",
        val toolName: String = "",
        val argumentsJson: String = "{}",
        val resultSummary: String = "",
        val sourceCallId: String = "",
        val evidenceRef: String = "",
        val success: Boolean = false
    )

    private val eventScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private val eventQueueLock = Any()
    private var eventTail: Deferred<Unit> = CompletableDeferred(Unit)
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state
    private val _operations = MutableStateFlow<List<RecordedOperation>>(emptyList())
    val operations: StateFlow<List<RecordedOperation>> = _operations
    private val _sessionInfo = MutableStateFlow(LearningSessionInfo())
    val sessionInfo: StateFlow<LearningSessionInfo> = _sessionInfo
    private val _completionNonce = MutableStateFlow(0L)
    val completionNonce: StateFlow<Long> = _completionNonce
    private val actionIndex = AtomicInteger(0)
    @Volatile private var lastTargetAction = 0

    @Volatile private var recordingStartTime = 0L
    @Volatile private var activeSessionId: String? = null
    @Volatile private var latestCompletedSessionId: String? = null
    @Volatile private var latestPackageName: String = ""
    @Volatile private var latestEventFailure: Throwable? = null
    @Volatile private var lastProjectedCompletedSessionId: String? = null

    suspend fun startRecording(
        info: LearningSessionInfo = LearningSessionInfo(captureMode = AGENT_CAPTURE_MODE)
    ) {
        check(activeSessionId == null) { "A learning session is already active" }
        val sessionId = UUID.randomUUID().toString()
        actionIndex.set(0)
        lastTargetAction = 0
        latestPackageName = ""
        latestEventFailure = null
        recordingStartTime = System.currentTimeMillis()
        activeSessionId = sessionId
        runCatching {
            eventStore.recordLearningSessionStarted(
                sessionId = sessionId,
                captureMode = info.captureMode,
                skillName = info.skillName.takeIf(String::isNotBlank),
                goal = info.goal.takeIf(String::isNotBlank),
                packageName = info.packageName.takeIf(String::isNotBlank),
                appLabel = info.appLabel.takeIf(String::isNotBlank)
            )
            refreshLiveProjection(sessionId)
        }.onFailure {
            failSession(sessionId, "start", it)
            throw it
        }
        Log.i(TAG, "Learning session started: $sessionId")
    }

    @Synchronized
    fun stopRecording() {
        val sessionId = activeSessionId ?: return
        val duration = (System.currentTimeMillis() - recordingStartTime).coerceAtLeast(0L)
        val count = actionIndex.get()
        activeSessionId = null
        latestCompletedSessionId = sessionId
        enqueueEvent {
            runCatching {
                requireNoEventFailure()
                val persistedActionCount = SkillLearningSessionProjector.project(
                    eventStore.eventsForLearningSession(sessionId)
                ).actions.size
                eventStore.recordLearningSessionCompleted(
                    LearningSessionCompletedEventPayload(
                        contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
                        sessionId = sessionId,
                        actionCount = persistedActionCount,
                        durationMillis = duration
                    )
                )
                refreshLiveProjection(sessionId)
            }.onFailure {
                latestEventFailure = it
                Log.e(TAG, "Learning completion event failed", it)
            }
        }
        Log.i(TAG, "Learning session stopped: $sessionId actions=$count")
    }

    @Synchronized
    fun recordUserGesture(
        gesture: UserDemonstrationGesture,
        beforeFrame: ByteArray?,
        afterFrame: ByteArray?,
        foregroundComponent: String?,
        success: Boolean
    ) {
        if (activeSessionId == null) return
        val (packageName, activityName) = splitComponent(foregroundComponent)
        lastTargetAction = actionIndex.get() + 1
        recordProjectedOperation(
            operation = RecordedOperation(
                actionType = gesture.actionType.wireName.uppercase(Locale.US),
                packageName = packageName.ifBlank { _sessionInfo.value.packageName },
                activityName = activityName,
                scrollDirection = UserDemonstrationGestureClassifier.direction(gesture),
                toolName = "user_demonstration",
                argumentsJson = gesture.argumentsJson(),
                resultSummary = if (success) {
                    "User demonstration action injected and post-action frame captured"
                } else {
                    "User demonstration injection failed"
                },
                success = success
            ),
            source = LearningActionSource.USER_DEMONSTRATION,
            beforeFrame = beforeFrame,
            afterFrame = afterFrame,
            displayWidth = gesture.displayWidth,
            displayHeight = gesture.displayHeight,
            durationMillis = gesture.durationMillis
        )
    }

    @Synchronized
    fun recordUserKey(
        keyCode: Int,
        actionType: UserDemonstrationActionType,
        beforeFrame: ByteArray?,
        afterFrame: ByteArray?,
        foregroundComponent: String?,
        success: Boolean
    ) {
        if (activeSessionId == null) return
        val (packageName, activityName) = splitComponent(foregroundComponent)
        val argumentFields = linkedMapOf<String, JsonElement>(
            "op" to JsonPrimitive(actionType.wireName)
        )
        if (actionType == UserDemonstrationActionType.TEXT_INPUT) {
            argumentFields["parameter_ref"] = JsonPrimitive("input_${actionIndex.get() + 1}")
            argumentFields["target_action_index"] = JsonPrimitive(lastTargetAction)
            argumentFields["operation"] = JsonPrimitive("insert_text")
            argumentFields["value_redacted"] = JsonPrimitive(true)
        } else {
            argumentFields["key_code"] = JsonPrimitive(keyCode)
            argumentFields["target_action_index"] = JsonPrimitive(lastTargetAction)
        }
        val arguments = JsonObject(argumentFields).toString()
        recordProjectedOperation(
            operation = RecordedOperation(
                actionType = actionType.wireName.uppercase(Locale.US),
                packageName = packageName.ifBlank { _sessionInfo.value.packageName },
                activityName = activityName,
                inputText = if (actionType == UserDemonstrationActionType.TEXT_INPUT) "{{input_${actionIndex.get() + 1}}}" else "",
                toolName = "user_demonstration",
                argumentsJson = arguments,
                resultSummary = if (success) "User demonstration key injected" else "User demonstration key injection failed",
                success = success
            ),
            source = LearningActionSource.USER_DEMONSTRATION,
            beforeFrame = beforeFrame,
            afterFrame = afterFrame
        )
    }

    /** Called centrally by ToolExecutor after a frozen device primitive settles. */
    fun recordToolExecution(
        sourceCallId: String,
        toolName: String,
        argumentsJson: String,
        result: ToolExecutionResult
    ) {
        if (activeSessionId == null) return
        if (toolName !in DeviceToolNames.deviceToolNames) return
        val safeArguments = sanitizeArguments(toolName, argumentsJson)
        val input = runCatching { Json.parseToJsonElement(safeArguments).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val operation = UnifiedDeviceToolProtocol.operation(toolName, safeArguments)
            ?: if (toolName == DeviceToolNames.SHELL) "run" else toolName
        val packageName = sequenceOf("package", "package_name", "target_package")
            .mapNotNull { input.string(it) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .ifBlank { latestPackageName }
        if (packageName.isNotBlank()) latestPackageName = packageName
        val evidenceRef = result.observationId?.let { "observation:$it" }.orEmpty()
        recordProjectedOperation(
            RecordedOperation(
                actionType = operation.uppercase(Locale.US),
                packageName = packageName,
                elementText = input.string("text").orEmpty(),
                elementDesc = input.string("content_description").orEmpty(),
                elementId = input.string("resource_id").orEmpty(),
                inputText = if (operation == "type_text") "{{input_text}}" else "",
                scrollDirection = input.string("direction").orEmpty(),
                toolName = toolName,
                argumentsJson = safeArguments,
                resultSummary = redact(result.outputText).replace(Regex("\\s+"), " ").take(500),
                sourceCallId = sourceCallId,
                evidenceRef = evidenceRef,
                success = ToolResultStatus.isSuccess(result)
            ),
            LearningActionSource.AGENT_TOOL
        )
    }

    suspend fun awaitPendingWrites() {
        val tail = synchronized(eventQueueLock) { eventTail }
        tail.await()
        requireNoEventFailure()
    }

    fun completedSessionId(): String? = latestCompletedSessionId

    /** Paginate the durable session; any recorded frame remains addressable after UI disposal. */
    suspend fun readLearningSource(arguments: String): ToolExecutionResult {
        val args = Json.parseToJsonElement(arguments).jsonObject
        val id = args.getValue("session_id").jsonPrimitive.content
        require(runCatching { UUID.fromString(id) }.isSuccess) { "LEARNING_SESSION_INVALID" }
        val history = eventStore.eventsForThread("skill-learning:$id")
        val projection = SkillLearningSessionProjector.project(history)
        require(projection.completed != null) { "LEARNING_SESSION_NOT_FINISHED" }
        val mode = args["mode"]?.jsonPrimitive?.content ?: "list"
        require(mode in setOf("list", "step", "image"))
        if (mode == "step") {
            val action = projection.actions.single { it.payload.actionIndex == args.getValue("action_index").jsonPrimitive.int }
            val content = Json.encodeToString(action.payload)
            val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
            require(offset in 0..content.length)
            val end = minOf(content.length, offset + 12000)
            return ToolProtocolResult.success(buildJsonObject {
                put("text", content.substring(offset, end)); put("next_offset", end); put("has_more", end < content.length)
            }.toString())
        }
        if (mode == "image") {
            val index = args.getValue("action_index").jsonPrimitive.int
            val action = projection.actions.single { it.payload.actionIndex == index }.payload
            val role = args["role"]?.jsonPrimitive?.content ?: "after"
            require(role in setOf("before", "after"))
            val path = requireNotNull(if (role == "before") action.beforeFrameRef else action.afterFrameRef)
            val bytes = requireNotNull(fileManager.readBytes(path)) { "LEARNING_FRAME_UNAVAILABLE" }
            val blob = blobs.put(bytes)
            return ToolProtocolResult.success("Historical demonstration image. Not current screen state.").copy(
                historicalAttachment = DetailAttachmentAddedEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION, "learning-$id-$index-$role", DetailAttachmentRole.INPUT_OBSERVATION,
                    blob.contentRef, blob.contentRef.removePrefix("sha256:"), bytes.size.toLong(), "image/jpeg"))
        }
        val after = args["after"]?.jsonPrimitive?.intOrNull ?: 0
        require(after >= 0)
        val remaining = projection.actions.filter { it.payload.actionIndex > after }
        val page = remaining.take(8)
        return ToolProtocolResult.success(buildJsonObject {
            put("session", Json.encodeToJsonElement(projection.started))
            put("total_actions", projection.actions.size)
            put("actions", JsonArray(page.map { action -> buildJsonObject {
                val value = action.payload
                put("action_index", value.actionIndex); put("occurred_at", action.occurredAt)
                put("action_type", value.actionType); put("tool", value.toolName)
                put("arguments_preview", value.argumentsJson.take(1200)); put("arguments_truncated", value.argumentsJson.length > 1200)
                put("injection_succeeded", value.success); put("package", value.packageName)
                put("before_frame", value.beforeFrameRef != null); put("after_frame", value.afterFrameRef != null)
                put("result", value.resultSummary.take(400))
            } }))
            put("next_after", page.lastOrNull()?.payload?.actionIndex ?: after)
            put("has_more", remaining.size > page.size)
            put("final_action_index", projection.actions.lastOrNull()?.payload?.actionIndex ?: 0)
        }.toString())
    }

    fun clear() {
        activeSessionId = null
        latestCompletedSessionId = null
        recordingStartTime = 0L
        lastProjectedCompletedSessionId = null
        _operations.value = emptyList()
        _state.value = RecordingState.IDLE
        _sessionInfo.value = LearningSessionInfo()
    }

    @Synchronized
    private fun recordProjectedOperation(
        operation: RecordedOperation,
        source: LearningActionSource,
        beforeFrame: ByteArray? = null,
        afterFrame: ByteArray? = null,
        displayWidth: Int? = null,
        displayHeight: Int? = null,
        durationMillis: Long? = null
    ) {
        val sessionId = activeSessionId ?: return
        val index = actionIndex.incrementAndGet()
        enqueueEvent {
            val beforeRef = persistFrame(sessionId, index, "before", beforeFrame)
            val afterRef = persistFrame(sessionId, index, "after", afterFrame)
            val recorded = operation.copy(
                beforeScreenshotPath = beforeRef.orEmpty(),
                screenshotPath = afterRef.orEmpty(),
                evidenceRef = operation.evidenceRef.ifBlank { afterRef.orEmpty() }
            )
            runCatching {
                eventStore.recordLearningAction(
                    LearningActionRecordedEventPayload(
                        contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
                        sessionId = sessionId,
                        actionIndex = index,
                        source = source,
                        sourceCallId = recorded.sourceCallId.takeIf(String::isNotBlank),
                        toolName = recorded.toolName.ifBlank { recorded.actionType.lowercase(Locale.US) },
                        argumentsJson = recorded.argumentsJson.ifBlank { "{}" },
                        resultSummary = recorded.resultSummary.ifBlank { "Recorded demonstration action" },
                        success = recorded.success,
                        evidenceRef = recorded.evidenceRef.takeIf(String::isNotBlank),
                        packageName = recorded.packageName.takeIf(String::isNotBlank),
                        activityName = recorded.activityName.takeIf(String::isNotBlank),
                        actionType = recorded.actionType.takeIf(String::isNotBlank),
                        beforeFrameRef = beforeRef,
                        afterFrameRef = afterRef,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight,
                        durationMillis = durationMillis
                    )
                )
                refreshLiveProjection(sessionId)
            }.onFailure {
                latestEventFailure = it
                Log.e(TAG, "Learning action event failed", it)
            }
        }
    }

    private fun persistFrame(
        sessionId: String,
        index: Int,
        phase: String,
        bytes: ByteArray?
    ): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val path = "learning/$sessionId/frames/${index.toString().padStart(3, '0')}-$phase-${hash.take(16)}.jpg"
        fileManager.writeBytes(path, bytes)
        return path
    }

    private fun splitComponent(component: String?): Pair<String, String> {
        if (component.isNullOrBlank()) return "" to ""
        val packageName = component.substringBefore('/')
        val activityName = component.substringAfter('/', missingDelimiterValue = "")
        return packageName to activityName
    }

    private fun enqueueEvent(block: suspend () -> Unit): Deferred<Unit> =
        synchronized(eventQueueLock) {
            val previous = eventTail
            eventScope.async {
                previous.await()
                block()
            }.also { eventTail = it }
        }

    private fun failSession(sessionId: String, phase: String, error: Throwable) {
        latestEventFailure = error
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }
        Log.e(TAG, "Learning session $phase failed", error)
    }

    private suspend fun refreshLiveProjection(sessionId: String) {
        val projection = SkillLearningSessionProjector.project(
            eventStore.eventsForLearningSession(sessionId)
        )
        _sessionInfo.value = LearningSessionInfo(
            skillName = projection.started.skillName.orEmpty(),
            goal = projection.started.goal.orEmpty(),
            packageName = projection.started.packageName.orEmpty(),
            appLabel = projection.started.appLabel.orEmpty(),
            captureMode = projection.started.captureMode
        )
        _operations.value = projection.actions.map(::toRecordedOperation)
        _state.value = if (projection.completed == null) {
            RecordingState.RECORDING
        } else {
            RecordingState.IDLE
        }
        if (
            projection.completed != null &&
            lastProjectedCompletedSessionId != projection.started.sessionId
        ) {
            lastProjectedCompletedSessionId = projection.started.sessionId
            _completionNonce.value = _completionNonce.value + 1L
        }
    }

    private fun toRecordedOperation(
        action: SkillLearningActionProjection
    ): RecordedOperation {
        val payload = action.payload
        val arguments = runCatching {
            Json.parseToJsonElement(payload.argumentsJson).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        return RecordedOperation(
            timestamp = action.occurredAt,
            actionType = payload.actionType.orEmpty(),
            packageName = payload.packageName.orEmpty(),
            activityName = payload.activityName.orEmpty(),
            elementText = arguments.string("text").orEmpty(),
            elementDesc = arguments.string("content_description").orEmpty(),
            elementId = arguments.string("resource_id").orEmpty(),
            elementBounds = arguments.string("bounds").orEmpty(),
            inputText = arguments.string("text").orEmpty().ifBlank {
                arguments.string("parameter_ref")?.let { "{{$it}}" }.orEmpty()
            },
            scrollDirection = arguments.string("direction").orEmpty(),
            beforeScreenshotPath = payload.beforeFrameRef.orEmpty(),
            screenshotPath = payload.afterFrameRef.orEmpty(),
            toolName = payload.toolName,
            argumentsJson = payload.argumentsJson,
            resultSummary = payload.resultSummary,
            sourceCallId = payload.sourceCallId.orEmpty(),
            evidenceRef = payload.evidenceRef.orEmpty(),
            success = payload.success
        )
    }

    private fun requireNoEventFailure() {
        latestEventFailure?.let { error ->
            throw IllegalStateException("Learning EventStore append failed", error)
        }
    }

    private fun sanitizeArguments(toolName: String, argumentsJson: String): String {
        val parsed = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull()
            ?: return "{}"
        val operation = UnifiedDeviceToolProtocol.operation(toolName, argumentsJson)
        return sanitizeElement(parsed, redactText = operation == "type_text").toString()
    }

    private fun sanitizeElement(element: JsonElement, redactText: Boolean): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            when {
                key.lowercase(Locale.US) in sensitiveKeys -> JsonPrimitive("[REDACTED]")
                redactText && key == "text" -> JsonPrimitive("{{input_text}}")
                else -> sanitizeElement(value, redactText)
            }
        })
        is kotlinx.serialization.json.JsonArray ->
            kotlinx.serialization.json.JsonArray(element.map { sanitizeElement(it, redactText) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redactPlainText(element.content)) else element
        else -> element
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun redact(value: String): String = runCatching {
        sanitizeElement(Json.parseToJsonElement(value), redactText = false).toString()
    }.getOrElse { redactPlainText(value) }

    private fun redactPlainText(value: String): String = value
        .replace(Regex("(?i)\\bbearer\\s+[^\\s,;]+"), "Bearer [REDACTED]")
        .replace(
            Regex("(?i)(password|passwd|otp|token|secret|cookie|authorization)(\\s*[:=]\\s*)([^\\s,;]+)"),
            "$1$2[REDACTED]"
        )

    private val sensitiveKeys = setOf(
        "password", "passwd", "otp", "access_token", "refresh_token", "token",
        "secret", "cookie", "authorization"
    )
}
