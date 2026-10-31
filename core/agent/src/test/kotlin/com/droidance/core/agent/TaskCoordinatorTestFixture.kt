// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.attempt.DeviceCapability
import com.droidance.core.agent.attempt.ExecutionAttemptBackendProbe
import com.droidance.core.agent.attempt.ExecutionAttemptBindingPreparer
import com.droidance.core.agent.attempt.ExecutionAttemptRoute
import com.droidance.core.agent.attempt.ExecutionRoundPlanner
import com.droidance.core.agent.attempt.ExecutionBackendObservation
import com.droidance.core.agent.attempt.ExecutionBackendKind
import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.context.DurableContextCheckpointCoordinator
import com.droidance.core.agent.device.DeviceExecutionStrategy
import com.droidance.core.agent.device.DeviceModeSelection
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.ExecutionSpaceSelection
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.event.*
import com.droidance.core.agent.harness.DurableModelRoundCoordinator
import com.droidance.core.agent.harness.ToolContractCatalog
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.ports.AgentConfiguration
import com.droidance.core.agent.ports.RuleSource
import com.droidance.core.agent.ports.TaskRuntimeInfo
import com.droidance.core.agent.ports.ToolExecutorPort
import com.droidance.core.agent.config.HarnessPolicy
import com.droidance.core.agent.config.HarnessPromptPolicy
import com.droidance.core.agent.config.ToolContractsDocument
import com.droidance.core.agent.debug.DebugLogAppender
import com.droidance.core.agent.debug.TaskLatencyTracker
import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.agent.tools.ObservationEnvelope
import com.droidance.core.agent.tools.OperatorToolProfile
import com.droidance.core.agent.tools.ResultCard
import com.droidance.core.agent.tools.TaskMemoTool
import com.droidance.core.agent.tools.Tool
import com.droidance.core.agent.tools.ToolCatalog
import com.droidance.core.agent.tools.ToolInvocation
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Test-only assembly for the production pure-JVM coordination boundary.
 *
 * The fixture deliberately uses the shipped YAML snapshots and the real
 * TaskCoordinator constructor. It does not recreate a second loop or a reduced
 * TurnSession implementation.
 */
