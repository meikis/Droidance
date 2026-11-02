// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import kotlinx.serialization.encodeToString

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.tools.DeviceToolNames
import com.droidance.core.agent.workspace.TaskExecutionProgress
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspaceBrowsingProgress

import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.context.ContextCheckpointDecision
import com.droidance.core.agent.context.DurableContextMessageProjector
import com.droidance.core.agent.context.DurableContextProjector
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.MemoryContextLoadedEventPayload
import com.droidance.core.agent.event.MemoryContextScope
import com.droidance.core.model.llm.ProviderErrorCategory
import com.droidance.core.model.llm.providerHttpException
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.toJsonSchema
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

internal suspend fun TaskTurnSession.runModelRound(repairPurpose: RepairPurpose): ModelRoundOutcome {
    val inputs = eventStore.eventsForTurn(durableTurn.scope.turnId)
    val previousReasoningMetadata = inputs.asReversed().asSequence()
        .filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
        .map { Json.decodeFromString<com.droidance.core.agent.event.ModelRoundCompletedEventPayload>(it.payloadJson).finishMetadata }
        .firstOrNull { "reasoning_next_effort" in it }
        .orEmpty()
    reasoningControl.restore(previousReasoningMetadata)
    val latestInput = inputs.lastOrNull { it.eventKind == AgentEventKind.USER_STEER }
    val latestMemory = inputs.lastOrNull { it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED }
    val foregroundPackage = evidenceLedger.latestGroundedObservation()?.foregroundPackage?.takeIf(String::isNotBlank)
    val userInputs = inputs.filter { it.eventKind in setOf(AgentEventKind.USER_MESSAGE, AgentEventKind.USER_STEER) }
    val memoryQuery by lazy {
        userInputs.joinToString("\n") { Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(it.payloadJson).text }
    }
    try {
        if (latestInput != null && latestInput.sequence > maxOf(latestMemory?.sequence ?: 0L, lastMemoryRefreshInput)) {
            lastMemoryRefreshInput = latestInput.sequence
            lastMemoryRefreshPackage = foregroundPackage
            refreshMemoryContext(memoryQuery, foregroundPackage)?.let { payload ->
                eventStore.recordMemoryContextLoaded(durableTurn.scope, payload, latestInput.sequence)
            }
        } else if (foregroundPackage != null && foregroundPackage != lastMemoryRefreshPackage &&
            experienceContextRefreshes < harnessPolicy.memoryContext.experienceRefreshMax) {
            // A newly observed App can match methods its user wording did not. Replacing the
            // memory context resets the provider prefix, so record it only for unseen methods.
            lastMemoryRefreshPackage = foregroundPackage
            val shown = latestMemory?.let { Json.decodeFromString<MemoryContextLoadedEventPayload>(it.payloadJson) }
                ?.items.orEmpty().filter { it.scope == MemoryContextScope.EXPERIENCE }.map { it.ref }.toSet()
            refreshMemoryContext(memoryQuery, foregroundPackage)?.takeIf { payload ->
                payload.items.any { it.scope == MemoryContextScope.EXPERIENCE && it.ref !in shown }
            }?.let { payload ->
                experienceContextRefreshes++
                eventStore.recordMemoryContextLoaded(durableTurn.scope, payload.copy(contextKey = "package:$foregroundPackage"),
                    userInputs.lastOrNull()?.sequence)
            }
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (error: Exception) { debugLogManager.appendEvent("Memory refresh unavailable: ${error.javaClass.simpleName}") }
    val preparationStartedNs = System.nanoTime()
    val toolCallsMap = mutableMapOf<Int, ToolCallBuilder>()
    var fullContent = ""
    var fullReasoning = ""
    suspend fun prepareModelObservation(): ModelRoundOutcome? {
        if (localKnowledge) return null
        if (initialScreenRequired && rounds == 1 && toolExecutor.pendingScreenshotBase64 == null) {
            // Persistent/background runs have already prepared a renderable target frame.
            // Bind the frozen virtual-space attempt, then publish the exact preflight frame into
            // that execution context. Only fall back to a second capture when processing
            // the prepared frame fails.
            ensureDurableExecutionBinding(repairPurpose)
            val preparedObservation = initialScreenFrame
                ?.takeIf(ByteArray::isNotEmpty)
                ?.let { frame ->
                    try {
                        toolExecutor.stagePreparedObservation(frame)
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        debugLogManager.appendEvent(
                            "prepared takeover frame rejected: ${error.message.orEmpty().take(160)}"
                        )
                        null
                    }
                }
            val preparedFrameReady = preparedObservation?.isVerifiedSuccess == true
            if (preparedFrameReady) {
                latestScreenStateForReflector = preparedObservation?.outputText
                groundedTextObservation = preparedObservation?.outputText
                evidenceRecorder.recordObservedState(taskGoal, "observe_screen", checkNotNull(preparedObservation).outputText)
                completionGroundingGate.recordFreshGroundedObservation()
                debugLogManager.appendEvent(
                    "⚡ reused app_takeover preflight frame for the first model round"
                )
            } else {
                freshObservationRequired = true
                forceFreshObservationCapture = false
                debugLogManager.appendEvent(
                    "⚡ prepared frame unavailable; initial screen fallback capture armed"
                )
            }
        }
        // 只等待上一动作已安排的观测，不因为“进入下一轮”就主动读取当前屏幕。
        // 新任务的虚拟 display 可能仍显示上一任务的 App；无条件补帧会把旧页面注入当前目标。
        val pendingObservationReady = if (freshObservationRequired && forceFreshObservationCapture) {
            false
        } else {
            toolExecutor.awaitPendingScreenshot(1500)
        }
        if (freshObservationRequired) {
            freshObservationRequired = false
            if (forceFreshObservationCapture) {
                // 坐标协议失败或计划拒绝后，待消费 envelope 可能仍是失败动作之前的画面。
                // 只在这些恢复路径丢弃旧 envelope；普通状态变化优先复用动作后的 settled frame。
                toolExecutor.invalidatePendingObservation()
                forceFreshObservationCapture = false
            }
            if (!pendingObservationReady && toolExecutor.pendingScreenshotBase64 == null) {
                // 人工接管期间界面可能已变化，恢复后必须先取得同一执行空间的新帧。
                try {
                    ensureExecutionStarted(DeviceToolNames.ANDROID_READ)
                    // This is a synthetic observation call, so its durable call/observation
                    // identity must be unique. Reusing a fixed id makes context projection
                    // bind different screenshots to one observation and abort the turn.
                    val autoResult = executeAuxiliaryObservation(
                        ToolCall(
                            id = "auto_refresh_${java.util.UUID.randomUUID()}",
                            name = DeviceToolNames.ANDROID_READ,
                            arguments = """{"op":"screen"}"""
                        )
                    )
                    totalToolCalls += 1
                    lastExecutionDiagnostic = autoResult.diagnosticCode.takeUnless { autoResult.isVerifiedSuccess }
                    executedToolNames.add(DeviceToolNames.ANDROID_READ)
                    if (autoResult.diagnosticCode == "VIRTUAL_SPACE_EMPTY" &&
                        autoResult.executionStatus == ToolExecutionStatus.FAILED) {
                        retainEmptyDisplayObservation(autoResult)
                        return null
                    }
                    val recoveryHandoff = ruleConfigManager.harnessPolicy().humanAssistance.recoveryHandoffs[autoResult.diagnosticCode]
                    if (recoveryHandoff != null) {
                        onManualTakeoverNeeded(fallbackText(recoveryHandoff))
                        if (!isPaused()) return ModelRoundOutcome.Terminal(groundedGuardStop())
                        waitForActiveExecution()
                        currentObservationLease = null
                        toolExecutor.invalidateModelFrameBinding()
                        toolExecutor.invalidatePendingObservation()
                        freshObservationRequired = true
                        forceFreshObservationCapture = true
                        return ModelRoundOutcome.RestartRound
                    }
                    if (looksLikeRootUnavailable(autoResult)) {
                        groundedTextObservation = null
                        channelUnavailableRepeats += 1
                        messages.add(
                            ChatMessage(
                                role = Role.SYSTEM,
                                content = guardText("channel_unavailable_hint") + "\n" + autoResult.outputText.take(800)
                            )
                        )
                        if (channelUnavailableRepeats >= channelUnavailableStopRepeats) {
                            return ModelRoundOutcome.Terminal(TurnResult(
                                hasToolCalls = true,
                                toolNames = executedToolNames.toList(),
                                summary = guardText("channel_unavailable_stop"),
                                evidenceText = evidenceLedger.buildOperatorEvidenceText(),
                                operatorOutput = finalOutput,
                                rounds = rounds - 1,
                                failureReason = TurnFailureReason.CHANNEL_DOWN,
                                terminal = TurnTerminal.SYSTEM_ERROR
                            ))
                        }
                    } else if (autoResult.isVerifiedSuccess) {
                        emptyDisplayObservation = null
                        latestScreenStateForReflector = autoResult.outputText
                        groundedTextObservation = autoResult.outputText
                        evidenceRecorder.recordObservedState(taskGoal, "observe_screen", autoResult.outputText)
                        completionGroundingGate.recordFreshGroundedObservation()
                        // The recovery observation already published a frame. Consume it
                        // in this same round so the next model action is grounded immediately
                        // instead of spending one extra round on the stale-observation guard.
                        toolExecutor.consumePendingObservation()?.let { observation ->
                            currentObservationLease = observation
                            toolExecutor.bindModelFrame(observation)
                        }
                    } else {
                        groundedTextObservation = null
                    }
                } catch (paused: ManualToolPauseException) {
                    waitForActiveExecution()
                    takeManualResumeInstruction()?.let {
                        resetAfterManualResume(it, "manual pause interrupted observation")
                    }
                    freshObservationRequired = true
                    return ModelRoundOutcome.RestartRound
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Required initial observation is checked below. */ }
            }
            if (currentObservationLease == null && toolExecutor.pendingScreenshotBase64 == null) {
                return ModelRoundOutcome.Terminal(groundedGuardStop())
            }
        } else if (rounds == 1 && toolExecutor.pendingScreenshotBase64 == null) {
            debugLogManager.appendEvent("⚡ first-round auto observe skipped; model chooses entry action")
        }
        return null
    }
    prepareModelObservation()?.let { return it }
    val observationPreparedNs = System.nanoTime()
    val newerObservation = if (localKnowledge) null else toolExecutor.consumePendingObservation()
    if (newerObservation != null) {
        emptyDisplayObservation = null
        currentObservationLease = newerObservation
        completionGroundingGate.recordFreshGroundedObservation()
    }
    val pendingObservation = currentObservationLease
    if (initialScreenRequired && rounds == 1 && pendingObservation == null) {
        return ModelRoundOutcome.Terminal(TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = fallbackText("termination_channel_down"),
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
            operatorOutput = "",
            rounds = 0,
            failureReason = TurnFailureReason.CHANNEL_DOWN,
            terminal = TurnTerminal.SYSTEM_ERROR
        ))
    }
    val pendingScreenshot = pendingObservation?.imageBase64
    val pendingScreenshotMode = pendingObservation?.mode
        ?: com.droidance.core.agent.tools.ObservationModes.NONE
    val pendingFrameScale = pendingObservation?.frameScale ?: 1f
    val pendingFrameSize = pendingObservation?.frameSize
    val pendingPhysicalFrameSize = pendingObservation?.physicalFrameSize
    val pendingFrameOrigin = pendingObservation?.frameOrigin ?: (0 to 0)
    val pendingRotationDegrees = pendingObservation?.rotationDegrees
    if (pendingObservation != null) {
        toolExecutor.bindModelFrame(pendingObservation)
    }
    // Public progress already belongs to durable assistant history. Cumulative
    // source-linked objects below are the working collection; avoid injecting the
    // same narration again as research facts on every decision.
    val reusePostActionObservationThisRound =
        avoidRedundantObservationPromptForNextRound && !reusablePostActionObservation.isNullOrBlank()
    avoidRedundantObservationPromptForNextRound = false
    if (!reusePostActionObservationThisRound) {
        reusablePostActionObservation = null
    } else {
        debugLogManager.appendEvent("⚡ post-action ScreenState attached; prompting model to avoid redundant observation this round")
    }
    val imageEvents = eventStore.eventsForTurn(durableTurn.scope.turnId)
    val finishedForImages = imageEvents.filter { it.eventKind == com.droidance.core.agent.event.AgentEventKind.TOOL_FINISHED }
    val latestFinishedRound = finishedForImages.lastOrNull()?.roundId
    val reviewSourceRefs = CompletionReviewImages.resolve(
        eventStore.eventsForTurn(durableTurn.scope.turnId), durableTurn.scope.threadId,
        durableTurn.scope.turnId, completionReviewGate.pendingSources())
    val imageResults = finishedForImages.map {
        it to Json.decodeFromString<com.droidance.core.agent.event.ToolFinishedEventPayload>(it.payloadJson).result
    }
    val latestResults = imageResults.filter { it.first.roundId == latestFinishedRound }.map { it.second }
    val recentCaptureBytes = imageResults.asReversed().firstNotNullOfOrNull { (_, result) ->
        result.metrics["capture_largest_observed_bytes"]?.takeIf { it > 0 }
    } ?: 0L
    val scrollImages = RetainedReviewSources.scrollHistory(imageEvents)
    // Explicit screenshots form the working set; the capture history limits bound resending.
    val collectionCalls = RetainedReviewSources.collectionCaptureCalls(imageEvents)
    val captureCandidates = imageResults.filter { it.first.roundId != latestFinishedRound && it.first.callId in collectionCalls }
        .flatMap { it.second.batchScreenshots }.map { it.attachment } + scrollImages
    val captureRefs = captureCandidates.mapTo(mutableSetOf()) { it.contentRef }
    val orderedCaptures = imageEvents.filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
        .map { Json.decodeFromString<DetailAttachmentAddedEventPayload>(it.payloadJson) }
        .filter { it.contentRef in captureRefs }
    val captureHistory = RetainedReviewSources.captureHistory(orderedCaptures,
        thresholds.contextCaptureHistoryMaxImages, thresholds.contextCaptureHistoryMaxBytes)
    val captureBudgetContext = "<capture_batch_budget>" + com.droidance.core.agent.tools.ScreenBatchCaptureBudget.nextResponseFacts(
        ruleConfigManager.harnessPolicy().screenActionBatch, recentCaptureBytes) + "</capture_batch_budget>" +
        "\n<capture_history>" + captureHistory.facts() + "</capture_history>"
    // Touch markers help re-localize a tap that had no visible effect; after a delivered
    // change the current frame is the better source and the marker is omitted.
    val explicitHistoricalRefs = latestResults.filter { result ->
        result.executionStatus != ToolExecutionStatus.SUCCEEDED || result.actionEvidence?.transition.let {
            it == null || it == com.droidance.core.agent.event.StateTransitionEvidence.UNCHANGED ||
                it == com.droidance.core.agent.event.StateTransitionEvidence.UNKNOWN
        }
    }.flatMap {
        it.actionFeedback.map { image -> image.contentRef }
    } + latestResults.mapNotNull {
        it.historicalAttachment?.let { image -> image.modelContentRef ?: image.contentRef }
            ?.takeIf { _ -> it.isVerifiedSuccess }
    } + latestResults.mapNotNull {
        it.historicalAttachment?.sourceContentRef?.takeIf { _ -> it.isVerifiedSuccess }
    } + reviewSourceRefs.values.distinct().take(reviewImageBatchLimit)
    val captureImages = (captureHistory.images + latestResults.flatMap { result ->
        result.batchScreenshots.map { it.attachment }
    }).distinctBy { it.contentRef }
    // Automatic history uses the retained model representation. An explicit original
    // read replaces that representation, so one source is never attached twice.
    val captureImageRefs = captureImages.filter {
        it.contentRef !in explicitHistoricalRefs && it.modelContentRef !in explicitHistoricalRefs
    }.mapTo(linkedSetOf()) { it.modelContentRef ?: it.contentRef }
    val captureSourceRefs = imageResults.flatMap { (_, result) -> result.batchScreenshots.flatMap {
        listOfNotNull(it.attachment.contentRef, it.attachment.modelContentRef)
    } }.toSet() + scrollImages.flatMap { listOfNotNull(it.contentRef, it.modelContentRef) }
    val requestedHistoricalRefs = captureImageRefs + explicitHistoricalRefs
    val recentHistoricalRead = requestedHistoricalRefs.isNotEmpty()
    val liveRoundContext = roundContext()
    // Explicit image reads must all be budgeted before compaction and provider assembly.
    val historicalImageLimit = thresholds.contextRecentImageLimit.coerceAtLeast(
        explicitHistoricalRefs.filter { it !in captureSourceRefs }.distinct().size)
    val userSuppliedImages = eventStore.eventsForTurn(durableTurn.scope.turnId).any {
        it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER &&
            Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(it.payloadJson).attachments.isNotEmpty()
    }
    val streamRequestPlan = llmRouter.planStreamChat(
        messages = messages,
        hasImage = pendingScreenshot != null || recentHistoricalRead || userSuppliedImages
    )
    reasoningControl.fixedEffort = streamRequestPlan.reasoningPreference
    // A Responses transport alone does not certify the model's built-in computer tool.
    // Keep the logical screen_act function as the canonical action contract until a
    // provider capability explicitly confirms computer-call support; otherwise some
    // endpoints return computer-style {action: ...} objects that fail the screen_act
    // schema before any device action can run.
    val screenActionBatchEnabled = false
    val canAttachScreenshot = streamRequestPlan.imageIncluded && pendingScreenshot != null
    val runtimeReadableObservation = pendingObservation?.readableText
    val textObservationForRound = PageObservationComposer.composeForModel(
        modelObservations = listOfNotNull(
            groundedTextObservation,
            reusablePostActionObservation.takeIf { reusePostActionObservationThisRound },
            runtimeReadableObservation
        )
    ).takeIf(String::isNotBlank)
    val hasCurrentScreenContext = canAttachScreenshot ||
        reusePostActionObservationThisRound ||
        !textObservationForRound.isNullOrBlank()
    // Register the raw current screen before constructing the request. Its id is visible
    // beside the current frame, while the same reserved detail id is completed after the model
    // responds. A prior screen can therefore never certify a newer terminal screen.
    val detailId = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
    val decisionFrameText = textObservationForRound
        ?: pendingObservation
            ?.takeIf { canAttachScreenshot && !pendingScreenshot.isNullOrBlank() }
            ?.let { observation ->
                "Current Android screen frame: " +
                    "execution_id=${observation.executionId}; " +
                    "backend_session_id=${observation.backendSessionId}; " +
                    "display_id=${observation.displayId}; " +
                    "rotation_degrees=${observation.rotationDegrees}; " +
                    "sequence=${observation.sequence}"
            }
    val decisionFrameObservation = evidenceRecorder.recordDecisionFrame(
        taskGoal = taskGoal,
        rawText = decisionFrameText,
        screenshotRef = detailId,
        observationId = pendingObservation?.observationId.takeIf { canAttachScreenshot },
        frameHash = pendingScreenshot?.takeIf { canAttachScreenshot }?.let { encoded ->
            pendingObservation?.nativeFrameSha256 ?: run {
                val original = pendingObservation?.nativeImagePng ?: checkNotNull(decodeModelFrame(encoded))
                java.security.MessageDigest.getInstance("SHA-256").digest(original)
                    .joinToString("") { "%02x".format(it) }
            }
        },
        executionScope = pendingObservation?.let { "${it.executionId}:${it.backendSessionId}:${it.displayId}" }
    )
    terminalObservationRefVisibleToModel = decisionFrameObservation?.id
    pendingObservation?.let { observation ->
        val fingerprint = observation.nativeVisualFingerprint ?: pendingScreenshot?.let { encoded ->
            runCatching {
                toolExecutor.screenVisualFingerprint(java.util.Base64.getMimeDecoder().decode(encoded))
            }.getOrNull()
        }
        val changeSignature = (observation.nativeImagePng ?: pendingScreenshot?.let(::decodeModelFrame))?.let { bytes ->
            runCatching { toolExecutor.screenChangeSignature(bytes) }.getOrNull()
        }
        observationRecovery.observe(
            sessionIdentity = "${observation.executionId}:${observation.backendSessionId}:${observation.displayId}",
            scope = "${observation.rotationDegrees}:${observation.frameOrigin}:${observation.frameSize}:${observation.frameScale}",
            observationId = decisionFrameObservation?.id ?: observation.observationId,
            fingerprint = fingerprint,
            frameHash = observation.nativeFrameSha256 ?: pendingScreenshot?.let { encoded ->
                decodeModelFrame(encoded)?.let { bytes ->
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                }
            },
            changeSignature = changeSignature
        )
    }
    // Persist the selected source before projecting history and its checkpoint budget.
    // Its first model request must not leave a reference to be backfilled into an
    // already dispatched tool receipt on the following round.
    val currentEvidenceStartedNs = System.nanoTime()
    val decisionFrameBytes = pendingScreenshot
        ?.takeIf { canAttachScreenshot }
        ?.let(::decodeModelFrame)
    decisionFrameObservation?.let { observation ->
        persistPendingPostActionEvidence(
            observationId = observation.id,
            frameBytes = pendingObservation?.nativeImagePng?.takeIf { canAttachScreenshot } ?: decisionFrameBytes,
            frameText = decisionFrameText,
            modelImageBytes = decisionFrameBytes.takeIf { pendingObservation?.nativeImagePng != null }
        )
    }
    val currentEvidenceNanos = System.nanoTime() - currentEvidenceStartedNs
    val turnEventsForContext = eventStore.eventsForTurn(durableTurn.scope.turnId)
    pendingObservation?.let { observation ->
        val images = turnEventsForContext.filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }
            .map { Json.decodeFromString<com.droidance.core.agent.event.DetailAttachmentAddedEventPayload>(it.payloadJson) }
        images.lastOrNull { it.observationId == observation.observationId && it.sourceContentRef == null }?.let { source ->
            observationRecovery.bindCoordinateSources(observation.observationId, source.contentRef, images)
        }
    }
    val focusedRecovery = observationReplanContext.active && !observationReplanContext.protocolRepair
    val finalizationRepair = observationReplanContext.protocolRepair &&
        observationReplanContext.candidateToolName in com.droidance.core.agent.tools.ToolCatalog.finalizationToolNames
    val focusedReview = completionReviewContext.active && !observationReplanContext.active
    val titleRequest = conversationTitles.request(turnEventsForContext,
        eligible = repairPurpose == RepairPurpose.NONE && !focusedReview && !focusedRecovery &&
            !observationReplanContext.protocolRepair)
    val taskTitleRequested = taskTitle == null && repairPurpose == RepairPurpose.NONE &&
        !focusedReview && !focusedRecovery && !observationReplanContext.protocolRepair
    val titleContext = titleRequest.context + "\n<task_title_state>" + buildJsonObject {
        put("requested", JsonPrimitive(taskTitleRequested))
        put("current_title", JsonPrimitive(taskTitle))
    } + "</task_title_state>"
    val requestMemoryText = turnEventsForContext.lastOrNull {
        it.eventKind == AgentEventKind.MEMORY_CONTEXT_LOADED
    }?.let { event ->
        val payload = Json.decodeFromString<com.droidance.core.agent.event.MemoryContextLoadedEventPayload>(event.payloadJson)
        com.droidance.core.agent.memory.MemoryContextPromptRenderer.render(payload, harnessPolicy.memoryContext.turnIndexMaxChars)
    }.orEmpty()
    // Route targets and inventory queries come from explicit tool arguments,
    // independently. A label search is not the name of a route target.
    val explicitAppArguments = turnEventsForContext.asReversed().asSequence()
        .filter { it.eventKind == AgentEventKind.TOOL_CALL }
        .map { Json.decodeFromString<ToolCallEventPayload>(it.payloadJson) }
        .filter { it.source == com.droidance.core.agent.event.ToolCallSource.MODEL_REQUESTED &&
            it.name in setOf(DeviceToolNames.ANDROID_ACT, DeviceToolNames.ANDROID_READ) }
        .mapNotNull { call -> runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }
            .getOrNull()?.let { call.name to it } }.toList()
    val capabilityTarget = explicitAppArguments.firstOrNull { (name, args) ->
        !(name == DeviceToolNames.ANDROID_READ &&
            (args["op"] as? JsonPrimitive)?.contentOrNull == "apps") &&
            listOf("package_name", "app_name").any {
            !(args[it] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
        }
    }?.second
    val inventoryTarget = explicitAppArguments.firstOrNull { (name, args) ->
        name == DeviceToolNames.ANDROID_READ &&
            (args["op"] as? JsonPrimitive)?.contentOrNull == "apps" ||
            listOf("package_name", "app_name").any {
                !(args[it] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
            }
    }?.second
    val inventoryPackageName = (inventoryTarget?.get("package_name") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val inventoryQuery = (inventoryTarget?.get(
        if ((inventoryTarget?.get("op") as? JsonPrimitive)?.contentOrNull == "apps") "query" else "app_name"
    ) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val capabilityContextLimit = Json.parseToJsonElement(
        ruleConfigManager.readMarkdownSection("device_tools.md", "capability_context_policy")
    ).jsonObject.getValue("maxChars").jsonPrimitive.int.also { require(it in 1024..32768) }
    val rawCapabilityContext = run {
        try {
            toolExecutor.capabilityContext(
                (capabilityTarget?.get("package_name") as? JsonPrimitive)?.contentOrNull,
                (capabilityTarget?.get("app_name") as? JsonPrimitive)?.contentOrNull,
                inventoryPackageName,
                inventoryQuery,
                capabilityContextLimit)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) {
            debugLogManager.appendEvent("Capability context unavailable: ${failure.javaClass.simpleName}")
            "{\"source_kind\":\"local_capability_catalog\",\"status\":\"unavailable\"}"
        }
    }
    // The index is decision context for choosing an entry route. Once delivered unchanged,
    // repeating it every round only grows the request; launch and apps reads resolve names.
    val capabilityContext = if (rawCapabilityContext == previousCapabilityContext) "" else rawCapabilityContext
    previousCapabilityContext = rawCapabilityContext
    val activeWorkspaceContext = workspaceContextReader.activeContext(
        turnEventsForContext, durableTurn.scope, workspaceContextPolicy.maxActiveChars)
    val pendingCompletionFacts = completionReviewGate.candidateTool()?.let { tool ->
        CompletionSubmissionProtocol.currentCandidateFacts(workspaceContextReader, durableTurn.scope, tool)
    }
    modelRecordRepairs = com.droidance.core.agent.event.ModelRecordRepairs.project(
        turnEventsForContext, durableTurn.scope.threadId, durableTurn.scope.turnId)
    val workspaceBrowsingFacts = WorkspaceBrowsingProgress.project(
        turnEventsForContext, durableTurn.scope, workspaceContextPolicy)
    val executionProgressForRound = TaskExecutionProgress.project(turnEventsForContext, durableTurn.scope, recoveryPolicy.refreshAfterDiagnostics)
    // Only an exhausted turn resource budget restricts mutations here. Model-authored
    // plans, reviews and working-record checkpoints never grant execution authority.
    val executionMutationBlock = executionProgressForRound.mutationBlockCode(taskExecutionPolicy)
    val restrictMutations = !takeoverManaged && executionMutationBlock != null
    // Results delivered with report_result are already visible to the user. Naming them in
    // the decision tail keeps the completion from restating them.
    val deliveredResultHeadings = turnEventsForContext.filter { it.eventKind == AgentEventKind.TOOL_FINISHED }
        .mapNotNull { it.callId }.toSet().let { settled ->
            turnEventsForContext.filter { it.eventKind == AgentEventKind.TOOL_CALL && it.callId in settled }
                .map { Json.decodeFromString<ToolCallEventPayload>(it.payloadJson) }
                .filter { it.name == com.droidance.core.agent.tools.ReportResultTool.NAME }
                .mapNotNull { call -> runCatching {
                    (Json.parseToJsonElement(call.argumentsJson).jsonObject["text"] as? JsonPrimitive)?.contentOrNull
                }.getOrNull()?.lineSequence()?.map { it.trim().trimStart('#', ' ') }?.firstOrNull(String::isNotBlank)?.take(120) }
        }
    // Reasoning facts accompany the effort hint only when this Turn's frozen tools offer it.
    val reasoningHintsOffered = tools.any { ReasoningEffortProtocol.ARGUMENT in it.parameters }
    val taskWorkspaceContext = listOfNotNull(
        reasoningControl.context().takeIf { reasoningHintsOffered },
        previousReasoningMetadata.filterKeys { it in setOf("sent_reasoning_effort", "reasoning_fallback_reason", "supported_reasoning_efforts") }
            .takeIf { it.isNotEmpty() && reasoningHintsOffered }?.let { "<previous_reasoning_transport>${Json.encodeToString(it)}</previous_reasoning_transport>" },
        captureBudgetContext,
        deliveredResultHeadings.takeIf { it.isNotEmpty() }?.let { headings ->
            "<delivered_results>" + Json.encodeToString(headings) + "\n" +
                ruleConfigManager.readMarkdownSection("agent_core.md", "delivered_results_context") + "</delivered_results>"
        },
        if (modelRecordRepairs.pending.isNotEmpty()) ruleConfigManager.readMarkdownSection(
            "workspace.md", "record_repair") + "\n" + buildJsonObject {
                put("repair_suggested", JsonPrimitive(modelRecordRepairs.active(modelRecordRepairPolicy.maxRepairRounds)))
                put("rounds_used", JsonPrimitive(modelRecordRepairs.roundsSinceRejection))
                put("max_rounds", JsonPrimitive(modelRecordRepairPolicy.maxRepairRounds))
            }.toString() else null,
        workingRecordInitializationFailure?.takeIf { activeWorkspaceContext == null }?.let {
            "<working_record_initialization>\n$it\n" +
                ruleConfigManager.readMarkdownSection("workspace.md", "initialization_unavailable") +
                "\n</working_record_initialization>"
        },
        pendingCompletionFacts?.let { "<pending_completion_candidate>\n$it\n</pending_completion_candidate>" },
        capabilityContext.takeIf(String::isNotBlank)?.let {
            ruleConfigManager.readMarkdownSection("device_tools.md", "capability_context") +
                "\n<available_capability_index>\n$it\n</available_capability_index>"
        },
        executionProgressForRound.render(),
        observationRecovery.failedMethodHistory().takeIf { it.isNotEmpty() }?.let {
            "<failed_delivery_methods>\n$it\n</failed_delivery_methods>"
        },
        observationRecovery.navigationFacts().takeIf { it.isNotEmpty() }?.let {
            "<navigation_repetition>\n$it\n" +
                ruleConfigManager.readMarkdownSection("observation_recovery.md", "navigation_facts") +
                "\n</navigation_repetition>"
        },
        if (executionProgressForRound.reviewDue(taskExecutionPolicy))
            ruleConfigManager.readMarkdownSection("workspace.md", "execution_review_advisory") else null,
        if (workspaceBrowsingFacts.reviewDue(workspaceContextPolicy))
            ruleConfigManager.readMarkdownSection("workspace.md", "browsing_review") else null,
        executionMutationBlock?.takeIf { restrictMutations }?.let { code ->
            "<execution_checkpoint>" + buildJsonObject {
                put("code", JsonPrimitive(code))
                put("instruction", JsonPrimitive(ruleConfigManager.readMarkdownSection("workspace.md", "execution_checkpoint")))
            } + "</execution_checkpoint>"
        },
        workspaceBrowsingFacts.takeIf { it.browseCallsSinceRecordChange > 0 || it.deviceCallsSinceRecordChange > 0 }?.let {
            "<workspace_browsing_facts>\n${it.facts()}\n</workspace_browsing_facts>"
        }).joinToString("\n")
    // Without a checkpoint or a focused projection, every settled call and receipt is already
    // in the request history; the tail then carries only state that history does not hold.
    val settledHistoryRetained = !focusedReview && !focusedRecovery && !finalizationRepair &&
        eventStore.latestContextCheckpointForTurn(durableTurn.scope.turnId) == null
    val taskExecutionFacts = listOf(com.droidance.core.agent.event.TaskExecutionFacts.render(
        turnEventsForContext,
        durableTurn.scope.threadId,
        durableTurn.scope.turnId,
        recentLimit = ruleConfigManager.readMarkdownSection("model_context.md", "recent_tool_feedback_limit")
            .trim().toInt().also { require(it > 0) },
        historyRetained = settledHistoryRetained
    ), com.droidance.core.agent.event.TaskContinuationContext.render(
        turnEventsForContext, durableTurn.scope.threadId, durableTurn.scope.turnId,
        includeModelAuthoredMemo = !observationReplanContext.active || observationReplanContext.protocolRepair,
        historyRetained = settledHistoryRetained
    ), com.droidance.core.agent.workspace.TaskWorkspace.overview(
        turnEventsForContext, durableTurn.scope, MAX_TASK_NOTE_CHARS
    )).filter(String::isNotBlank).joinToString("\n")
    val currentTaskNote = TaskWorkingState(
        checkpoint = "",
        latestClosedObservationId = terminalObservationRefVisibleToModel,
        semanticContextKey = null,
        verifiedEvidence = "",
        recentActions = emptyList(),
        blockedActions = blockedActionNarrative.toList()
    ).render(maxChars = MAX_TASK_NOTE_CHARS)
    val workspaceContent = activeWorkspaceContext?.takeIf { it.totalChars > 0 }?.render().orEmpty()
    val modelRoundWorkingMemory = listOf(taskExecutionFacts, currentTaskNote, taskWorkspaceContext, workspaceContent)
        .filter(String::isNotBlank)
        .joinToString("\n")
    // Runtime state changes facts and execution permissions, never the frozen tool schema.
    val toolsForRound = tools
    val presentedToolNames = toolsForRound.mapTo(linkedSetOf(), ToolDefinition::name)
    if (pendingScreenshot != null && !canAttachScreenshot && !visionUnavailableNotified) {
        visionUnavailableNotified = true
        debugLogManager.appendEvent("vision_off_screenshots_not_sent")
    }
    val systemPrompt = durableTurn.stablePrefix.systemPrompt
    // A pending computer call always needs the current frame, even if its pixels are unchanged.
    val requestImageMode = if (canAttachScreenshot) pendingScreenshotMode
        else com.droidance.core.agent.tools.ObservationModes.NONE
    val freshScreenStateForRound = textObservationForRound
    var contextCapacityText = ""
    val requirementsEvents = eventStore.eventsForTurn(durableTurn.scope.turnId)
    val taskRequirements = com.droidance.core.agent.event.TaskRequirementPlan.modelContext(
        requirementsEvents, durableTurn.scope.threadId, durableTurn.scope.turnId
    )
    val reviewSourceIndex = if (completionReviewContext.active || observationReplanContext.active)
        com.droidance.core.agent.event.SessionEvidenceProjector.sourceIndex(
            requirementsEvents,
            durableTurn.scope.threadId, durableTurn.scope.turnId, limit = 32, includeTextContent = false,
            preferredRefs = completionReviewGate.candidateSourceRefs(), requester = durableTurn.scope
        ).toString() else ""
    // Source projections discard execution history and summaries. Budget their real
    // request below; ordinary decisions retain the durable checkpoint lifecycle.
    val durableContextDecision = if (focusedReview || focusedRecovery) null else runCatching {
        checkpointContext(ContextRoundInput(
            workingMemory = modelRoundWorkingMemory,
            screenState = freshScreenStateForRound,
            hasAttachedImage = canAttachScreenshot,
            requiredImageCount = requestedHistoricalRefs.distinct().size,
            additionalContext = liveRoundContext + messages.filter { it.role == Role.SYSTEM }.joinToString("\n") { it.content } +
                requestMemoryText + reviewSourceIndex + taskRequirements + titleContext,
            systemPrompt = systemPrompt,
            tools = toolsForRound
        )) { capacity ->
            contextCapacityText = capacity.render()
            debugLogManager.appendEvent(contextCapacityText)
        }
    }.onFailure { error ->
        if (error is kotlinx.coroutines.CancellationException) {
            if (coroutineContext.isActive && isPaused()) {
                waitForActiveExecution()
                takeManualResumeInstruction()?.let {
                    resetAfterManualResume(it, "manual pause interrupted context compaction")
                }
                return ModelRoundOutcome.RestartRound
            }
            throw error
        }
        debugLogManager.appendEvent(
            "context checkpoint failed closed: ${error::class.simpleName}: ${error.message?.take(240)}"
        )
    }.getOrElse { error ->
        val imageInputRejected = error.providerHttpException()?.category in setOf(
            ProviderErrorCategory.IMAGE_UNSUPPORTED,
            ProviderErrorCategory.IMAGE_INPUT_UNAVAILABLE
        )
        if (error is com.droidance.core.agent.context.ContextCompactionUnavailable && !imageInputRejected) {
            val output = fallbackText("termination_context_compaction_failed")
            return ModelRoundOutcome.Terminal(TurnResult(
                hasToolCalls = totalToolCalls > 0,
                toolNames = executedToolNames.toList(),
                summary = output,
                evidenceText = evidenceLedger.buildOperatorEvidenceText(),
                operatorOutput = output,
                rounds = rounds - 1,
                failureReason = TurnFailureReason.CONTEXT_COMPACTION_FAILED,
                terminal = TurnTerminal.NEED_MORE_INFO
            ))
        }
        return ModelRoundOutcome.Terminal(TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = if (imageInputRejected) fallbackText("termination_model_image_unavailable") else guardText(
                "context_checkpoint_failed",
                mapOf("error" to error::class.simpleName.orEmpty())
            ),
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
            operatorOutput = finalOutput,
            rounds = rounds - 1,
            runtimeFailureSummary = fallbackText("termination_model_image_unavailable").takeIf { imageInputRejected },
            failureReason = if (imageInputRejected) TurnFailureReason.MODEL_UNAVAILABLE else TurnFailureReason.RUNTIME_EXCEPTION,
            terminal = TurnTerminal.SYSTEM_ERROR
        ))
    }
    if (durableContextDecision is ContextCheckpointDecision.Unchanged &&
        durableContextDecision.reason == com.droidance.core.agent.context.ContextUnchangedReason.COMPACTION_DEFERRED
    ) {
        debugLogManager.appendEvent("context compaction deferred; original history retained below hard input limit")
    }
    if (durableContextDecision is ContextCheckpointDecision.CheckpointReady) {
        requestHistory.reset()
        // A Responses previous_response_id would otherwise retain the compacted
        // provider-side history. Reset that transport epoch so the next request is
        // rebuilt from the same bounded EventStore projection as generic chat.
        llmRouter.resetResponsesSessionAfterContextCheckpoint()
        debugLogManager.appendEvent(
            "durable context checkpoint committed: " +
                "source=${durableContextDecision.payload.sourceThroughSequence} " +
                "retained=${durableContextDecision.payload.retainedUnits.size} " +
                "dropped=${durableContextDecision.payload.droppedUnits.size}"
        )
    }
    if (durableContextDecision is ContextCheckpointDecision.ContextLimitExceeded) {
        stoppedReplyContextAvailable = false
        debugLogManager.appendEvent(
            "durable context limit exceeded: ${durableContextDecision.reason}"
        )
        return ModelRoundOutcome.Terminal(TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = guardText("context_limit_exceeded"),
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
            operatorOutput = finalOutput,
            rounds = rounds - 1,
            failureReason = TurnFailureReason.MODEL_PROTOCOL_ERROR,
            terminal = TurnTerminal.SYSTEM_ERROR
        ))
    }
    val parentCheckpoint = eventStore.latestContextCheckpointForTurn(durableTurn.scope.turnId)
    val durableProjection = DurableContextProjector.project(
        events = eventStore.eventsForTurn(durableTurn.scope.turnId),
        parentCheckpoint = parentCheckpoint
    )
    val retainedImagesStartedNs = System.nanoTime()
    val historicalImages = if (durableProjection != null && streamRequestPlan.imageIncluded) {
        withContext(Dispatchers.IO) {
            HistoricalObservationImages(blobStore).load(
                durableProjection, historicalImageLimit, pendingScreenshot,
                requestedHistoricalRefs.filter { ref -> ref !in captureSourceRefs && durableProjection.observations.any {
                    (it.contentRef == ref || it.modelContentRef == ref) &&
                        it.mode == com.droidance.core.agent.context.ObservationProjectionMode.IMAGE
                } }.toSet(), deduplicateCurrentImage = canAttachScreenshot, excludedContentRefs = captureSourceRefs
            )
        }
    } else HistoricalObservationImages.Loaded(emptyMap(), emptyMap())
    val activeLoadedRefs = historicalImages.imageContentRefs.values.toSet() +
        historicalImages.currentImageRefs
    val restoredReviewSources = if (streamRequestPlan.imageIncluded) withContext(Dispatchers.IO) {
        RetainedReviewSources.load(eventStore.eventsForTurn(durableTurn.scope.turnId), durableTurn.scope,
            requestedHistoricalRefs.toSet() - activeLoadedRefs, blobStore,
            currentImage = pendingScreenshot.takeIf { canAttachScreenshot })
    } else RetainedReviewSources.Loaded(emptyList(), emptySet(),
        requestedHistoricalRefs.associateWith { "MODEL_IMAGE_UNSUPPORTED" })
    val retainedImagesNanos = System.nanoTime() - retainedImagesStartedNs
    val inputEvents = eventStore.eventsForTurn(durableTurn.scope.turnId)
    val lastInputBoundary = inputEvents.lastOrNull {
        it.eventKind == com.droidance.core.agent.event.AgentEventKind.MODEL_ROUND_STARTED
    }?.sequence ?: 0L
    val steerEvents = inputEvents.filter { it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER }
    val representedInputs = durableProjection?.units.orEmpty().flatMap { it.items }
        .filterIsInstance<com.droidance.core.agent.context.UserInputAgentItem>().mapTo(mutableSetOf()) { it.itemId }
    val retainedUserImages = steerEvents.filter { it.eventId in representedInputs }.associate { event ->
        val input = Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(event.payloadJson)
        event.eventId to input.attachments.map {
            blobStore.readBase64(it.contentRef, 8 * 1024 * 1024)
        }
    }
    val projectedMemoryMessages: List<ChatMessage>
    if (durableProjection != null) {
        val rebuilt = DurableContextMessageProjector.build(
            projection = durableProjection,
            firstTaskPrompt = stepPrompt,
            imagesByObservationSequence = historicalImages.images,
            imageContentRefsByObservationSequence = historicalImages.imageContentRefs,
            imagesByUserItemId = retainedUserImages,
            memoryContextText = requestMemoryText
        )
        // The durable projector owns memory SYSTEM messages. Keep them request-local;
        // only runtime directives belong to the mutable loop history.
        projectedMemoryMessages = rebuilt.filter { it.role == Role.SYSTEM }
        val runtimeDirectives = messages.filter { it.role == Role.SYSTEM }
        messages.clear()
        messages.addAll(rebuilt.filterNot { it.role == Role.SYSTEM })
        messages.addAll(runtimeDirectives)
        if (historicalImages.failures.isNotEmpty() || historicalImages.requiredFailures.isNotEmpty()) messages.add(ChatMessage(
            role = Role.USER,
            content = "historical_image_load_failures=" + historicalImages.failures.toString() +
                " required_reference_failures=" + historicalImages.requiredFailures.toString()
        ))
        debugLogManager.appendEvent(
            "durable context rebuilt: units=${durableProjection.units.size} " +
                "messages=${rebuilt.size} runtime_directives=${runtimeDirectives.size}"
        )
    } else {
        // Before the first closed round, only the committed memory event is available.
        projectedMemoryMessages = requestMemoryText.takeIf(String::isNotBlank)
            ?.let { text -> listOf(ChatMessage(Role.SYSTEM, text)) }.orEmpty()
    }
    messages.addAll(restoredReviewSources.messages)
    if (restoredReviewSources.failures.isNotEmpty()) messages.add(ChatMessage(Role.USER,
        "required_source_load_failures=${restoredReviewSources.failures}. " +
            ruleConfigManager.readMarkdownSection("completion_recovery.md", "source_unavailable")))
    steerEvents.filter { it.eventId !in representedInputs && it.sequence > lastInputBoundary }.forEach { event ->
        val input = Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(event.payloadJson)
        messages.add(ChatMessage(Role.USER, input.text, imageBase64List = input.attachments.map {
            blobStore.readBase64(it.contentRef, 8 * 1024 * 1024)
        }))
    }
    val modelHistoryForRound = if (focusedReview) completionReviewContext.project(
        originalRequest = taskGoal,
        userSteers = inputEvents
            .filter { it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER }
            .map { Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(it.payloadJson).text },
        history = messages,
        instruction = ruleConfigManager.readMarkdownSection("completion_recovery.md", "focused_source_review"),
        priorConversation = additionalContext,
        sourceIndex = reviewSourceIndex,
        taskRequirements = taskRequirements
    ) else if (focusedRecovery) observationReplanContext.project(
        originalRequest = taskGoal,
        userSteers = inputEvents
            .filter { it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER }
            .map { Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(it.payloadJson).text },
        history = messages,
        instruction = observationRecoveryDirective?.content
            ?: if (recordRecoveryPending) ruleConfigManager.readMarkdownSection("workspace.md", "record_recovery")
            else observationReplanInstruction(),
        priorConversation = additionalContext,
        sourceIndex = reviewSourceIndex,
        taskRequirements = taskRequirements
    ).let { recoveryHistory ->
        // Method recovery still needs the frozen Skill catalog and environment.
        // Earlier model assertions stay outside this focused source projection.
        listOfNotNull(turnContext.takeIf(String::isNotBlank)?.let { ChatMessage(Role.SYSTEM, it) }) +
            recoveryHistory
    } else if (finalizationRepair) observationReplanContext.project(
        originalRequest = taskGoal, userSteers = emptyList(), history = messages.filterNot { it === observationRecoveryDirective } +
            listOfNotNull(taskRequirements.takeIf(String::isNotBlank)?.let {
                ChatMessage(Role.USER, buildJsonObject { put("model_derived_task_requirements", JsonPrimitive(it)) }.toString())
            }),
        instruction = ruleConfigManager.readMarkdownSection("utility_prompts.md", "protocol_repair")
    ) else if (observationReplanContext.protocolRepair) messages.filterNot { it === observationRecoveryDirective }
    else messages
    // An ordinary call's field repair keeps the cacheable history and appends its exact
    // rejection; only completion repairs withhold earlier completion proposals.
    val fieldRepairFeedback = if (observationReplanContext.protocolRepair && !finalizationRepair)
        observationReplanContext.repairFeedback(ruleConfigManager.readMarkdownSection("utility_prompts.md", "protocol_repair"))
        else null
    val isolatedHistory = focusedReview || focusedRecovery || finalizationRepair
    val cacheableHistory = if (isolatedHistory) {
        requestHistory.reset()
        projectedMemoryMessages + modelHistoryForRound.filterNot { it.historicalImage }
    } else requestHistory.prepare(
        durable = modelHistoryForRound.filter { it.role != Role.SYSTEM && !it.historicalImage },
        directives = modelHistoryForRound.filter { it.role == Role.SYSTEM },
        memory = projectedMemoryMessages.singleOrNull()
    )
    var messagesForLLM = modelContextAssembler.buildMessagesForModel(
        messages = cacheableHistory.map { it.copy(cacheablePrefix = !isolatedHistory) } + modelHistoryForRound.filter { it.historicalImage } +
            listOfNotNull(taskRequirements.takeIf(String::isNotBlank)?.let {
                ChatMessage(Role.USER, buildJsonObject { put("model_derived_task_requirements", JsonPrimitive(it)) }.toString())
            }) + listOfNotNull(
            fieldRepairFeedback,
            emptyDisplayObservation?.let { result -> ChatMessage(Role.SYSTEM,
                ruleConfigManager.readMarkdownSection("observation_recovery.md", "empty_display") +
                    "\n" + result.outputText)
            }
        ),
        taskGoal = taskGoal,
        userUpdates = steerEvents.map { Json.decodeFromString<com.droidance.core.agent.event.UserTextEventPayload>(it.payloadJson).text },
        imageBase64 = pendingScreenshot.takeIf { canAttachScreenshot },
        imageBase64List = emptyList(),
        imageMode = requestImageMode,
        frameSize = pendingFrameSize.takeIf { canAttachScreenshot },
        frameOrigin = pendingFrameOrigin.takeIf { canAttachScreenshot } ?: (0 to 0),
        freshScreenState = freshScreenStateForRound.takeUnless { focusedReview },
        taskWorkingMemory = null,
        // Ordinary decisions and method recovery retain bounded, source-linked
        // findings without requiring a file write. Candidate review uses originals.
        taskResearchNote = null,
        rawObservationId = decisionFrameObservation?.id,
        taskExecutionFacts = listOf(taskExecutionFacts.takeUnless { focusedReview }.orEmpty(), liveRoundContext)
            .filter(String::isNotBlank).joinToString("\n"),
        taskWorkspaceContext = listOf(taskWorkspaceContext, workspaceContent).filter(String::isNotBlank).joinToString("\n"),
        screenUnchanged = false
    ) + ChatMessage(Role.SYSTEM, titleContext)
    run {
        fun capacity(request: List<ChatMessage>) = durableContextCheckpointCoordinator.requestCapacity(
            activeTurn = durableTurn, systemPrompt = systemPrompt, tools = toolsForRound,
            messages = request, providerContextWindowTokens = llmRouter.currentContextWindowTokens(),
            compressionTriggerPercent = thresholds.contextBudgetPercent,
            recentScreenshotLimit = thresholds.contextRecentImageLimit, maxOutputTokens = modelOutputBudget)
        val sourceRequest = messagesForLLM
        var actualCapacity = capacity(sourceRequest)
        // Include the capacity footer itself; repeated estimation converges once its
        // decimal token counts stop changing length. No request content is truncated.
        do {
            contextCapacityText = actualCapacity.render()
            messagesForLLM = sourceRequest + ChatMessage(Role.USER, contextCapacityText)
            val next = capacity(messagesForLLM)
            val changed = next != actualCapacity
            actualCapacity = next
        } while (changed)
        debugLogManager.appendEvent(contextCapacityText)
        if (actualCapacity.estimatedInputTokens > actualCapacity.usableInputTokens) {
            stoppedReplyContextAvailable = false
            debugLogManager.appendEvent(if (focusedReview || focusedRecovery)
                "isolated source request exceeds hard input capacity" else "model request exceeds hard input capacity")
            return ModelRoundOutcome.Terminal(TurnResult(
                hasToolCalls = totalToolCalls > 0, toolNames = executedToolNames.toList(),
                summary = guardText("context_limit_exceeded"),
                evidenceText = evidenceLedger.buildOperatorEvidenceText(), operatorOutput = finalOutput,
                rounds = rounds - 1, failureReason = TurnFailureReason.MODEL_PROTOCOL_ERROR,
                terminal = TurnTerminal.SYSTEM_ERROR))
        }
    }
    val observationPreparationNanos = observationPreparedNs - preparationStartedNs
    val loadedReviewRefs = activeLoadedRefs + restoredReviewSources.refs
    val deliveredReviewSources = reviewSourceRefs.filterValues { it in loadedReviewRefs }.keys +
        setOfNotNull(decisionFrameObservation?.id.takeIf { decisionFrameBytes != null })
    val prefixFacts = if (isolatedHistory) mapOf(
        "local_prefix_reused" to "false", "local_prefix_reused_messages" to "0",
        "local_prefix_reset_reason" to "isolated_source_projection", "duplicate_directives_removed" to "0"
    ) else requestHistory.preparationFacts()
    val observationInputMetadata = prefixFacts + mapOf(
        "completion_review_candidate_id" to completionReviewGate.candidateId().orEmpty(),
        "completion_review_result_ids" to completionReviewGate.reviewedResultIds().joinToString(","),
        "completion_review_context" to if (focusedReview) "original_sources" else "execution_history",
        "execution_mutation_block" to executionMutationBlock.orEmpty(),
        "device_mutations_allowed" to (!restrictMutations).toString(),
        "source_context_isolated" to (isolatedHistory || restrictMutations).toString(),
        "protocol_repair_active" to observationReplanContext.protocolRepair.toString(),
        "observation_replan_context" to if (focusedRecovery) "original_sources" else "execution_history",
        "capability_context_chars" to capabilityContext.length.toString(),
        // Stable prefix size facts: provider-independent JSON schema text, not provider tokens.
        "system_prompt_chars" to systemPrompt.length.toString(),
        "tool_schema_chars" to toolsForRound.sumOf { tool ->
            tool.name.length + tool.description.length + buildJsonObject {
                tool.parameters.forEach { (name, parameter) -> put(name, parameter.toJsonSchema()) }
            }.toString().length
        }.toString(),
        "tool_schema_chars_by_name" to toolsForRound.joinToString(",") { tool ->
            tool.name + ":" + (tool.description.length + buildJsonObject {
                tool.parameters.forEach { (name, parameter) -> put(name, parameter.toJsonSchema()) }
            }.toString().length)
        },
        "capability_context_target" to listOf("package_name", "app_name").mapNotNull { key ->
            (capabilityTarget?.get(key) as? JsonPrimitive)?.contentOrNull
        }.joinToString("|"),
        "inventory_context_package" to inventoryPackageName,
        "inventory_context_query" to inventoryQuery,
        "active_workspace_path" to activeWorkspaceContext?.path.orEmpty(),
        "active_workspace_content_ref" to activeWorkspaceContext?.contentRef.orEmpty(),
        "active_workspace_delivered_chars" to (activeWorkspaceContext?.text?.length ?: 0).toString(),
        "active_workspace_has_more" to (activeWorkspaceContext?.hasMore ?: false).toString(),
        "browse_calls_since_record_change" to workspaceBrowsingFacts.browseCallsSinceRecordChange.toString(),
        "device_calls_since_record_change" to workspaceBrowsingFacts.deviceCallsSinceRecordChange.toString(),
        "completion_review_source_ids" to deliveredReviewSources
            .intersect(completionReviewGate.pendingSources()).joinToString(","),
        "assembled_history_text_bytes" to messagesForLLM.sumOf {
            it.content.toByteArray(Charsets.UTF_8).size +
                it.toolCalls.orEmpty().sumOf { call -> call.arguments.toByteArray(Charsets.UTF_8).size }
        }.toString(),
        "assembled_image_base64_bytes" to messagesForLLM.sumOf {
            it.imageBase64List.ifEmpty { listOfNotNull(it.imageBase64) }.sumOf(String::length)
        }.toString(),
        "historical_image_sequences" to historicalImages.images.keys.joinToString(","),
        "historical_image_failures" to historicalImages.failures.entries.joinToString(",") { "${it.key}:${it.value}" },
        "historical_required_image_failures" to (historicalImages.requiredFailures + restoredReviewSources.failures).entries.joinToString(",") { "${it.key}:${it.value}" },
        "model_input_prepared_at_ms" to System.currentTimeMillis().toString(),
        "current_observation_id" to decisionFrameObservation?.id.orEmpty(),
        "current_observation_diagnostic" to emptyDisplayObservation?.diagnosticCode.orEmpty(),
        "current_observation_execution" to pendingObservation?.executionId.orEmpty(),
        "current_observation_display" to pendingObservation?.displayId?.toString().orEmpty(),
        "current_observation_captured_elapsed_ms" to pendingObservation?.capturedAtElapsedMs?.toString().orEmpty(),
        "current_frame_produced_at_ms" to pendingObservation?.producedAtMs?.toString().orEmpty(),
        "current_frame_age_ms" to pendingObservation?.producedAtMs?.let {
            (System.currentTimeMillis() - it).coerceAtLeast(0).toString()
        }.orEmpty(),
        "current_observation_age_ms" to pendingObservation?.let {
            (runtimeInfo.elapsedRealtimeMs() - it.capturedAtElapsedMs).toString()
        }.orEmpty(),
        "native_source_sha256" to pendingObservation?.nativeImagePng?.let { bytes ->
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }.orEmpty(),
        "current_image_sha256" to pendingScreenshot?.let { encoded ->
            imageDigestCache.sha256(encoded)
        }.orEmpty(),
        "assembled_image_sha256" to messagesForLLM.flatMap {
            it.imageBase64List.ifEmpty { listOfNotNull(it.imageBase64) }
        }.joinToString(",") { encoded ->
            imageDigestCache.sha256(encoded)
        },
        "assembled_image_count" to messagesForLLM.sumOf { it.imageBase64List.ifEmpty { listOfNotNull(it.imageBase64) }.size }.toString()
    ) + run {
        // Disjoint preparation wall-time slices, including metadata digest calculation.
        // Context includes checkpoint/compaction; manual-pause waiting below is excluded.
        val preparationNanos = System.nanoTime() - preparationStartedNs
        mapOf(
            "preparation_observation_ms" to (observationPreparationNanos / 1_000_000).toString(),
            "preparation_retained_images_ms" to (retainedImagesNanos / 1_000_000).toString(),
            "preparation_current_evidence_ms" to (currentEvidenceNanos / 1_000_000).toString(),
            "preparation_context_other_ms" to ((preparationNanos - observationPreparationNanos -
                retainedImagesNanos - currentEvidenceNanos).coerceAtLeast(0) / 1_000_000).toString()
        )
    }

    val requestCancellationGeneration = llmRouter.currentCancellationGeneration()
    waitForActiveExecution()
    coroutineContext.ensureActive()
    val manualResumeInstructionBeforeRequest = takeManualResumeInstruction()
    if (manualResumeInstructionBeforeRequest != null) {
        resetAfterManualResume(
            manualResumeInstructionBeforeRequest,
            "manual takeover resumed before request registration; reset loop guards and forcing fresh observation"
        )
        return ModelRoundOutcome.RestartRound
    }
    stateFlow?.value = AgentState.THINKING

    requestMetrics = emptyMap()
    modelRecoveryBudget.start()
    val llmStarted = System.currentTimeMillis()
    var firstEventAt: Long = 0L
    val roundTimeoutMs = modelRecoveryBudget.requestTimeoutMs(RoundTimeoutBudgetPolicy.bound(
        requestedTimeoutMs = RoundTimeoutBudgetPolicy.forOutputBudget(
            baseTimeoutMs = thresholds.llmRoundTimeoutMs,
            baseTokens = thresholds.llmActionMaxOutputTokens,
            requestedTokens = modelOutputBudget
        ),
        taskWallMs = maxDeviceTaskWallMs,
        activeElapsedMs = activeElapsedMs(),
        finalReply = false
    ))
    if (maxDeviceTaskWallMs > 0L && activeElapsedMs() >= maxDeviceTaskWallMs) {
        finalOutput = finalOutput.ifBlank { wallStopMessage(activeElapsedMs()) }
        return ModelRoundOutcome.Terminal(finalReplyProtocolFailure(failureReason = TurnFailureReason.TASK_TIME_LIMIT))
    }
    if (roundTimeoutMs <= 0L) {
        settlePendingDurableRound("LLM_RECOVERY_BUDGET_EXHAUSTED")
        return ModelRoundOutcome.Terminal(modelTimeoutTerminal(""))
    }
    // Record the model-round boundary before the Provider request, but defer backend
    // preparation until the response actually selects a DEVICE_BOUND tool.
    beginDurableRound(repairPurpose)
    if (!isolatedHistory) requestHistory.recordSent(cacheableHistory)
    var reviewRequestCompleted = false
    val streamProgress = OperatorStreamProgress()
    try {
        val imageBytes = pendingScreenshot?.takeIf { canAttachScreenshot }?.length ?: 0
        debugLogManager.appendEvent("LLM(operator) call started: hasImage=$canAttachScreenshot, imageBase64Chars=${imageBytes}, msgCount=${messagesForLLM.size}, toolDefs=${toolsForRound.size}")
        // The exact schema text behind tool_schema_chars, once per turn, for prefix reviews.
        if (rounds == 1) debugLogManager.appendEvent("operator tool schemas: " + buildJsonObject {
            toolsForRound.forEach { tool -> put(tool.name, buildJsonObject {
                put("description", JsonPrimitive(tool.description))
                tool.parameters.forEach { (name, parameter) -> put(name, parameter.toJsonSchema()) }
            }) }
        })
        val streamCapture = operatorStreamCollector.collect(
            diagnosticScope = checkNotNull(roundSession.pending).scope.let {
                com.droidance.core.model.llm.ModelDiagnosticScope(it.threadId, it.turnId, it.roundId)
            },
            requestPlan = streamRequestPlan,
            reasoningEffort = reasoningControl.effortFor(focusedRound = isolatedHistory),
            messages = messagesForLLM,
            tools = toolsForRound,
            systemPrompt = systemPrompt,
            hasImage = messagesForLLM.any { !it.imageBase64.isNullOrBlank() || it.imageBase64List.isNotEmpty() },
            timeoutMs = roundTimeoutMs,
            deadlines = ModelStreamDeadlinePolicy(thresholds.llmFirstEventTimeoutMs,
                thresholds.llmStreamIdleTimeoutMs, thresholds.llmFirstOutputTimeoutMs),
            cancellationGeneration = requestCancellationGeneration,
            maxOutputTokens = modelOutputBudget,
            enableComputerTool = screenActionBatchEnabled && !restrictMutations,
            computerFrameSize = pendingFrameSize,
            isolatedContext = focusedReview || focusedRecovery || finalizationRepair || restrictMutations,
            startedAt = llmStarted,
            onMetrics = { requestMetrics = it },
            // Buffer operator prose until tool selection is known. A completion
            // candidate must not stream as an accepted answer before its guards run.
            onContent = {},
            progress = streamProgress
        )
        reviewRequestCompleted = true
        fullContent = streamCapture.content
        fullReasoning = streamCapture.reasoning
        toolCallsMap.putAll(streamCapture.toolCalls)
        firstEventAt = streamCapture.firstEventAt
        // Keep the task's expanded budget after a successful response.
    } catch (e: TimeoutCancellationException) {
        if (isPaused() && coroutineContext.isActive) {
            waitForActiveExecution()
            takeManualResumeInstruction()?.let { instruction ->
                settlePendingDurableRound("USER_STEER")
                resetAfterManualResume(instruction, "model timeout arrived during manual control")
                return ModelRoundOutcome.RestartRound
            }
        }
        firstEventAt = streamProgress.firstEventAt
        // No tool executes before this request completes. A truncated stream is
        // transport evidence, never an actionable call or a completion candidate.
        fullContent = ""
        fullReasoning = streamProgress.reasoning
        requestMetrics = requestMetrics + mapOf(
            "discarded_partial_tool_calls" to streamProgress.toolCalls.size.toString(),
            "discarded_partial_content_chars" to streamProgress.content.length.toString()
        )
        if (maxDeviceTaskWallMs > 0 &&
            activeElapsedMs() >= maxDeviceTaskWallMs
        ) {
            settlePendingDurableRound("MAX_DEVICE_TASK_WALL_STOP")
            return ModelRoundOutcome.Terminal(finalReplyProtocolFailure(fullContent, TurnFailureReason.TASK_TIME_LIMIT))
        }
        val firstByteMs = if (firstEventAt > 0) firstEventAt - llmStarted else -1
        val phase = when {
            streamProgress.firstReasoningAt > 0L && streamProgress.firstContentAt == 0L && streamProgress.firstToolAt == 0L -> "provider reasoning without actionable output"
            firstEventAt == 0L -> "no first byte received"
            streamProgress.toolCalls.isEmpty() && streamProgress.content.isBlank() -> "first byte arrived but no content/tool_call followed"
            else -> "partial content received"
        }
        debugLogManager.appendEvent("⚠️ Operator LLM timeout: ${roundTimeoutMs}ms image=$canAttachScreenshot, phase=「$phase」, firstByte=${firstByteMs}ms, discardedToolCalls=${streamProgress.toolCalls.size}, discardedContentLen=${streamProgress.content.length}")
        if (retryModelRequest("LLM_TIMEOUT_RETRY", e)) return ModelRoundOutcome.RestartRound
        settlePendingDurableRound("LLM_TIMEOUT")
        return ModelRoundOutcome.Terminal(modelTimeoutTerminal(fullContent))
    } catch (e: Exception) {
        firstEventAt = streamProgress.firstEventAt
        fullContent = streamProgress.content
        fullReasoning = streamProgress.reasoning
        toolCallsMap.putAll(streamProgress.toolCalls)
        val requestWasInterrupted =
            e is kotlinx.coroutines.CancellationException ||
                llmRouter.wasCancellationRequestedSince(requestCancellationGeneration)
        if (requestWasInterrupted) {
            if (isPaused() && coroutineContext.isActive) waitForActiveExecution()
            val steerInstruction = takeManualResumeInstruction()
            if (steerInstruction != null && coroutineContext.isActive) {
                settlePendingDurableRound("USER_STEER")
        resetAfterManualResume(
                    steerInstruction,
                    "user steer interrupted active model request; restarting with fresh observation"
                )
                return ModelRoundOutcome.RestartRound
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw kotlinx.coroutines.CancellationException("model request cancelled").also {
                it.initCause(e)
            }
        }
        waitForActiveExecution()
        val manualResumeInstruction = takeManualResumeInstruction()
        if (manualResumeInstruction != null) {
            settlePendingDurableRound("MANUAL_RESUME")
        resetAfterManualResume(
                manualResumeInstruction,
                "manual takeover resumed after model response ended; reset loop guards and forcing fresh observation"
            )
            return ModelRoundOutcome.RestartRound
        }
        debugLogManager.appendEvent("❌ Operator LLM exception: ${e::class.java.simpleName}: ${e.message?.take(2000)}")
        val transportTimeout = isLlmTransportTimeout(e)
        // 详细打印发往 LLM 的 messages 结构（截短），方便定位 400
        runCatching {
            val structure = messagesForLLM.joinToString(" → ") { m ->
                val tcCount = m.toolCalls?.size ?: 0
                val tcId = if (tcCount > 0) "/tc=${tcCount}" else ""
                val tcrId = if (!m.toolCallId.isNullOrBlank()) "/id=${m.toolCallId!!.take(8)}" else ""
                "${m.role}${tcId}${tcrId}"
            }
            debugLogManager.appendEvent("messages before failure: $structure")
        }
        // No tool executes until collection and normalization finish. A failed stream's
        // fragments are not committed actions, including an almost-complete done call.
        // Discard them before bounded retry, just as for coroutine request timeouts.
        requestMetrics = requestMetrics + mapOf(
            "discarded_partial_tool_calls" to toolCallsMap.size.toString(),
            "discarded_partial_content_chars" to fullContent.length.toString()
        )
        fullContent = ""
        fullReasoning = ""
        toolCallsMap.clear()
        val nothingConsumed = toolCallsMap.isEmpty() && fullContent.isBlank()
        val actionableOutput = toolCallsMap.isNotEmpty() || fullContent.isNotBlank()
        if (transportTimeout && !actionableOutput) {
            if (e is ModelStreamTimeoutException && e.phase == "first_output") {
                settlePendingDurableRound("LLM_NO_ACTIONABLE_OUTPUT")
                return ModelRoundOutcome.Terminal(modelTimeoutTerminal(fullContent))
            }
            if (retryModelRequest("TRANSPORT_TIMEOUT_RETRY", e)) return ModelRoundOutcome.RestartRound
            settlePendingDurableRound("TRANSPORT_TIMEOUT")
            return ModelRoundOutcome.Terminal(modelTimeoutTerminal(fullContent))
        }
        val providerFailure = e.providerHttpException()
        val imageInputRejected = streamRequestPlan.imageIncluded &&
            providerFailure?.category in setOf(
                ProviderErrorCategory.IMAGE_UNSUPPORTED,
                ProviderErrorCategory.IMAGE_INPUT_UNAVAILABLE
            )
        val permanentError = providerFailure?.let { failure ->
            imageInputRejected || failure.category in setOf(
                ProviderErrorCategory.AUTHENTICATION,
                ProviderErrorCategory.PERMISSION_DENIED,
                ProviderErrorCategory.CONTEXT_LIMIT,
                ProviderErrorCategory.REQUEST_REJECTED
            ) || (failure.statusCode in 400..499 &&
                !ModelRoundRetryPolicy.isTransientFailure(failure) &&
                failure.category != ProviderErrorCategory.IMAGE_UNSUPPORTED)
        } == true
        val incomplete = e as? com.droidance.core.model.llm.OpenAIResponsesIncompleteException
        val failureClass = when {
            incomplete?.outputLimitReached == true -> "MODEL_OUTPUT_TRUNCATED"
            incomplete != null || e is com.droidance.core.model.llm.OpenAIResponsesUnterminatedException ||
                e is kotlinx.serialization.SerializationException -> "MODEL_RESPONSE_FORMAT"
            providerFailure != null -> "PROVIDER_${providerFailure.category.name}"
            generateSequence(e as Throwable?) { it.cause }.take(8).any { it is java.io.IOException } -> "NETWORK"
            else -> "LOCAL_RUNTIME"
        }
        if (failureClass == "MODEL_OUTPUT_TRUNCATED") modelOutputTruncations++
        val truncationRetriesExhausted = failureClass == "MODEL_OUTPUT_TRUNCATED" &&
            modelOutputTruncations > thresholds.llmOutputTruncationRetries
        if (!truncationRetriesExhausted && ModelRoundRetryPolicy.shouldRetry(
                hasActionableOutput = !nothingConsumed,
                permanentFailure = permanentError || failureClass == "LOCAL_RUNTIME",
                retriesUsed = llmTransientRetries,
                retryLimit = llmTransientRetryLimit
            )
        ) {
            modelOutputBudget = (e as? com.droidance.core.model.llm.OpenAIResponsesIncompleteException)
                ?.expandedOutputBudget(modelOutputBudget, thresholds.llmRetryMaxOutputTokens)
                ?: modelOutputBudget
            if (retryModelRequest("${failureClass}_RETRY", e)) return ModelRoundOutcome.RestartRound
        }
        settlePendingDurableRound("LLM_EXCEPTION")
        return ModelRoundOutcome.Terminal(TurnResult(
            hasToolCalls = totalToolCalls > 0,
            toolNames = executedToolNames.toList(),
            summary = "LLM call failed: ${e.message?.take(800)}",
            runtimeFailureSummary = when {
                imageInputRejected -> fallbackText("termination_model_image_unavailable")
                providerFailure?.category == ProviderErrorCategory.PERMISSION_DENIED ->
                    providerFailure.publicReason?.let { reason ->
                        fallbackText("termination_model_permission_denied_reason", mapOf("reason" to reason))
                    } ?: fallbackText("termination_model_permission_denied")
                else -> fallbackText(modelFailureNoticeKey(e))
            },
            evidenceText = evidenceLedger.buildOperatorEvidenceText(),
            operatorOutput = fullContent,
            rounds = rounds,
            failureReason = TurnFailureReason.MODEL_UNAVAILABLE,
            stopDiagnostic = providerFailure?.let { "PROVIDER_${it.category.name}_HTTP_${it.statusCode}" } ?: failureClass,
            terminal = TurnTerminal.SYSTEM_ERROR
        ))
    }
    // A late Provider response can arrive after the user takes control. Hold every
    // response, including zero-tool completion, until explicit resume or cancellation.
    waitForActiveExecution()
    coroutineContext.ensureActive()
    takeManualResumeInstruction()?.let { instruction ->
        settlePendingDurableRound("USER_CONTEXT_CHANGED")
        resetAfterManualResume(instruction, "User context changed during model request; discarded unexecuted response")
        return ModelRoundOutcome.RestartRound
    }
    if (reviewRequestCompleted && focusedReview) {
        val deliveredSources = deliveredReviewSources.associateWith { source ->
            reviewSourceRefs[source] ?: checkNotNull(decisionFrameBytes).let { bytes ->
                "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            }
        }
        completionRejectionCycleGuard.recordReviewedSources(deliveredSources, completionReviewGate.pendingSources())
        completionReviewGate.recordDeliveredSources(deliveredSources)
    }
    return ModelRoundOutcome.Ready(
        ModelRoundOutput(
            toolCalls = toolCallsMap,
            content = fullContent,
            reasoning = fullReasoning,
            pendingScreenshot = pendingScreenshot,
            nativeFrameBytes = pendingObservation?.nativeImagePng?.takeIf { canAttachScreenshot },
            sourceObservationId = decisionFrameObservation?.id,
            pendingFrameSize = pendingFrameSize,
            pendingPhysicalFrameSize = pendingPhysicalFrameSize,
            canAttachScreenshot = canAttachScreenshot,
            presentedToolNames = presentedToolNames,
            inputMetadata = observationInputMetadata + requestMetrics + mapOf(
                "runtime_build_version" to runtimeInfo.buildVersion
            ),
            textObservation = textObservationForRound,
            detailId = detailId,
            reusePostActionObservation = reusePostActionObservationThisRound,
            durableRound = checkNotNull(roundSession.pending),
            titleRequest = titleRequest,
            taskTitleRequested = taskTitleRequested,
            mutationsRestricted = restrictMutations
        )
    )
    }
