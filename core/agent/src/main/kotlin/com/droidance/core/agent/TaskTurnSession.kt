// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.workspace.TaskExecutionPolicy
import com.droidance.core.agent.workspace.WorkspaceContextPolicy

import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.PendingDetailAttachment
import com.droidance.core.agent.harness.materializeDetailAttachments
import com.droidance.core.agent.attempt.AttemptBindingResolution
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolLifecycleEventScope
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.coroutineContext
import com.droidance.core.agent.debug.recordRuntimeFailure

internal class TaskTurnSession(
    internal val services: TaskTurnServices,
    internal val taskGoal: String,
    internal val additionalContext: String,
    internal val durableTurn: AgentTurnLedger.ActiveTurn,
    internal val turnContext: String,
    internal val expectedBackend: com.droidance.core.agent.attempt.ExecutionBackendObservation?,
    internal var executionStrategy: com.droidance.core.agent.device.DeviceExecutionStrategy?,
    internal val initialScreenRequired: Boolean,
    internal val initialScreenFrame: ByteArray?,
    internal val toolProfile: OperatorToolProfile,
    internal val mode: com.droidance.core.agent.device.RuntimeMode?,
    internal val host: TurnHost
) {
    internal val llmRouter get() = services.llmRouter
    internal val toolExecutor get() = services.toolExecutor
    internal val debugLogManager get() = services.debugLogManager
    internal val evidenceLedger get() = services.evidenceLedger
    internal val evidenceRecorder get() = services.evidenceRecorder
    internal val observationFreshnessPolicy get() = services.observationFreshnessPolicy
    internal val ruleConfigManager get() = services.ruleConfigManager
    internal val latencyTracker get() = services.latencyTracker
    internal val toolContractCatalog get() = services.toolContractCatalog
    internal val durableModelRoundCoordinator get() = services.durableModelRoundCoordinator
    internal val taskContextCompactor get() = services.taskContextCompactor
    internal val durableContextCheckpointCoordinator get() = services.durableContextCheckpointCoordinator
    internal val eventStore get() = services.eventStore
    internal val blobStore get() = services.blobStore
    internal val runtimeInfo get() = services.runtimeInfo
    internal val MAX_TASK_NOTE_CHARS get() = services.MAX_TASK_NOTE_CHARS
    internal val workspaceContextReader get() = services.workspaceContextReader
    internal val modelContextAssembler get() = services.modelContextAssembler
    internal val operatorStreamCollector get() = services.operatorStreamCollector
    internal val ACTION_NARRATIVE_LIMIT get() = TaskTurnServices.ACTION_NARRATIVE_LIMIT
    internal fun stablePrefix(
        mode: com.droidance.core.agent.device.RuntimeMode?,
        executionSpace: com.droidance.core.agent.device.ExecutionSpace?,
        toolProfile: OperatorToolProfile = OperatorToolProfile.DEFAULT,
        extraSystemContext: String = ""
    ) = services.stablePrefix(mode, executionSpace, toolProfile, extraSystemContext)
    internal fun buildOperatorTooling(
        mode: com.droidance.core.agent.device.RuntimeMode?,
        executionSpace: com.droidance.core.agent.device.ExecutionSpace?,
        communication: com.droidance.core.agent.config.CommunicationContract,
        toolProfile: OperatorToolProfile = OperatorToolProfile.DEFAULT
    ) = services.buildOperatorTooling(mode, executionSpace, communication, toolProfile)
    internal fun operatorSystemPrompt() = services.operatorSystemPrompt()
    internal fun trustedOperatorSystemPrompt(extraSystemContext: String) = services.trustedOperatorSystemPrompt(extraSystemContext)
    internal fun guardText(key: String, values: Map<String, String> = emptyMap()) = services.guardText(key, values)
    internal fun fallbackText(key: String, values: Map<String, String> = emptyMap()) = services.fallbackText(key, values)
    internal fun screenshotMimeType(bytes: ByteArray) = services.screenshotMimeType(bytes)
    internal fun jsonStringArg(arguments: String, vararg keys: String) = services.jsonStringArg(arguments, *keys)
    internal fun isScreenObservation(toolCall: ToolCall) = services.isScreenObservation(toolCall)
    internal fun promptValuePreview(value: String, maxChars: Int) = services.promptValuePreview(value, maxChars)
    internal fun interventionPublicReply(toolCalls: List<ToolCall>, publicText: String) = services.interventionPublicReply(toolCalls, publicText)
    internal fun looksLikeRootUnavailable(result: ToolExecutionResult) = services.looksLikeRootUnavailable(result)
    internal fun isLlmTransportTimeout(error: Throwable) = services.isLlmTransportTimeout(error)
    internal fun buildActionLabel(tc: ToolCall) = services.buildActionLabel(tc)
    internal val onPrepareDevice get() = host.onPrepareDevice
    internal val onExecutionStarted get() = host.onExecutionStarted
    internal val onStreamContent get() = host.onStreamContent
    internal val onActionUpdate get() = host.onActionUpdate
    internal val onTaskTitle get() = host.onTaskTitle
    internal val onTaskDeadline get() = host.onTaskDeadline
    internal val checkTaskDeadline get() = host.checkTaskDeadline
    internal val onManualTakeoverNeeded get() = host.onManualTakeoverNeeded
    internal val stateFlow get() = host.stateFlow
    internal val waitIfPaused get() = host.waitIfPaused
    internal val isPaused get() = host.isPaused
    internal val runInterruptibleTool get() = host.runInterruptibleTool
    internal val takeManualResumeInstruction get() = host.takeManualResumeInstruction
    internal val refreshMemoryContext get() = host.refreshMemoryContext
    internal val trySealInput get() = host.trySealInput
    internal val roundContext get() = host.roundContext

    val localKnowledge = toolProfile == OperatorToolProfile.LOCAL_KNOWLEDGE ||
        toolProfile == OperatorToolProfile.BACKGROUND_KNOWLEDGE
    init {
        require(localKnowledge == (executionStrategy == null))
        require(!localKnowledge || expectedBackend == null)
    }
    val preloadedScreenState = if (localKnowledge) null else toolExecutor.pendingScreenStateText?.also {
        toolExecutor.pendingScreenStateText = null
    }
    init {
        preloadedScreenState?.let {
            evidenceRecorder.recordObservedState(taskGoal, "observe_screen", it)
        }
    }

    // 当前回合先使用最小入口消息；回合关闭后由 EventStore typed projection 重建历史。
    val messages = mutableListOf<ChatMessage>()
    internal val requestHistory = com.droidance.core.agent.context.CacheableRequestHistory()
    internal val conversationTitles = ConversationTitleSession(eventStore, durableTurn.scope)
    internal var taskTitle: String? = null
    internal var stoppedReplyContextAvailable = true

    init {
        if (turnContext.isNotBlank()) {
            messages.add(ChatMessage(role = Role.SYSTEM, content = turnContext))
        }
    }

    val stepPrompt = modelContextAssembler.initialStepPrompt(taskGoal, additionalContext, preloadedScreenState)
    init { messages.add(ChatMessage(role = Role.USER, content = stepPrompt)) }

    val harnessPolicy = ruleConfigManager.harnessPolicy()
    val reasoningControl = ReasoningControl(services.reasoningControlPolicy())
    val operatorTooling = buildOperatorTooling(
        mode,
        executionStrategy?.spaceSelection?.effectiveSpace,
        ruleConfigManager.harnessPrompts().communicationContract,
        toolProfile
    )
    val operatorRoundScheduler = operatorTooling.scheduler
    val completionGroundingByTool = operatorTooling.completionGroundingByTool
    internal val imageDigestCache = EncodedImageDigestCache()
    val thresholds = harnessPolicy.runtimeThresholds
    val tools = durableTurn.stablePrefix.tools
    var rounds = 0
    var workingRecordInitializationFailure: JsonObject? = null
    var finalOutput = ""
    var finalTerminal = TurnTerminal.FAILED
    var totalToolCalls = 0
    var executionToolCalls = 0
    val executedToolNames = mutableListOf<String>()
    // 只有后台调度轮才把接管配置视为它自己的任务生命周期；普通交互中的接管
    // 调用仍需由模型明确判断并收尾，不能用配置回执绕过一次性设备任务的证据门槛。
    var takeoverManaged = false
    // 动作叙事：跨轮保留"做过什么、结果如何"的一行式记录，替代把多张历史帧塞回请求。
    val actionNarrative = ArrayDeque<String>()
    // 保留最近的本地拒绝原因，跨上下文压缩提示模型，避免
    // 只看到“已有结果”却忘了某个限定条件仍未被当前画面证明。
    val blockedActionNarrative = ArrayDeque<String>()
    // 只记录本地验证明确确认推动了用户目标的动作；普通运行时生效或页面观察不算进展。
    var channelUnavailableRepeats = 0
    var lastMemoryRefreshInput = 0L
    var lastMemoryRefreshPackage: String? = null
    var experienceContextRefreshes = 0
    var emptyResponseNudged = false
    var repetitionDirective: ChatMessage? = null
    val repetitionFacts = ToolRepetitionFacts(Json.decodeFromString<ToolRepetitionPolicy>(
        ruleConfigManager.readMarkdownSection("utility_prompts.md", "tool_repetition_policy")))
    var llmTransientRetries = 0
    /** Output-limit truncations in this turn; each retry could spend the full budget again. */
    var modelOutputTruncations = 0
    val modelRecoveryBudget = ModelRecoveryBudget(thresholds.llmRecoveryTimeoutMs)
    var requestMetrics = emptyMap<String, String>()
    var modelOutputBudget = thresholds.llmActionMaxOutputTokens
    val completionGroundingGate = CompletionGroundingGate()
    val completionReviewGate = CompletionReviewGate(
        ruleConfigManager.readMarkdownSection("completion_recovery.md", "review_before_delivery").trim().toBooleanStrict()
    )
    val workspaceContextPolicy = Json.decodeFromString<WorkspaceContextPolicy>(
        ruleConfigManager.readMarkdownSection("workspace.md", "context_policy")
    ).also { it.validate() }
    val modelRecordRepairPolicy = Json.decodeFromString<com.droidance.core.agent.event.ModelRecordRepairPolicy>(
        ruleConfigManager.readMarkdownSection("workspace.md", "record_repair_policy")
    ).also { it.validate() }
    var modelRecordRepairs = com.droidance.core.agent.event.ModelRecordRepairs.Snapshot(emptyMap(), 0)
    val reviewImageBatchLimit = ruleConfigManager.readMarkdownSection(
        "completion_recovery.md", "source_image_batch_limit"
    ).trim().toInt().also { require(it > 0) }
    val completionRejectionCycleGuard = CompletionRejectionCycleGuard(
        ruleConfigManager.readMarkdownSection("completion_recovery.md", "max_rejected_completions").trim().toInt()
    )
    val compactionPolicy = Json.decodeFromString<com.droidance.core.agent.context.ContextCompactionExecutionPolicy>(
        ruleConfigManager.readMarkdownSection("context_compaction.md", "execution_policy")
    ).also { it.validate() }
    var memoryCheckpointOffered = false
    val compactionRecovery = com.droidance.core.agent.context.ContextCompactionRecovery(compactionPolicy)
    var completionRepairDirective: ChatMessage? = null
    var observationRecoveryDirective: ChatMessage? = null
    var recordRecoveryPending = false
    var completionReviewDirective: ChatMessage? = null
    val completionReviewContext = TaskSourceContext("review_feedback")
    val observationReplanContext = TaskSourceContext("recovery_feedback")
    val taskExecutionPolicy = Json.decodeFromString<TaskExecutionPolicy>(
        ruleConfigManager.readMarkdownSection("workspace.md", "execution_policy")
    ).also { it.validate() }
    fun observationReplanInstruction(): String =
        ruleConfigManager.readMarkdownSection("observation_recovery.md", "replan_context")
    var visionUnavailableNotified = false
    var latestScreenStateForReflector: String? = preloadedScreenState
    var groundedTextObservation: String? = preloadedScreenState
    var lastToolResultForReflector: String? = null
    var lastExecutionDiagnostic: String? = null
    var reusablePostActionObservation: String? = null
    var avoidRedundantObservationPromptForNextRound = false
    var freshObservationRequired = false
    var forceFreshObservationCapture = false
    var emptyDisplayObservation: ToolExecutionResult? = null
    var currentObservationLease: com.droidance.core.agent.tools.ObservationEnvelope? = null
    // The raw screen reference made visible in the current model request. It must be
    // retained outside runModelRound because terminal-tool validation happens in the
    // later tool-dispatch scope.
    var terminalObservationRefVisibleToModel: String? = null
    var previousCapabilityContext: String? = null
    var executionStarted = false
    var deviceExecutionRequested = false
    // START is a durable conversation fact, while executionStarted means that at least one
    // device call passed preflight. A rejected first action can commit START without executing.
    val roundSession = OperatorRoundSession(durableModelRoundCoordinator, operatorRoundScheduler, durableTurn.scope, durableTurn.startCommitted, operatorTooling.contentTools.associate { it.name to it.parameters })
    var pendingPostActionAttachmentScope: ToolLifecycleEventScope? = null
    var pendingPostActionResult: DetailAttachmentAddedEventPayload? = null
    var contentStreamed = false
    val executionClock = durableTurn.executionClock
    val channelUnavailableStopRepeats = thresholds.channelUnavailableStopRepeats
    val exactActionRepeatGuard = ExactActionRepeatGuard()
    val recoveryPolicy = Json.decodeFromString<ObservationRecoveryPolicy>(
        ruleConfigManager.readMarkdownSection("observation_recovery.md", "policy")
    ).also { it.validate() }
    val observationRecovery = ObservationRecovery(recoveryPolicy)
    val userWallMinutes = ruleConfigManager.taskWallMinutes()
    val maxDeviceTaskWallMs = if (userWallMinutes > 0) {
        userWallMinutes * 60_000L
    } else {
        thresholds.maxDeviceTaskWallMs
    }
    val llmTransientRetryLimit = thresholds.llmTransientRetryLimit
    val llmTransientRetryBackoffMs = thresholds.llmTransientRetryBackoffMs
    val toolExecutionTimeoutMs = thresholds.toolExecutionTimeoutMs
    val permissionToolExecutionTimeoutMs = thresholds.manualTakeoverWaitTimeoutMs
        .takeIf { it > 0L }
        ?.let { Math.addExact(it, toolExecutionTimeoutMs) }
        ?: 0L
    suspend fun waitForActiveExecution() {
        waitIfPaused()
    }

    suspend fun executeAuxiliaryObservation(call: ToolCall): ToolExecutionResult =
        runInterruptibleTool { toolExecutor.execute(call) }

    fun activeElapsedMs(): Long = executionClock.elapsedMs()

    fun wallStopMessage(elapsedMs: Long): String {
        return guardText(
            "max_device_task_wall_stop",
            mapOf("seconds" to (elapsedMs / 1000L).toString())
        )
    }

    fun finalReplyProtocolFailure(
        output: String = finalOutput,
        failureReason: TurnFailureReason = TurnFailureReason.MODEL_PROTOCOL_ERROR
    ): TurnResult {
        return TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = guardText("terminal_protocol_error"),
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
            operatorOutput = output,
            rounds = rounds,
            failureReason = failureReason,
            takeoverManaged = takeoverManaged,
            terminal = TurnTerminal.SYSTEM_ERROR
        )
    }

    fun completionProtocolUnconfirmed(): TurnResult {
        val output = fallbackText("termination_model_protocol_error")
        return TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = output,
            runtimeFailureSummary = output,
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
            operatorOutput = output,
            rounds = rounds,
            failureReason = TurnFailureReason.MODEL_PROTOCOL_ERROR,
            terminal = TurnTerminal.SYSTEM_ERROR,
            takeoverManaged = takeoverManaged
        )
    }

    suspend fun groundedGuardStop(diagnostic: String? = lastExecutionDiagnostic): TurnResult {
        val output = fallbackText(guardStopNoticeKey(diagnostic))
        return TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = output,
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
            operatorOutput = output,
            rounds = rounds,
            failureReason = TurnFailureReason.GUARD_STOP,
            stopDiagnostic = diagnostic,
            terminal = TurnTerminal.SYSTEM_ERROR,
            takeoverManaged = takeoverManaged,
            contentStreamed = contentStreamed
        )
    }

    fun modelTimeoutTerminal(output: String): TurnResult =
        TurnResult(
                hasToolCalls = totalToolCalls > 0,
                toolNames = executedToolNames.toList(),
                summary = fallbackText("termination_model_timeout"),
                evidenceText = evidenceLedger.buildOperatorEvidenceText(),
                operatorOutput = output,
                rounds = rounds,
                failureReason = TurnFailureReason.MODEL_TIMEOUT,
                stopDiagnostic = requestMetrics["response_timeout_phase"]?.takeIf(String::isNotBlank)
                    ?.let { "MODEL_TIMEOUT_$it" } ?: "MODEL_TIMEOUT",
                terminal = TurnTerminal.SYSTEM_ERROR,
                takeoverManaged = takeoverManaged,
                contentStreamed = contentStreamed
            )

    suspend fun ensureExecutionStarted(firstToolName: String) {
        if (executionStarted) return
        onExecutionStarted(firstToolName)
        executionStarted = true
    }

    /**
     * A Provider request may fail after the durable ModelRound start but before it yields a
     * normalized response. Close that round with a typed terminal diagnostic so the Turn
     * never contains an orphan MODEL_ROUND_STARTED fact.
     */
    suspend fun settlePendingDurableRound(diagnosticCode: String) = roundSession.settle(diagnosticCode, requestMetrics)

    suspend fun retryModelRequest(diagnostic: String, error: Throwable? = null): Boolean {
        if (!ModelRoundRetryPolicy.shouldRetry(false, false, llmTransientRetries, llmTransientRetryLimit)) return false
        val retryDelay = modelRecoveryBudget.retryDelayMs(llmTransientRetryBackoffMs, llmTransientRetries + 1, error)
            ?: return false
        settlePendingDurableRound(diagnostic)
        llmTransientRetries++
        debugLogManager.appendEvent("Retrying model decision: diagnostic=$diagnostic attempt=$llmTransientRetries delay_ms=$retryDelay remaining_ms=${modelRecoveryBudget.remainingMs()}")
        delay(retryDelay)
        return true
    }

    var lastStreamRoundId: String? = null
    suspend fun beginDurableRound(repairPurpose: RepairPurpose) {
        roundSession.begin(repairPurpose)
        lastStreamRoundId = roundSession.pending?.scope?.roundId
    }

    var devicePrepared = false
    suspend fun ensureDurableExecutionBinding(repairPurpose: RepairPurpose) {
        if (localKnowledge) return
        if (!devicePrepared) {
            executionStrategy = onPrepareDevice() ?: executionStrategy
            devicePrepared = true
        }
        beginDurableRound(repairPurpose)
        val handle = roundSession.pending ?: return
        val binding = (durableModelRoundCoordinator.prepareDeviceBinding(
            handle = handle,
            strategy = requireNotNull(executionStrategy),
            expectedBackend = expectedBackend
        ) as? AttemptBindingResolution.Ready)?.binding
        if (binding != null) {
            toolExecutor.beginFrozenExecutionContext(
                frozen = binding.attempt.context,
                strategy = requireNotNull(executionStrategy)
            )
        }
    }

    suspend fun streamFallbackContent(content: String) {
        val clean = content.trim()
        if (contentStreamed || clean.isBlank()) return
        onStreamContent(AssistantStreamChunk(clean, lastStreamRoundId))
        contentStreamed = true
    }

    suspend fun persistPendingPostActionEvidence(
        observationId: String,
        frameBytes: ByteArray?,
        frameText: String?,
        modelImageBytes: ByteArray? = null
    ) {
        val retained = eventStore.eventsForTurn(durableTurn.scope.turnId)
        if (retained.any { event -> event.eventKind == com.droidance.core.agent.event.AgentEventKind.DETAIL_ATTACHMENT_ADDED &&
            Json.decodeFromString<DetailAttachmentAddedEventPayload>(event.payloadJson).observationId == observationId }) {
            pendingPostActionAttachmentScope = null
            pendingPostActionResult = null
            return
        }
        // Recovery/manual-resume frames use a settled call as their storage anchor.
        // CURRENT_OBSERVATION does not claim that this call caused the later frame;
        // its execution status remains unchanged, including NOT_EXECUTED.
        val scope = pendingPostActionAttachmentScope ?: retained.lastOrNull {
            it.eventKind == com.droidance.core.agent.event.AgentEventKind.TOOL_FINISHED && it.callId != null && it.roundId != null
        }?.let { ToolLifecycleEventScope(it.threadId, checkNotNull(it.turnId), checkNotNull(it.roundId),
            checkNotNull(it.callId), it.attemptId, it.executionId) } ?: return
        if (observationId == pendingPostActionResult?.observationId) {
            pendingPostActionAttachmentScope = null
            pendingPostActionResult = null
            return
        }
        val imageBytes = frameBytes?.takeIf(ByteArray::isNotEmpty)
        val textBytes = frameText
            ?.takeIf(String::isNotBlank)
            ?.toByteArray(Charsets.UTF_8)
        val content = imageBytes ?: textBytes
            ?: error("A model-visible post-action observation requires durable content")
        val mimeType = if (imageBytes != null) screenshotMimeType(content) else "text/plain"
        val attachment = materializeDetailAttachments(
            blobStore = blobStore,
            attachments = listOf(
                PendingDetailAttachment(
                    attachmentId = "${scope.callId}:observation:$observationId",
                    role = DetailAttachmentRole.CURRENT_OBSERVATION,
                    bytes = content,
                    mimeType = mimeType,
                    observationId = observationId,
                    modelImageBytes = modelImageBytes
                )
            )
        ).single()
        eventStore.addDetailAttachment(scope, attachment)
        pendingPostActionAttachmentScope = null
        pendingPostActionResult = null
    }

    suspend fun retainEmptyDisplayObservation(result: ToolExecutionResult) {
        // An empty display is a platform fact, never a grounded screen or completion source.
        currentObservationLease = null
        terminalObservationRefVisibleToModel = null
        groundedTextObservation = null
        latestScreenStateForReflector = null
        reusablePostActionObservation = null
        avoidRedundantObservationPromptForNextRound = false
        toolExecutor.invalidateModelFrameBinding()
        toolExecutor.invalidatePendingObservation()
        freshObservationRequired = false
        forceFreshObservationCapture = false
        emptyDisplayObservation = result

        // Automatic observations have no model-requested call. Retain their receipt as a
        // diagnostic on the latest settled call without changing its original outcome.
        val anchor = eventStore.eventsForTurn(durableTurn.scope.turnId).lastOrNull {
            it.eventKind == com.droidance.core.agent.event.AgentEventKind.TOOL_FINISHED &&
                it.callId != null && it.roundId != null
        } ?: return
        val scope = ToolLifecycleEventScope(anchor.threadId, checkNotNull(anchor.turnId),
            checkNotNull(anchor.roundId), checkNotNull(anchor.callId), anchor.attemptId, anchor.executionId)
        val attachment = materializeDetailAttachments(blobStore, listOf(PendingDetailAttachment(
            attachmentId = "${scope.callId}:empty-display:${java.util.UUID.randomUUID()}",
            role = DetailAttachmentRole.DIAGNOSTIC,
            bytes = result.outputText.toByteArray(Charsets.UTF_8),
            mimeType = "application/json"
        ))).single()
        eventStore.addDetailAttachment(scope, attachment)
    }

    fun appendGuardFeedbackToRecentToolResult(feedback: String) {
        val index = messages.indexOfLast { it.role == Role.TOOL }
        if (index < 0) return
        val message = messages[index]
        messages[index] = message.copy(
            content = message.content.trimEnd() + "\n\n" + feedback.trim()
        )
    }

    fun attachScreenStateObservation(refreshedObservation: String): ObservationFrame {
        latestScreenStateForReflector = refreshedObservation
        val observation = evidenceRecorder.recordObservedState(taskGoal, "observe_screen", refreshedObservation)
        completionGroundingGate.recordFreshGroundedObservation()
        return observation
    }

    suspend fun resetAfterManualResume(instruction: String, event: String) {
        // Preparation may already own a round before its model request is dispatched.
        settlePendingDurableRound("USER_CONTEXT_CHANGED")
        emptyDisplayObservation = null
        messages.add(ChatMessage(role = Role.SYSTEM, content = instruction))
        repetitionFacts.reset()
        repetitionDirective?.let(messages::remove)
        repetitionDirective = null
        emptyResponseNudged = false
        exactActionRepeatGuard.reset()
        observationRecovery.reset()
        observationRecoveryDirective?.let(messages::remove)
        observationRecoveryDirective = null
        recordRecoveryPending = false
        forceFreshObservationCapture = devicePrepared
        toolExecutor.invalidateModelFrameBinding()
        toolExecutor.invalidatePendingObservation()
        completionRejectionCycleGuard.reset()
        completionReviewGate.reset()
        completionReviewContext.reset()
        observationReplanContext.reset()
        completionReviewDirective?.let(messages::remove)
        completionReviewDirective = null
        completionRepairDirective?.let(messages::remove)
        completionRepairDirective = null
        llmTransientRetries = 0
        modelRecoveryBudget.reset()
        toolExecutor.resetTextInputTransactions()
        latestScreenStateForReflector = null
        groundedTextObservation = null
        reusablePostActionObservation = null
        avoidRedundantObservationPromptForNextRound = false
        currentObservationLease = null
        pendingPostActionAttachmentScope = null
        pendingPostActionResult = null
        terminalObservationRefVisibleToModel = null
        freshObservationRequired = devicePrepared
        blockedActionNarrative.clear()
        debugLogManager.appendEvent(event)
    }

    fun settleLocallyRejectedToolCall(
        toolCall: ToolCall,
        rejectionKey: String,
        beforeObservationId: String?,
        countsAsNoProgress: Boolean = true
    ) {
        if (!isScreenObservation(toolCall)) {
            actionNarrative.addLast(
                guardText(
                    "working_memory_action_line",
                    mapOf(
                        "round" to rounds.toString(),
                        "tool" to toolCall.name,
                        "args" to promptValuePreview(toolCall.arguments, 80),
                        "outcome" to "rejected:$rejectionKey"
                    )
                )
            )
            while (actionNarrative.size > ACTION_NARRATIVE_LIMIT) actionNarrative.removeFirst()
            blockedActionNarrative.addLast(
                "${toolCall.name}: ${rejectionKey.take(120)}"
            )
            while (blockedActionNarrative.size > ACTION_NARRATIVE_LIMIT) {
                blockedActionNarrative.removeFirst()
            }
        }
    }

    suspend fun runOperatorLoop(): TurnResult {
        operatorLoop@ while (true) {
            checkTaskDeadline()
            coroutineContext.ensureActive()
            waitForActiveExecution()
            takeManualResumeInstruction()?.let { instruction ->
                resetAfterManualResume(instruction,
                    "manual takeover resumed; reset loop guards and forcing fresh observation")
            }
            rounds++
            requestMetrics = emptyMap()
            val elapsedMs = activeElapsedMs()
            if (maxDeviceTaskWallMs > 0 && elapsedMs >= maxDeviceTaskWallMs) {
                return finalReplyProtocolFailure(failureReason = TurnFailureReason.TASK_TIME_LIMIT)
            }
            val repairPurpose = RepairPurpose.NONE
            when (val outcome = runModelRound(repairPurpose)) {
                ModelRoundOutcome.RestartRound -> continue@operatorLoop
                is ModelRoundOutcome.Terminal -> return outcome.result
                is ModelRoundOutcome.Ready -> runToolRound(outcome.output)?.let { return it }
            }
        }
    }

    fun finishTurnResult(): TurnResult {
        val summary = PublicAssistantText.sanitize(finalOutput).takeIf(String::isNotBlank)
            ?: finalOutput.trim().ifBlank { guardText("empty_final_response") }
        return TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = summary,
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
            operatorOutput = finalOutput,
            rounds = rounds,
            taskDoneByModel = toolExecutor.isCompletionRequested(),
            taskDoneStatus = toolExecutor.completionStatus().takeIf { toolExecutor.isCompletionRequested() },
            terminal = finalTerminal,
            takeoverManaged = takeoverManaged,
            contentStreamed = contentStreamed
        )
    }

    suspend fun execute(): TurnResult = try {
        // The task already owns its storage scope. Opening a blank working record is
        // runtime bookkeeping, not a model tool call, plan, finding or completion source.
        try {
            if (workspaceContextReader.list(durableTurn.scope).isEmpty()) {
                workspaceContextReader.write(durableTurn.scope, "work.md", "", false, null,
                    "initialize-working-record", Json.decodeFromString(
                        ruleConfigManager.readMarkdownSection("workspace.md", "policy")), activate = true)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            workingRecordInitializationFailure = buildJsonObject {
                put("code", JsonPrimitive("WORKING_RECORD_INITIALIZATION_FAILED"))
                put("error_type", JsonPrimitive(error::class.java.simpleName))
            }
            debugLogManager.appendEvent("Working record initialization failed: ${error::class.java.simpleName}")
        }
        var result: TurnResult
        do {
            result = try {
                runOperatorLoop()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val reference = java.util.UUID.randomUUID().toString().take(8)
                debugLogManager.recordRuntimeFailure(reference,
                    "phase=operator turn=${durableTurn.scope.turnId} round=$rounds tools=${executedToolNames.joinToString(",")} diagnostic=$lastExecutionDiagnostic", error)
                debugLogManager.appendEvent("Agent execution exception: ${error::class.java.simpleName}: ${error.message.orEmpty().take(500)}")
                settlePendingDurableRound("RUNTIME_EXCEPTION")
                TurnResult(hasToolCalls = totalToolCalls > 0, toolNames = executedToolNames.toList(),
                    summary = fallbackText("termination_internal_error"),
                    evidenceText = evidenceLedger.buildOperatorEvidenceText(), rounds = rounds,
                    failureReason = TurnFailureReason.RUNTIME_EXCEPTION, terminal = TurnTerminal.SYSTEM_ERROR,
                    runtimeFailureSummary = fallbackText("termination_internal_error_detail", mapOf(
                        "error_type" to error.javaClass.simpleName, "reference" to reference)),
                    stopDiagnostic = "${error::class.java.simpleName}: ${error.message.orEmpty().take(500)}")
            }
        } while (!trySealInput())
        explainStoppedTask(result)
    } finally {
        // Cancellation can happen while the Provider stream is in flight. The durable start
        // must still be closed before AgentRuntime attempts to complete the Turn.
        withContext(NonCancellable) {
            kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                runCatching { settlePendingDurableRound("TURN_ABORTED") }
            }
        }
    }

}