class TaskCoordinatorTestFixture(
    val eventStore: AgentEventStore = InMemoryAgentEventStore(),
    reviewEnabled: Boolean = false,
    stoppedReplyEnabled: Boolean = false,
    retainedImageLimit: Int? = null,
    taskWallMs: Long = 0L,
    modelClientOverride: ModelClient? = null,
    compactorModelOverride: ModelClient? = null,
    compactionChunkInputTokens: Int = 500,
    stoppedReplyReasons: Set<TurnFailureReason>? = null,
    runtimeThresholdsOverride: (com.droidance.core.agent.config.RuntimeThresholds) -> com.droidance.core.agent.config.RuntimeThresholds = { it },
    stoppedReplyTimeoutMs: Long = 1000,
    toolSchemaPolicy: String = "{}"
) {
    val blobStore = InMemoryBlobStore()
    val model = FakeModelClient()
    private val rules = FixtureAgentConfiguration(reviewEnabled, stoppedReplyEnabled, retainedImageLimit, taskWallMs,
        compactionChunkInputTokens, stoppedReplyReasons, runtimeThresholdsOverride, stoppedReplyTimeoutMs, toolSchemaPolicy)
    private val taskWorkspace = TaskWorkspace(eventStore, blobStore)
    private var activeTurnScope: TurnEventScope? = null
    val toolExecutor = RecordingToolExecutor(
        memoToolProvider = {
            TaskMemoTool(
                workspace = taskWorkspace,
                scope = { checkNotNull(activeTurnScope) },
                rules = { section -> rules.readMarkdownSection("workspace.md", section) }
            )
        },
        memoDefinition = TaskMemoTool.definition(rules.readMarkdownSection("workspace.md", "execution_plan"))
    )
    val evidenceLedger = TaskEvidenceLedger()
    val turnLedger = AgentTurnLedger(eventStore, model, blobStore)
    val runtimeInfo = TaskRuntimeInfo("test-build", { 1_000L })
    val strategy = DeviceExecutionStrategy(
        selection = DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
        spaceSelection = ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE)
    )
    val expectedBackend = ExecutionBackendObservation(
        backendKind = ExecutionBackendKind.AIR_SIDECAR,
        displayId = 41,
        backendSessionId = "fixture-backend",
        backendAvailable = true,
        capabilities = DeviceCapability.entries.toSet()
    )

    val diagnostics = mutableListOf<String>()
    private val debug = RecordingDebugLogAppender(diagnostics)
    private val roundCoordinator: DurableModelRoundCoordinator
    val coordinator: TaskCoordinator

    /**
     * Builds the production task lifetime boundary over this fixture's real durable stores.
     * A replacement model is useful for lease failure/cleanup tests; all other dependencies stay
     * the same so those tests cannot accidentally exercise a reduced coordination loop.
     */
    fun createLifecycle(client: ModelClient = model): TaskLifecycle {
        val lifecycleLedger = if (client === model) turnLedger else AgentTurnLedger(eventStore, client, blobStore)
        return TaskLifecycle(
            model = client,
            ledger = lifecycleLedger,
            durableModelRoundCoordinator = roundCoordinator,
            configuration = rules,
            evidenceLedger = evidenceLedger
        )
    }

    fun operatorPrefix() = coordinator.stablePrefix(null, null, OperatorToolProfile.LOCAL_KNOWLEDGE)

    suspend fun runOperator(session: TaskLifecycle.Session, goal: String,
        onContent: suspend (AssistantStreamChunk) -> Unit = {}): TurnResult {
        session.activateTools(session.durableTurn.stablePrefix, TurnActivationKind.LOCAL, preparedAtStart = true)
        bindLocalToolScope(session.durableTurn.scope)
        return coordinator.coordinateTask(taskGoal = goal, additionalContext = "",
            durableTurn = session.durableTurn, turnContext = "", expectedBackend = null,
            executionStrategy = null, toolProfile = OperatorToolProfile.LOCAL_KNOWLEDGE, mode = null,
            host = object : TurnHost { override val onStreamContent = onContent })
    }

    init {
        val probe = object : ExecutionAttemptBackendProbe {
            override suspend fun prepare(route: ExecutionAttemptRoute): ExecutionBackendObservation =
                expectedBackend.copy(backendKind = route.expectedBackend)

            override suspend fun inspect(route: ExecutionAttemptRoute): ExecutionBackendObservation =
                expectedBackend.copy(backendKind = route.expectedBackend)
        }
        roundCoordinator = DurableModelRoundCoordinator(
            eventStore,
            ExecutionAttemptBindingPreparer(probe)
        )
        val textFingerprint = ObservationTextFingerprint(rules)
        val evidenceRecorder = OperatorEvidenceRecorder(
            evidenceLedger = evidenceLedger,
            evidenceFactExtractor = EvidenceFactExtractor(),
            textFingerprint = textFingerprint
        )
        coordinator = TaskCoordinator(TaskTurnServices(
            llmRouter = modelClientOverride ?: model,
            toolExecutor = toolExecutor,
            debugLogManager = debug,
            evidenceLedger = evidenceLedger,
            evidenceRecorder = evidenceRecorder,
            observationFreshnessPolicy = ObservationFreshnessPolicy(),
            ruleConfigManager = rules,
            latencyTracker = TaskLatencyTracker(),
            toolContractCatalog = ToolContractCatalog(rules),
            durableModelRoundCoordinator = roundCoordinator,
            taskContextCompactor = TaskContextCompactor(compactorModelOverride ?: model, rules, blobStore, debug),
            durableContextCheckpointCoordinator = DurableContextCheckpointCoordinator(eventStore),
            eventStore = eventStore,
            blobStore = blobStore,
            runtimeInfo = runtimeInfo
        ))
    }

    suspend fun startTurn(
        requestId: String,
        goal: String,
        profile: OperatorToolProfile = OperatorToolProfile.DEFAULT,
        images: List<String> = emptyList(),
        activate: Boolean = true
    ): StartedTurn {
        val isLocal = profile == OperatorToolProfile.LOCAL_KNOWLEDGE ||
            profile == OperatorToolProfile.BACKGROUND_KNOWLEDGE
        val mode = if (isLocal) null else RuntimeMode.STANDARD
        val space = if (isLocal) null else ExecutionSpace.VIRTUAL_SPACE
        val prefix = coordinator.stablePrefix(mode, space, profile)
        val active = turnLedger.start(
            conversationId = "fixture-$requestId",
            source = MessageSource.DIRECT,
            userText = goal,
            images = images,
            requestId = requestId,
            stablePrefix = prefix,
            onStarted = {}
        )
        if (activate) {
            turnLedger.activateTools(
                activeTurn = active,
                stablePrefix = prefix,
                activationKind = if (isLocal) TurnActivationKind.LOCAL else TurnActivationKind.ANDROID
            )
        }
        activeTurnScope = active.scope
        return StartedTurn(
            active = active.copy(startCommitted = true),
            mode = mode,
            executionSpace = space,
            profile = profile,
            expectedBackend = if (isLocal) null else expectedBackend,
            executionStrategy = if (isLocal) null else strategy
        )
    }

    /** Scripted Provider submits native structured content; runtime owns persistence. */
    fun bindLocalToolScope(scope: TurnEventScope) { activeTurnScope = scope }

    suspend fun recordClosedHistory(scope: TurnEventScope, roundId: String, text: String) {
        val handle = roundCoordinator.begin(scope, roundId, startCommitted = true)
        eventStore.append(AgentEventDraft(eventKind = AgentEventKind.PUBLIC_PROGRESS,
            threadId = scope.threadId, turnId = scope.turnId, roundId = roundId,
            payloadJson = kotlinx.serialization.json.Json.encodeToString(PublicProgressEventPayload.serializer(),
                PublicProgressEventPayload(1, listOf(text), handle.roundDetailRef)),
            idempotencyKey = "$roundId:progress"))
        val round = NormalizedModelRound.create(publicBlocks = listOf(text), toolCalls = emptyList())
        val execution = ExecutionRoundPlanner(emptyList(), emptyList(),
            callIdFactory = { _, _ -> error("History fixture has no tools") }
        ).plan(round, startCommitted = true, repairPurpose = RepairPurpose.NONE)
        roundCoordinator.commit(handle, round, execution, emptyMap())
    }

    suspend fun completionCall(id: String, body: String, scope: TurnEventScope = checkNotNull(activeTurnScope)): LLMStreamEvent.ToolCallChunk =
        LLMStreamEvent.ToolCallChunk(0, id, "done", body)

    suspend fun complete(started: StartedTurn, result: TurnResult) {
        val status = when (result.terminal) {
            TurnTerminal.DONE, TurnTerminal.DIRECT_REPLY -> "done"
            TurnTerminal.NEED_MORE_INFO -> "need_more_info"
            TurnTerminal.FAILED, TurnTerminal.SYSTEM_ERROR -> "failed"
        }
        turnLedger.complete(
            activeTurn = started.active,
            status = status,
            assistantReply = result.summary,
            diagnosticCode = result.failureReason.takeIf { it != TurnFailureReason.NONE }?.name
        )
    }

    fun events(started: StartedTurn): List<AgentEventEnvelope> =
        runBlockingForFixture { eventStore.eventsForTurn(started.active.scope.turnId) }

    data class StartedTurn(
        val active: AgentTurnLedger.ActiveTurn,
        val mode: RuntimeMode?,
        val executionSpace: ExecutionSpace?,
        val profile: OperatorToolProfile,
        val expectedBackend: ExecutionBackendObservation?,
        val executionStrategy: DeviceExecutionStrategy?
    )

    class RecordingToolExecutor(
        private val memoToolProvider: () -> TaskMemoTool,
        private val memoDefinition: ToolDefinition
    ) : ToolExecutorPort {
        override fun canOpenPublicLink(link: String): Boolean = link.startsWith("https://")
        data class CapabilityContextRequest(val packageName: String?, val appName: String?,
                                            val inventoryPackageName: String?, val inventoryQuery: String?,
                                            val maxChars: Int)
        val capabilityContextRequests = mutableListOf<CapabilityContextRequest>()
        override suspend fun capabilityContext(packageName: String?, appName: String?, inventoryPackageName: String?,
                                               inventoryQuery: String?, maxChars: Int): String {
            capabilityContextRequests += CapabilityContextRequest(packageName, appName, inventoryPackageName, inventoryQuery, maxChars)
            return ""
        }

        val deviceCalls = mutableListOf<ToolCall>()
        val localCalls = mutableListOf<ToolCall>()
        var resultOverride: ((ToolCall, Boolean) -> ToolExecutionResult?)? = null
        var definitionOverride: ((String) -> ToolDefinition?)? = null
        var beforeLocalExecute: suspend (ToolCall) -> Unit = {}
        var beforeDeviceExecute: suspend (ToolCall) -> Unit = {}
        var postActionCaptureTools: Set<String> = emptySet()
        var preparedObservationText: String = "Prepared fixture screen"
        var completionRequested = false
        var completionStatusValue = ""
        var completionSummaryValue = ""
        private var resultCardValue: ResultCard? = null
        private var pendingObservation: ObservationEnvelope? = null
        private var modelFrameBound = false

        override var pendingScreenStateText: String? = null
        override val pendingScreenshotBase64: String?
            get() = pendingObservation?.imageBase64

        override suspend fun execute(toolCall: ToolCall): ToolExecutionResult {
            deviceCalls += toolCall
            beforeDeviceExecute(toolCall)
            return executeRecorded(toolCall, local = false)
        }

        override suspend fun executeTurnLocal(toolCall: ToolCall): ToolExecutionResult {
            localCalls += toolCall
            beforeLocalExecute(toolCall)
            if (toolCall.name == "task_memo") {
                return kotlinx.coroutines.withContext(ToolInvocation(toolCall.id)) {
                    memoToolProvider().execute(toolCall.arguments)
                }
            }
            return executeRecorded(toolCall, local = true)
        }

        private fun executeRecorded(toolCall: ToolCall, local: Boolean): ToolExecutionResult {
            resultOverride?.invoke(toolCall, local)?.let { return it }
            if (toolCall.name == "done") {
                val args = runCatching { Json.parseToJsonElement(toolCall.arguments).jsonObject }.getOrNull()
                val status = args?.get("status")?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
                if (status.isNotBlank()) {
                    completionRequested = true
                    completionStatusValue = status
                    completionSummaryValue = args?.get("results")?.jsonArray.orEmpty().mapNotNull {
                        it.jsonObject["text"]?.jsonPrimitive?.content
                    }.joinToString("\n\n")
                }
            }
            if (toolCall.name == "present_result_card") {
                val arguments = com.droidance.core.agent.harness.ResultCardToolArguments.parseCompletion(toolCall.arguments)
                resultCardValue = ResultCard(arguments.title, arguments.items)
            }
            return ToolExecutionResult(
                outputText = if (toolCall.name == "android_read") {
                    "Observed current screen: login complete\nobservation_id=obs_read"
                } else {
                    "Executed ${toolCall.name}"
                },
                executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = if (toolCall.name == "android_read") ToolEvidenceStatus.CAPTURED
                else ToolEvidenceStatus.NOT_REQUIRED,
                observationId = "obs_read".takeIf { toolCall.name == "android_read" }
            )
        }

        override fun getOperatorToolDefinitions(
            mode: RuntimeMode?,
            executionSpace: ExecutionSpace?,
            profile: OperatorToolProfile
        ): List<ToolDefinition> = namesFor(mode, executionSpace, profile).map { definitionFor(it) }

        override fun getOperatorTools(
            mode: RuntimeMode?,
            executionSpace: ExecutionSpace?,
            profile: OperatorToolProfile
        ): List<Tool> = namesFor(mode, executionSpace, profile).map { name ->
            object : Tool {
                private val toolName = name
                override val name: String get() = toolName
                override val definition: ToolDefinition = definitionFor(toolName)
                override suspend fun execute(arguments: String): ToolExecutionResult =
                    ToolExecutionResult("fixture tool $toolName", ToolExecutionStatus.SUCCEEDED, ToolEvidenceStatus.NOT_REQUIRED)
            }
        }

        private fun namesFor(
            mode: RuntimeMode?,
            executionSpace: ExecutionSpace?,
            profile: OperatorToolProfile
        ): Set<String> = ToolCatalog.operatorToolNamesFor(mode, executionSpace, profile)

        private fun definitionFor(name: String): ToolDefinition = definitionOverride?.invoke(name) ?: if (name == "task_memo") {
            memoDefinition
        } else ToolDefinition(
            name = name,
            description = "Fixture definition for $name",
            parameters = if (name == "ask_human") mapOf(
                "boundary_type" to com.droidance.core.model.llm.models.ToolParameter("string", "Boundary", true,
                    enumValues = listOf("live_verification", "secret_credential", "payment_authorization",
                        "irreversible_confirmation", "user_judgment")),
                "reason" to com.droidance.core.model.llm.models.ToolParameter("string", "Reason", true)
            ) else if (name == "done") mapOf(
                "status" to com.droidance.core.model.llm.models.ToolParameter("string", "Status", true,
                    enumValues = listOf("done", "failed", "need_more_info"))
            ) else if (name == "present_result_card") mapOf(
                "title" to com.droidance.core.model.llm.models.ToolParameter("string", "Title", true)
            ) else emptyMap()
        )

        override fun beginFrozenExecutionContext(
            frozen: com.droidance.core.agent.attempt.ExecutionContext,
            strategy: DeviceExecutionStrategy
        ) = Unit

        override fun screenVisualFingerprint(imagePng: ByteArray): Long = imagePng.contentHashCode().toLong()

        override fun screenChangeSignature(imagePng: ByteArray): ScreenChangeSignature? = null

        override fun schedulesPostActionCapture(toolName: String): Boolean = toolName in postActionCaptureTools

        override suspend fun stagePreparedObservation(frameBytes: ByteArray): ToolExecutionResult =
            stageObservation(frameBytes, "obs_initial", preparedObservationText)

        /** Publish an actual envelope when a scripted device read produces a fresh frame. */
        fun stageObservation(
            frameBytes: ByteArray,
            observationId: String,
            screenText: String,
            sequence: Long = 1L,
            actionId: String = "initial",
            nativeFrameBytes: ByteArray? = null
        ): ToolExecutionResult {
            val encoded = Base64.getEncoder().encodeToString(frameBytes)
            pendingObservation = ObservationEnvelope(
                observationId = observationId,
                executionId = "execution:fixture",
                backendSessionId = "fixture-backend",
                displayId = 41,
                rotationDegrees = 0,
                actionId = actionId,
                sequence = sequence,
                capturedAtElapsedMs = 1_000L,
                imageBase64 = encoded,
                nativeImagePng = nativeFrameBytes,
                mode = "PRO",
                frameScale = 1f,
                frameSize = 2 to 2,
                physicalFrameSize = 2 to 2,
                frameOrigin = 0 to 0,
                readableText = null,
                screenText = screenText,
                producedAtMs = 1_000L
            )
            return ToolExecutionResult(
                outputText = screenText,
                executionStatus = ToolExecutionStatus.SUCCEEDED,
                evidenceStatus = ToolEvidenceStatus.CAPTURED,
                observationId = observationId
            )
        }

        override suspend fun awaitPendingScreenshot(timeoutMs: Long): Boolean = pendingObservation != null

        override fun currentPendingObservation(): ObservationEnvelope? = pendingObservation

        override fun consumePendingObservation(): ObservationEnvelope? = pendingObservation.also { pendingObservation = null }

        override fun bindModelFrame(observation: ObservationEnvelope) {
            modelFrameBound = true
        }

        override fun invalidateModelFrameBinding() {
            modelFrameBound = false
        }

        override fun invalidatePendingObservation() {
            pendingObservation = null
        }

        override fun isCompletionRequested(): Boolean = completionRequested

        override fun completionSummary(): String = completionSummaryValue

        override fun completionStatus(): String = completionStatusValue

        override fun clearCompletionRequest() {
            completionRequested = false
            completionStatusValue = ""
            completionSummaryValue = ""
        }

        override fun resetTextInputTransactions() = Unit

        override fun currentResultCard(): ResultCard? = resultCardValue

    }

    private class FixtureAgentConfiguration(private val reviewEnabled: Boolean, private val stoppedReplyEnabled: Boolean,
        retainedImageLimit: Int?, taskWallMs: Long, private val compactionChunkInputTokens: Int,
        stoppedReplyReasons: Set<TurnFailureReason>?,
        runtimeThresholdsOverride: (com.droidance.core.agent.config.RuntimeThresholds) -> com.droidance.core.agent.config.RuntimeThresholds,
        private val stoppedReplyTimeoutMs: Long, private val toolSchemaPolicy: String) : AgentConfiguration {
        private val stoppedReasons = stoppedReplyReasons?.map { it.name } ?: if (stoppedReplyEnabled)
            listOf("TASK_TIME_LIMIT", "MODEL_PROTOCOL_ERROR", "MODEL_TIMEOUT") else emptyList()
        private val json = Json { ignoreUnknownKeys = true }
        private val policy = decode<HarnessPolicy>("harness_policy.json").let { configured ->
            configured.copy(runtimeThresholds = runtimeThresholdsOverride(configured.runtimeThresholds.copy(
                contextRecentImageLimit = retainedImageLimit ?: configured.runtimeThresholds.contextRecentImageLimit,
                maxDeviceTaskWallMs = taskWallMs)))
        }
        private val prompts = decode<HarnessPromptPolicy>("harness_prompts.json")
        private val contracts = decode<ToolContractsDocument>("tool_contracts.json")

        override fun harnessPolicy(): HarnessPolicy = policy
        override fun harnessPrompts(): HarnessPromptPolicy = prompts
        override fun toolContracts(): ToolContractsDocument = contracts
        override fun message(key: String): String = prompts.messages[key] ?: key
        override fun publicFallback(key: String): String = prompts.messages[key] ?: key
        override fun outputLanguage(): String = "English"
        override fun operatorSystemPrompt(): String = "Configured fixture operator rules."
        override fun taskWallMinutes(): Int = 0

        override fun readMarkdownSection(fileName: String, heading: String): String = when {
            fileName == "agent_core.md" && heading == "reasoning_control_policy" ->
                """{"initialEffort":"MEDIUM","adaptiveEfforts":["LOW","MEDIUM","HIGH"],"minimumDecisionsBetweenChanges":2}"""
            fileName == "model_context.md" && heading == "recent_tool_feedback_limit" -> "8"
            fileName == "tool_context.md" && heading == "schema_context_policy" -> toolSchemaPolicy
            fileName == "utility_prompts.md" && heading == "tool_repetition_policy" ->
                """{"historySize":24,"warningThreshold":4,"ignoredArgumentKeys":["action_title","task_title"],"ignoredResultKeys":["source_ref","observation_id","execution_id","request_id"]}"""
            fileName == "context_compaction.md" && heading == "execution_policy" ->
                "{\"requestTimeoutMs\":1000,\"totalTimeoutMs\":3000,\"requestRetries\":0,\"maxOutputTokens\":1000,\"retryMaxOutputTokens\":1000,\"chunkInputTokens\":$compactionChunkInputTokens,\"maxSoftDeferrals\":0,\"deferralHeadroomTokens\":100}"
            fileName == "observation_recovery.md" && heading == "policy" ->
                "{\"attempts\":2,\"maxCurrentFrameCrops\":2,\"observationScopedActions\":[],\"maxFingerprintDistance\":4,\"historyLimit\":8,\"scopeHistoryLimit\":4,\"coordinateTolerance\":2,\"refreshAfterTools\":[],\"refreshAfterDiagnostics\":[\"RECOVERY\"],\"actions\":{\"screen_act\":[\"observe_screen\"]},\"effectChangeFraction\":0.002,\"effectChannelDelta\":24}"
            fileName == "device_tools.md" && heading == "capability_context_policy" ->
                "{\"maxChars\":12000}"
            fileName == "workspace.md" && heading == "record_repair_policy" ->
                """{"maxRepairRounds":4}"""
            fileName == "workspace.md" && heading == "context_policy" ->
                "{\"maxActiveChars\":2048,\"deviceChangesBeforeReview\":1,\"browsingOperations\":{\"screen_act\":[\"scroll\",\"swipe\"]}}"
            fileName == "workspace.md" && heading == "policy" ->
                "{\"maxFiles\":8,\"maxFileChars\":4096,\"maxRetainedBytes\":16384,\"pageChars\":64}"
            fileName == "workspace.md" && heading == "execution_policy" ->
                "{\"reviewAfterCalls\":6,\"maxObservationRecoveries\":24,\"maxCallsPerTask\":120,\"maxRejectedCalls\":3}"
            fileName == "workspace.md" && heading == "execution_plan" ->
                "Configured execution plan rules."
            fileName == "stopped_task_reply.md" && heading == "policy" ->
                "{\"eligibleReasons\":${stoppedReasons.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},\"timeoutMs\":$stoppedReplyTimeoutMs,\"maxOutputTokens\":1000,\"maxContextChars\":1000,\"reasoningEffort\":\"LOW\"}"
            fileName == "completion_recovery.md" && heading == "review_before_delivery" -> reviewEnabled.toString()
            fileName == "completion_recovery.md" && heading == "source_image_batch_limit" -> "3"
            fileName == "completion_recovery.md" && heading == "max_rejected_completions" -> "3"
            else -> "Fixture rule $fileName#$heading {round} {tool} {args} {seconds} {language} {observationId} {width} {height} {note}"
        }

        private inline fun <reified T> decode(name: String): T =
            json.decodeFromString(checkNotNull(javaClass.getResourceAsStream("/com/droidance/agent/$name"))
                .bufferedReader().use { it.readText() })
    }

    private class RecordingDebugLogAppender(private val events: MutableList<String>) : DebugLogAppender {
        override fun appendEvent(event: String) { events.add(event) }
        override fun appendPersistentEvent(stream: String, event: String) { events.add("$stream: $event") }
    }
}

private fun <T> runBlockingForFixture(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
