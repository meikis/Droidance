// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.*
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * LLM 路由器（重构版）
 * 直接从 ProviderConfigManager 获取动态配置，
 * 使用 OpenAI 兼容格式发起 HTTP 调用
 */
class LLMRouter(
    private val httpClient: OkHttpClient,
    private val configManager: TransportConfiguration,
    private val tokenUsageRepository: TransportUsageRecorder,
    private val diagnostics: TransportDiagnostics
) : ModelClient {
    private companion object {
        const val FINAL_USAGE_GRACE_MS = 1_000L
    }

    /** 模型切换事件回调（供 UI 监听，显示切换通知） */
    private val switchEvents = kotlinx.coroutines.flow.MutableSharedFlow<ModelSwitchEvent>(extraBufferCapacity = 16)
    override val modelSwitches: kotlinx.coroutines.flow.SharedFlow<ModelSwitchEvent> = switchEvents

    private val streamingHttpClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val imageDetailDiscoveryLocks = ConcurrentHashMap<String, Mutex>()
    private val cancellationGeneration = AtomicLong(0L)
    private val activeSessionRef = java.util.concurrent.atomic.AtomicReference<ModelSession?>()
    private val sessionLock = Any()

    override fun createSession(initialPlan: ModelStreamPlan?): ModelSession = synchronized(sessionLock) {
        val session = object : ModelSession {
            private var closed = false

            private fun <T> withActiveSession(block: () -> T): T = synchronized(sessionLock) {
                check(!closed && activeSessionRef.get() === this) { "ModelSession is closed" }
                block()
            }

            override fun planStreamChat(messages: List<ChatMessage>, hasImage: Boolean): ModelStreamPlan =
                withActiveSession { this@LLMRouter.planStreamChat(messages, hasImage) }

            override suspend fun streamChat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>,
                systemPrompt: String?,
                hasImage: Boolean,
                enableComputerTool: Boolean,
                computerFrameSize: Pair<Int, Int>?,
                maxOutputTokens: Int?,
                requestTimeoutMs: Long?,
                requestPlan: ModelStreamPlan?,
                isolatedContext: Boolean,
                reasoningEffort: ReasoningEffort
            ): Flow<LLMStreamEvent> {
                val (generation, plan) = withActiveSession {
                    cancellationGeneration.get() to (requestPlan ?: this@LLMRouter.planStreamChat(messages, hasImage))
                }
                return flow {
                    withActiveSession { Unit }
                    this@LLMRouter.streamChat(
                        messages = messages,
                        tools = tools,
                        systemPrompt = systemPrompt,
                        hasImage = hasImage,
                        enableComputerTool = enableComputerTool,
                        computerFrameSize = computerFrameSize,
                        maxOutputTokens = maxOutputTokens,
                        requestTimeoutMs = requestTimeoutMs,
                        requestCancellationGeneration = generation,
                        requestPlan = plan,
                        isolatedContext = isolatedContext,
                        reasoningEffort = reasoningEffort
                    ).collect { emit(it) }
                }
            }

            override fun cancelActiveRequests() {
                synchronized(sessionLock) {
                    if (!closed && activeSessionRef.get() === this) this@LLMRouter.cancelActiveRequests()
                }
            }

            override fun resetResponsesSessionAfterCheckpoint() =
                withActiveSession { this@LLMRouter.resetResponsesSessionAfterContextCheckpoint() }

            override fun resetResponsesSessionForAndroidActivation() =
                withActiveSession { this@LLMRouter.resetResponsesSessionForAndroidActivation() }

            override fun currentCancellationGeneration(): Long =
                withActiveSession { cancellationGeneration.get() }

            override fun wasCancellationRequestedSince(generation: Long): Boolean = synchronized(sessionLock) {
                closed || activeSessionRef.get() !== this || this@LLMRouter.wasCancellationRequestedSince(generation)
            }

            override fun reportImageSendRejected(reason: String) =
                withActiveSession { this@LLMRouter.reportImageSendRejected(reason) }

            override fun canSendVisionMessages(): Boolean =
                withActiveSession { this@LLMRouter.canSendVisionMessages() }

            override fun currentContextWindowTokens(): Int =
                withActiveSession { this@LLMRouter.currentContextWindowTokens() }

            override fun close() {
                synchronized(sessionLock) {
                    if (closed) return
                    closed = true
                    this@LLMRouter.cancelActiveRequests()
                    this@LLMRouter.endTurn()
                    activeSessionRef.compareAndSet(this, null)
                }
            }
        }

        check(activeSessionRef.compareAndSet(null, session)) {
            "A model session is already active. Concurrent or overlapping sessions are prohibited."
        }
        beginTurn()
        synchronized(turnPlanLock) {
            if (initialPlan != null) frozenTurnPlan = initialPlan
        }
        session
    }

    // Current provider config used for the ongoing stream
    private var currentProviderConfig: ProviderConfig? = null
    private var currentProviderModel: String? = null
    private val sessionImageRejected = Collections.synchronizedSet(mutableSetOf<String>())
    private var imageCapabilityEpoch = 0L
    private val turnPlanLock = Any()
    private var turnPlanDepth = 0
    private var frozenTurnPlan: ModelStreamPlan? = null
    private var responsesTurnState = OpenAIResponsesTurnState()
    private var responsesStatefulContinuationDisabled = false
    private val unretainedResponsesScopes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Scopes whose provider rejected the gateway's extra Responses fields. */
    private val requestFieldsRejectedScopes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Freeze provider/model selection for one user turn, including all recovery rounds. */
    override fun beginTurn() {
        synchronized(turnPlanLock) {
            turnPlanDepth += 1
            if (turnPlanDepth == 1) {
                imageCapabilityEpoch++
                sessionImageRejected.clear()
                frozenTurnPlan = null
                responsesTurnState = OpenAIResponsesTurnState()
                responsesStatefulContinuationDisabled = false
            }
        }
    }

    override fun endTurn() {
        synchronized(turnPlanLock) {
            turnPlanDepth = (turnPlanDepth - 1).coerceAtLeast(0)
            if (turnPlanDepth == 0) {
                imageCapabilityEpoch++
                sessionImageRejected.clear()
                frozenTurnPlan = null
                responsesTurnState = OpenAIResponsesTurnState()
                responsesStatefulContinuationDisabled = false
            }
        }
    }

    /**
     * Start a fresh Responses conversation after durable context compaction. A
     * previous_response_id keeps the provider-side history alive even when the local
     * EventStore projection has dropped old rounds; retaining it would therefore defeat
     * the checkpoint budget and make long tasks progressively slower.
     */
    override fun resetResponsesSessionAfterContextCheckpoint() {
        synchronized(turnPlanLock) {
            if (turnPlanDepth > 0) {
                responsesTurnState = OpenAIResponsesTurnState()
            }
        }
    }

    /**
     * Some Responses-compatible endpoints return an id but do not retain it for the next call.
     * Once that contract violation is observed, keep this turn stateless and send the bounded
     * local projection instead of retrying the same unusable previous_response_id.
     */
    private fun disableResponsesStatefulContinuation() {
        synchronized(turnPlanLock) {
            responsesStatefulContinuationDisabled = true
            responsesTurnState = OpenAIResponsesTurnState()
        }
    }

    /** The zero-tool phase and Android phase have different immutable prefixes. */
    override fun resetResponsesSessionForAndroidActivation() {
        synchronized(turnPlanLock) {
            if (turnPlanDepth > 0) responsesTurnState = OpenAIResponsesTurnState()
        }
    }

    private fun isTurnPlanFrozen(): Boolean = synchronized(turnPlanLock) { turnPlanDepth > 0 }

    override fun cancelActiveRequests() {
        val snapshot = synchronized(activeCalls) {
            cancellationGeneration.incrementAndGet()
            activeCalls.toList()
        }
        snapshot.forEach { call -> runCatching { call.cancel() } }
    }

    override fun currentCancellationGeneration(): Long = cancellationGeneration.get()

    override fun wasCancellationRequestedSince(generation: Long): Boolean =
        cancellationGeneration.get() != generation

    private fun registerActiveCall(call: Call, expectedCancellationGeneration: Long?): Boolean {
        return synchronized(activeCalls) {
            if (
                expectedCancellationGeneration != null &&
                cancellationGeneration.get() != expectedCancellationGeneration
            ) {
                false
            } else {
                activeCalls.add(call)
                true
            }
        }
    }

    override fun explicitReasoningEfforts(): Set<ReasoningEffort> {
        val frozen = synchronized(turnPlanLock) { frozenTurnPlan }
        val config = frozen?.providerConfig ?: configManager.resolveProvider() ?: return emptySet()
        // A user-fixed provider setting is never changed by the Agent.
        if (config.reasoningPreference != null) return emptySet()
        val model = frozen?.initialModel ?: configManager.getCurrentModel(config)
        val capability = runCatching { configManager.reasoningCapability(config, model) }
            .getOrDefault(ReasoningCapability())
        if (capability.forceDefault) return emptySet()
        return capability.efforts.filterTo(linkedSetOf()) { it != ReasoningEffort.DEFAULT }
    }

    override fun canSendVisionMessages(): Boolean {
        synchronized(turnPlanLock) {
            frozenTurnPlan?.let { plan ->
                return plan.providerConfig?.let { isVisionEnabled(it, plan.initialModel) } ?: plan.imageIncluded
            }
        }
        val config = configManager.resolveProvider() ?: return false
        return isVisionEnabled(config, configManager.getCurrentModel(config))
    }

    /** 当前操作 Provider 的上下文窗口（token），供自动压缩按 60% 阈值判断。 */
    override fun currentContextWindowTokens(): Int {
        synchronized(turnPlanLock) {
            frozenTurnPlan?.contextWindowTokens?.let { return it.coerceAtLeast(8_000) }
        }
        val config = configManager.resolveProvider()
        return (config?.contextWindowTokens ?: 128_000).coerceAtLeast(8_000)
    }

    /** An explicit capability rejection disables vision only within the current turn. */
    private fun isVisionEnabled(config: ProviderConfig, model: String): Boolean =
        !sessionImageRejected.contains(visionKey(config.id, model))

    /** Record an explicit capability rejection for the active turn without changing configuration. */
    override fun reportImageSendRejected(reason: String) {
        val config = currentProviderConfig
            ?: configManager.resolveProvider()
            ?: return
        val model = currentProviderModel ?: configManager.getCurrentModel(config)
        if (model.isBlank()) return
        sessionImageRejected.add(visionKey(config.id, model))
        diagnostics.warning("DroidanceLLMRouter", "vision demoted for ${config.name}/$model: ${reason.take(200)}")
    }

    private fun visionKey(providerId: String, model: String): String = "$providerId::$model"

    private fun detectImageMimeType(base64: String): String = when {
        base64.startsWith("iVBOR") -> "image/png"
        base64.startsWith("/9j/") -> "image/jpeg"
        base64.startsWith("UklGR") -> "image/webp"
        else -> "image/png"
    }

    /**
     * 流式聊天（支持同 Provider 内模型自动降级）
     * 当流式调用失败且错误为额度/模型不可用时，自动切换到下一个模型重试
     * 降级逻辑在 Flow 内部完成（因为 SSE 错误在 collect 时才触发）
     */
    override suspend fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        hasImage: Boolean,
        enableComputerTool: Boolean,
        computerFrameSize: Pair<Int, Int>?,
        maxOutputTokens: Int?,
        requestTimeoutMs: Long?,
        requestCancellationGeneration: Long?,
        requestPlan: ModelStreamPlan?,
        isolatedContext: Boolean,
        reasoningEffort: ReasoningEffort
    ): Flow<LLMStreamEvent> {
        val plan = requestPlan ?: planStreamChat(messages, hasImage)
        val config = plan.providerConfig ?: configManager.resolveProvider()
            ?: throw ModelNotConfiguredException("No AI provider is available; configure one in settings")
        val requestedEffort = config.reasoningPreference ?: reasoningEffort

        // remember the current provider for tool_choice construction
        currentProviderConfig = config
        currentProviderModel = plan.initialModel
        val requestImageCapabilityEpoch = synchronized(turnPlanLock) { imageCapabilityEpoch }

        return flow {
            var currentModel = plan.initialModel
            var lastError: Exception? = null
            var transientRetriesLeft = 1
            var responsesStatefulRetryUsed = false
            var defaultReasoningRetryUsed = false
            var requestFieldsRetryUsed = false
            val allowModelFallback = !isTurnPlanFrozen()
            var imageDetailCandidates = if (plan.imageIncluded) {
                configManager.imageDetailCandidates(config, currentModel)
            } else {
                listOf(ImageDetailOption.SERVICE_DEFAULT)
            }
            val historyScope = "${plan.providerId}:${plan.authScopeHash}:${plan.transportConfigHash}:$currentModel"
            var imageDetailIndex = 0
            val discoveryLock = plan.imageIncluded &&
                configManager.imageDetailCapabilityStatus(config, currentModel).needsDetection
            val discoveryMutex = if (discoveryLock) {
                imageDetailDiscoveryLocks.computeIfAbsent(
                    "${plan.providerId}:${plan.authScopeHash}:${plan.transportConfigHash}:$currentModel"
                ) { Mutex() }
            } else null
            discoveryMutex?.lock()
            try {
            // Another task may have completed discovery while this request waited.
            if (discoveryLock) {
                imageDetailCandidates = configManager.imageDetailCandidates(config, currentModel)
            }
            while (true) {
                val supportsVision = plan.imageIncluded
                val imageDetail = imageDetailCandidates.getOrElse(imageDetailIndex) {
                    ImageDetailOption.SERVICE_DEFAULT
                }
                val transport = config.resolvedTransportOrNull()
                    ?: throw IllegalStateException(
                        "Provider ${config.name} has no confirmed transport adapter"
                    )
                var emittedAny = false
                var emittedToolCall = false
                var isolatedResponsesBase: OpenAIResponsesTurnState? = null
                try {
                    currentProviderConfig = config
                    currentProviderModel = currentModel
                    val capability = runCatching { configManager.reasoningCapability(config, currentModel) }
                        .getOrDefault(ReasoningCapability())
                    val useDefaultReasoning = defaultReasoningRetryUsed || capability.forceDefault
                    val effectiveEffort = if (useDefaultReasoning) ReasoningEffort.DEFAULT else capability.resolve(requestedEffort)
                    emit(LLMStreamEvent.ReasoningConfiguration(
                        requested = requestedEffort,
                        sent = effectiveEffort,
                        fallbackReason = when {
                            defaultReasoningRetryUsed -> "provider_rejected"
                            capability.forceDefault && requestedEffort != ReasoningEffort.DEFAULT -> "cached_provider_rejection"
                            effectiveEffort != requestedEffort -> "unsupported_or_unknown_capability"
                            else -> null
                        },
                        supportedEfforts = capability.options
                    ))
                    val stream = when (transport) {
                        ModelTransport.GENERIC_CHAT_COMPLETIONS -> streamOpenAICompatible(
                            endpointUrl = ProviderEndpoint.resolve(config.baseUrl, transport),
                            apiKey = config.apiKey,
                            model = currentModel,
                            messages = messages,
                            tools = tools,
                            systemPrompt = systemPrompt,
                            providerName = config.name,
                            supportsVision = supportsVision,
                            imageDetail = imageDetail,
                            maxOutputTokens = maxOutputTokens,
                            requestTimeoutMs = requestTimeoutMs,
                            requestCancellationGeneration = requestCancellationGeneration,
                            reasoningEffort = effectiveEffort,
                            reasoningParameter = capability.parameter,
                            forceDefaultReasoning = useDefaultReasoning
                        )
                        ModelTransport.OPENAI_RESPONSES -> streamOpenAIResponses(
                            endpointUrl = ProviderEndpoint.resolve(config.baseUrl, transport),
                            apiKey = config.apiKey,
                            model = currentModel,
                            messages = messages,
                            tools = tools,
                            systemPrompt = systemPrompt,
                            providerName = config.name,
                            supportsVision = supportsVision,
                            imageDetail = imageDetail,
                            maxOutputTokens = maxOutputTokens,
                            requestTimeoutMs = requestTimeoutMs,
                            requestCancellationGeneration = requestCancellationGeneration,
                            state = synchronized(turnPlanLock) {
                                if (isolatedContext) {
                                    isolatedResponsesBase = responsesTurnState
                                    OpenAIResponsesTurnState()
                                } else if (responsesStatefulContinuationDisabled || historyScope in unretainedResponsesScopes) {
                                    OpenAIResponsesTurnState()
                                } else {
                                    responsesTurnState
                                }
                            },
                            includeComputerTool = enableComputerTool,
                            computerFrameSize = computerFrameSize,
                            reasoningEffort = effectiveEffort,
                            forceDefaultReasoning = useDefaultReasoning,
                            extraFields = if (historyScope in requestFieldsRejectedScopes) ProviderRequestFields.EMPTY
                                else runCatching { configManager.responsesRequestFields(config) }.getOrDefault(ProviderRequestFields.EMPTY)
                        )
                        else -> throw IllegalStateException(
                            "Transport ${transport.name} is configured but has no HTTP implementation"
                        )
                    }
                    var returnedModel: String? = null
                    stream.collect { event ->
                        // Local request facts precede the HTTP exchange and must not block a retry.
                        if (event !is LLMStreamEvent.RequestConfiguration) emittedAny = true
                        if (event is LLMStreamEvent.ToolCallChunk) emittedToolCall = true
                        if (event is LLMStreamEvent.ResponseMetadata && !event.returnedModel.isNullOrBlank()) {
                            returnedModel = event.returnedModel
                        }
                        emit(event)
                    }
                    // Completed isolated calls are absent from the normal provider chain. The
                    // next request must rebuild declarations from local history, even if a
                    // buffered consumer later discards this response. Preserve newer state.
                    if (emittedToolCall && isolatedResponsesBase != null) {
                        synchronized(turnPlanLock) {
                            if (responsesTurnState === isolatedResponsesBase) {
                                responsesTurnState = OpenAIResponsesTurnState()
                            }
                        }
                    }
                    if (supportsVision) {
                        configManager.recordImageDetailAccepted(
                            config, currentModel, imageDetail, returnedModel
                        )
                    }
                    // 流完整结束，调用成功，重置降级状态
                    configManager.resetModelFallback(config.id)
                    return@flow
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    if (emittedAny || e is kotlinx.coroutines.CancellationException || e is RequestCancelledBeforeRegistrationException) throw e
                    lastError = e
                    val errMsg = e.message ?: ""
                    val requestFields = runCatching { configManager.responsesRequestFields(config) }
                        .getOrDefault(ProviderRequestFields.EMPTY)
                    if (!requestFieldsRetryUsed && transport == ModelTransport.OPENAI_RESPONSES &&
                        historyScope !in requestFieldsRejectedScopes && ProviderRequestFields.rejectedBy(e, requestFields)) {
                        requestFieldsRetryUsed = true
                        requestFieldsRejectedScopes.add(historyScope)
                        diagnostics.warning("DroidanceLLMRouter", "Gateway request fields rejected; retrying without them")
                        continue
                    }
                    if (!defaultReasoningRetryUsed &&
                        e.providerHttpException()?.category == ProviderErrorCategory.REASONING_UNSUPPORTED) {
                        defaultReasoningRetryUsed = true
                        runCatching { configManager.recordReasoningRejected(config, currentModel) }
                        diagnostics.warning("DroidanceLLMRouter", "Reasoning configuration rejected; retrying once with model defaults")
                        continue
                    }
                    if (supportsVision && isImageDetailUnsupportedError(e)) {
                        configManager.recordImageDetailRejected(config, currentModel, imageDetail)
                        if (imageDetailIndex + 1 < imageDetailCandidates.size) {
                            imageDetailIndex++
                            diagnostics.warning(
                                "DroidanceLLMRouter",
                                "image detail ${imageDetail.name} rejected; retrying with " +
                                    imageDetailCandidates[imageDetailIndex].name
                            )
                            continue
                        }
                    }
                    if (
                        transport == ModelTransport.OPENAI_RESPONSES &&
                        !responsesStatefulRetryUsed &&
                        isPreviousResponseNotFound(e)
                    ) {
                        responsesStatefulRetryUsed = true
                        disableResponsesStatefulContinuation()
                        unretainedResponsesScopes.add(historyScope)
                        diagnostics.warning(
                            "DroidanceLLMRouter",
                            "Responses previous_response_id was not retained; retrying this turn stateless"
                        )
                        continue
                    }
                    if (markVisionUnsupportedIfApplicable(config, currentModel, supportsVision, e,
                            requestImageCapabilityEpoch) && allowModelFallback) {
                        val oldModel = currentModel
                        val nextModel = advanceToNextImageCandidate(config)
                        if (nextModel != null) {
                            currentModel = nextModel
                            imageDetailCandidates = configManager.imageDetailCandidates(config, currentModel)
                            imageDetailIndex = 0
                            switchEvents.emit(ModelSwitchEvent(oldModel, nextModel, "Image input is unsupported"))
                            continue
                        }
                    }
                    if (shouldFallbackModel(e) && allowModelFallback) {
                        val oldModel = currentModel
                        val nextModel = if (plan.imageIncluded) advanceToNextImageCandidate(config)
                        else configManager.advanceToNextModel(config)
                        if (nextModel != null) {
                            currentModel = nextModel
                            imageDetailCandidates = if (plan.imageIncluded) {
                                configManager.imageDetailCandidates(config, currentModel)
                            } else {
                                listOf(ImageDetailOption.SERVICE_DEFAULT)
                            }
                            imageDetailIndex = 0
                            switchEvents.emit(ModelSwitchEvent(oldModel, nextModel, errMsg.take(80)))
                            continue // 用下一个模型重试
                        }
                    }
                    val boundedRequestTimeout = requestTimeoutMs != null &&
                        e is java.io.InterruptedIOException
                    if (shouldRetryTransientStreamError(e) &&
                        transientRetriesLeft > 0 &&
                        !boundedRequestTimeout
                    ) {
                        transientRetriesLeft--
                        delay(250)
                        continue
                    }
                    // 不是额度错误 或 所有模型都试完了
                    throw lastError
                }
            }
            } finally {
                discoveryMutex?.unlock()
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun planStreamChat(
        messages: List<ChatMessage>,
        hasImage: Boolean
    ): ModelStreamPlan {
        synchronized(turnPlanLock) {
            frozenTurnPlan?.let { frozen ->
                val cfg = frozen.providerConfig ?: configManager.resolveProvider()
                return frozen.copy(
                    // Provider and model remain frozen, while image transport can be decided
                    // per round from the same model's known capability.
                    imageIncluded = hasImage && cfg != null && isVisionEnabled(cfg, frozen.initialModel)
                )
            }
        }
        val config = configManager.resolveProvider()
            ?: throw ModelNotConfiguredException("No AI provider is available; configure one in settings")
        val initialModel = configManager.getCurrentModel(config)
        if (initialModel.isBlank()) throw ModelNotConfiguredException("Provider \"${config.name}\" has no configured model")
        val plan = ModelStreamPlan.fromProvider(
            config = config,
            initialModel = initialModel,
            imageIncluded = hasImage && isVisionEnabled(config, initialModel)
        )
        synchronized(turnPlanLock) {
            if (turnPlanDepth > 0 && frozenTurnPlan == null) frozenTurnPlan = plan
        }
        return plan
    }

    private fun markVisionUnsupportedIfApplicable(
        config: ProviderConfig,
        model: String,
        hasImage: Boolean,
        error: Exception,
        requestEpoch: Long
    ): Boolean {
        if (!hasImage || model.isBlank()) return false
        if (!isImageUnsupportedError(error)) return false
        return synchronized(turnPlanLock) {
            // A cancelled/closed request can finish after another turn has started.
            // Its capability result belongs to the old turn, never to its replacement.
            if (requestEpoch != imageCapabilityEpoch) return@synchronized false
            sessionImageRejected.add(visionKey(config.id, model))
            true
        }
    }

    private fun advanceToNextImageCandidate(config: ProviderConfig): String? {
        while (true) {
            val nextModel = configManager.advanceToNextModel(config) ?: return null
            if (isVisionEnabled(config, nextModel)) return nextModel
        }
    }

    private fun isImageUnsupportedError(error: Exception): Boolean {
        return error.providerHttpException()?.category == ProviderErrorCategory.IMAGE_UNSUPPORTED
    }

    private fun isImageDetailUnsupportedError(error: Exception): Boolean =
        error.providerHttpException()?.category == ProviderErrorCategory.IMAGE_DETAIL_UNSUPPORTED

    private fun isPreviousResponseNotFound(error: Exception): Boolean {
        return error.providerHttpException()?.category ==
            ProviderErrorCategory.PREVIOUS_RESPONSE_NOT_FOUND
    }

    // ====== OpenAI 兼容 HTTP 调用 ======

    /**
     * 流式调用 OpenAI 兼容 API（SSE）
     */
    private fun streamOpenAICompatible(
        endpointUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        providerName: String = "",
        supportsVision: Boolean = false,
        imageDetail: ImageDetailOption = ImageDetailOption.SERVICE_DEFAULT,
        maxOutputTokens: Int? = null,
        requestTimeoutMs: Long? = null,
        requestCancellationGeneration: Long? = null,
        reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
        reasoningParameter: ReasoningParameter = ReasoningParameter.REASONING_EFFORT,
        forceDefaultReasoning: Boolean = false
    ): Flow<LLMStreamEvent> = flow {
        val requestJson = buildRequestJson(
            messages = messages,
            model = model,
            tools = tools,
            systemPrompt = systemPrompt,
            supportsVision = supportsVision,
            imageDetail = imageDetail,
            maxOutputTokens = maxOutputTokens,
            reasoningEffort = reasoningEffort
        ).let { ReasoningRequestPolicy.apply(it, reasoningEffort, reasoningParameter, forceDefaultReasoning) }
            .let { OpenRouterPromptCache.apply(it, endpointUrl, messages) }
            .let { ToolResponseRequirement.apply(it, coroutineContext[ToolResponseRequirement] != null) }

        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        requestBuilder.post(
            requestJson.toString().toRequestBody("application/json".toMediaType())
        )

        val redactor = TransportDiagnosticRedactor(apiKey)
        val exchange = diagnostics.openExchange(coroutineContext[ModelDiagnosticScope], providerName, model,
            redactor.json(requestJson).jsonObject)
        diagnostics.requestStarted()
        val call = streamingHttpClient.newBuilder().eventListener(ModelTransportTiming(exchange)).build()
            .newCall(requestBuilder.withCacheSession(coroutineContext[ModelDiagnosticScope]).build())
        val cancellationBinding = call.bindCancellation(coroutineContext)
        requestTimeoutMs?.takeIf { it > 0L }?.let {
            call.timeout().timeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        }
        if (!registerActiveCall(call, requestCancellationGeneration)) {
            call.cancel()
            val error = RequestCancelledBeforeRegistrationException()
            cancellationBinding.complete()
            exchange.finish(error)
            throw error
        }
        val response = try {
            call.execute()
        } catch (e: Exception) {
            cancellationBinding.complete()
            tokenUsageRepository.recordRequest(ModelRequestUsage(
                provider = providerName,
                model = model,
                scope = coroutineContext[ModelDiagnosticScope]
            ))
            activeCalls.remove(call)
            exchange.finish(e)
            coroutineContext.ensureActive()
            throw e
        }
        exchange.response(response.code, response.header("x-request-id") ?: response.header("request-id"), response.header("retry-after"))
        var exchangeFailure: Throwable? = null
        val usageState = ChatCompletionsUsageState()
        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "unknown error"
                exchange.line(redactor.text(errorBody))
                throw ProviderHttpException.fromResponse(response.code, providerName, errorBody, response.header("retry-after"), redactor = redactor)
            }

            val source = response.body!!.source()
            val reader = BufferedReader(InputStreamReader(source.inputStream()))
            var finalUsageDeadlineSet = false
            var finishReason: String? = null
            var doneReceived = false
            val toolCallIndices = StreamingToolCallIndices()
            streamLoop@ while (true) {
                coroutineContext.ensureActive()
                val currentLine = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    if (usageState.terminalChoiceReceived) break@streamLoop else throw e
                } ?: break
                exchange.line(redactor.text(currentLine))
                if (currentLine.startsWith("data:")) {
                    val data = currentLine.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        doneReceived = true
                        break
                    }

                    // 先解析 JSON，JSON 格式错误才静默跳过
                    val json = try {
                        Json.parseToJsonElement(data).jsonObject
                    } catch (_: Exception) {
                        continue  // 非 JSON 行，跳过
                    }

                    // 检查 stream body 中的错误字段，通过 ProviderHttpException 保留状态码与结构化分类供瞬态重试
                    (json["error"] as? JsonObject)?.let { errObj ->
                        val codeRaw = errObj["code"]?.jsonPrimitive?.contentOrNull
                        val codeInt = (errObj["code"] as? JsonPrimitive)?.intOrNull ?: codeRaw?.toIntOrNull()
                        val statusCode = codeInt ?: 500
                        throw ProviderHttpException.fromResponse(statusCode, providerName, data, redactor = redactor)
                    }

                    // 解析 usage（出现在最后一个有效 chunk，需 stream_options.include_usage=true）
                    val usage = json["usage"] as? JsonObject
                    val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val reportedFinishReason = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
                    if (reportedFinishReason != null) finishReason = reportedFinishReason
                    usageState.observe(usage, reportedFinishReason != null)
                    if (usage != null || json["model"] is JsonPrimitive || reportedFinishReason != null) emit(LLMStreamEvent.ResponseMetadata(
                        (json["model"] as? JsonPrimitive)?.contentOrNull,
                        (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull,
                        (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull,
                        PromptCacheUsageParser.parse(usage).let { observed ->
                            observed.hitTokens.takeIf { observed.status in setOf(PromptCacheReportStatus.HIT, PromptCacheReportStatus.MISS) }
                        },
                        finishReason = finishReason,
                        cacheWriteTokens = PromptCacheUsageParser.parse(usage).cacheWriteTokens
                    ))

                    choice?.let {
                        val delta = choice["delta"] as? JsonObject

                        val text = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
                        if (text != null) emit(LLMStreamEvent.Content(text))

                        // DeepSeek-style gateways use reasoning_content; OpenRouter streams the same text as reasoning.
                        val reasoningText = (delta?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull
                            ?: (delta?.get("reasoning") as? JsonPrimitive)?.contentOrNull
                        if (reasoningText != null) emit(LLMStreamEvent.ReasoningChunk(reasoningText))

                        val toolCalls = delta?.get("tool_calls") as? JsonArray
                        if (toolCalls != null) {
                            for (tcJson in toolCalls) {
                                val tcObj = tcJson as? JsonObject ?: continue
                                val id = (tcObj["id"] as? JsonPrimitive)?.contentOrNull
                                val index = toolCallIndices.resolve((tcObj["index"] as? JsonPrimitive)?.intOrNull, id)
                                val function = tcObj["function"] as? JsonObject
                                val name = (function?.get("name") as? JsonPrimitive)?.contentOrNull
                                val arguments = (function?.get("arguments") as? JsonPrimitive)?.contentOrNull ?: ""
                                emit(LLMStreamEvent.ToolCallChunk(index = index, id = id, name = name, arguments = arguments))
                            }
                        }
                    }
                    // finish_reason terminates content; include_usage may arrive in a later chunk.
                    if (usageState.terminalChoiceReceived && usageState.usageObserved) {
                        break@streamLoop
                    }
                    if (usageState.needsFinalUsageChunk && !finalUsageDeadlineSet) {
                        source.timeout().deadlineNanoTime(
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FINAL_USAGE_GRACE_MS)
                        )
                        finalUsageDeadlineSet = true
                    }
                }
            }
            if (!usageState.terminalChoiceReceived && !doneReceived) {
                throw IOException("Chat Completions stream ended without finish_reason or [DONE]")
            }
            runCatching { reader.close() }
            val promptCacheObservation = usageState.observation
            if (usageState.usageObserved) {
                diagnostics.recordTokenUsage(
                    promptCacheObservation.promptTokens,
                    promptCacheObservation.completionTokens
                )
            }
            diagnostics.debug(
                "DroidanceLLMRouter",
                "prompt cache ${providerName.ifBlank { "generic" }}: " +
                    "status=${promptCacheObservation.status.wireName} " +
                    "hit=${promptCacheObservation.hitTokens} miss=${promptCacheObservation.missTokens}"
            )
        } catch (error: Throwable) {
            exchangeFailure = error
            coroutineContext.ensureActive()
            throw error
        } finally {
            cancellationBinding.complete()
            exchange.finish(exchangeFailure)
            val usage = usageState.observation
            tokenUsageRepository.recordRequest(ModelRequestUsage(
                provider = providerName,
                model = model,
                inputTokens = usageState.inputTokens?.toLong(),
                outputTokens = usageState.outputTokens?.toLong(),
                cachedInputTokens = usage.hitTokens.toLong().takeIf {
                    usage.status == PromptCacheReportStatus.HIT || usage.status == PromptCacheReportStatus.MISS
                },
                scope = coroutineContext[ModelDiagnosticScope]
            ))
            runCatching { response.close() }
            activeCalls.remove(call)
        }
    }.flowOn(Dispatchers.IO)

    private fun streamOpenAIResponses(
        endpointUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        providerName: String,
        supportsVision: Boolean,
        imageDetail: ImageDetailOption,
        maxOutputTokens: Int?,
        requestTimeoutMs: Long?,
        requestCancellationGeneration: Long?,
        state: OpenAIResponsesTurnState,
        includeComputerTool: Boolean,
        computerFrameSize: Pair<Int, Int>?,
        reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
        forceDefaultReasoning: Boolean = false,
        extraFields: kotlinx.serialization.json.JsonObject = ProviderRequestFields.EMPTY
    ): Flow<LLMStreamEvent> = flow {
        val requestJson = OpenAIResponsesProtocol.buildRequest(
            model = model,
            messages = messages,
            tools = tools,
            systemPrompt = systemPrompt,
            supportsVision = supportsVision,
            imageDetail = imageDetail,
            maxOutputTokens = maxOutputTokens,
            state = state,
            includeComputerTool = includeComputerTool,
            computerFrameSize = computerFrameSize,
            reasoningEffort = reasoningEffort,
            forceDefaultReasoning = forceDefaultReasoning,
            cacheStableHistory = OpenRouterPromptCache.supports(endpointUrl, model)
        ).let { ToolResponseRequirement.apply(it, coroutineContext[ToolResponseRequirement] != null) }
            .let { OpenRouterPromptCache.apply(it, endpointUrl) }
            .let { ResponsesPromptCacheKey.apply(it, endpointUrl, coroutineContext[ModelDiagnosticScope]) }
            .let { ProviderRequestFields.apply(it, extraFields) }
        emit(LLMStreamEvent.RequestConfiguration(mapOf(
            "responses_continuation" to ("previous_response_id" in requestJson).toString(),
            "responses_input_items" to ((requestJson["input"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0).toString(),
            "responses_skipped_retained_images" to state.skippedRetainedImages.toString(),
            "responses_extra_fields" to extraFields.keys.filter { it in requestJson }.sorted().joinToString(",")
        )))
        diagnostics.debug("ResponsesRequest", ResponsesRequestDiagnostics.summarize(requestJson))
        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .addHeader("Content-Type", "application/json")
        if (apiKey.isNotBlank()) requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        requestBuilder.post(requestJson.toString().toRequestBody("application/json".toMediaType()))

        val redactor = TransportDiagnosticRedactor(apiKey)
        val exchange = diagnostics.openExchange(coroutineContext[ModelDiagnosticScope], providerName, model,
            redactor.json(requestJson).jsonObject)
        diagnostics.requestStarted()
        val call = streamingHttpClient.newBuilder().eventListener(ModelTransportTiming(exchange)).build()
            .newCall(requestBuilder.withCacheSession(coroutineContext[ModelDiagnosticScope]).build())
        val cancellationBinding = call.bindCancellation(coroutineContext)
        requestTimeoutMs?.takeIf { it > 0L }?.let {
            call.timeout().timeout(it, TimeUnit.MILLISECONDS)
        }
        if (!registerActiveCall(call, requestCancellationGeneration)) {
            call.cancel()
            val error = RequestCancelledBeforeRegistrationException()
            cancellationBinding.complete()
            exchange.finish(error)
            throw error
        }
        val response = try {
            call.execute()
        } catch (e: Exception) {
            cancellationBinding.complete()
            tokenUsageRepository.recordRequest(ModelRequestUsage(
                provider = providerName,
                model = model,
                scope = coroutineContext[ModelDiagnosticScope]
            ))
            activeCalls.remove(call)
            exchange.finish(e)
            coroutineContext.ensureActive()
            throw e
        }
        exchange.response(response.code, response.header("x-request-id") ?: response.header("request-id"), response.header("retry-after"))
        var exchangeFailure: Throwable? = null
        val previousPendingComputerCallId = state.pendingComputerCallId
        var responseCompleted = false
        var emittedContentChars = 0
        var responseUsage: OpenAIResponsesProtocol.Usage? = null
        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "unknown error"
                exchange.line(redactor.text(errorBody))
                throw ProviderHttpException.fromResponse(response.code, providerName, errorBody, response.header("retry-after"), redactor = redactor)
            }
            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            var line: String?
            responsesLoop@ while (reader.readLine().also { line = it } != null) {
                coroutineContext.ensureActive()
                val currentLine = line ?: continue
                exchange.line(redactor.text(currentLine))
                if (!currentLine.startsWith("data:")) continue
                val data = currentLine.removePrefix("data:").trim()
                if (data.isBlank()) continue
                if (data == "[DONE]") break
                val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: continue
                OpenAIResponsesProtocol.usage(json)?.let { usage ->
                    responseUsage = usage
                }
                if ((json["type"] as? JsonPrimitive)?.contentOrNull in setOf(
                        "response.completed", "response.incomplete", "response.failed"
                    )) {
                    val terminalResponse = json["response"] as? JsonObject
                    emit(LLMStreamEvent.ResponseMetadata(
                        (terminalResponse?.get("model") as? JsonPrimitive)?.contentOrNull,
                        responseUsage?.inputTokens,
                        responseUsage?.outputTokens,
                        responseUsage?.takeIf { it.cacheFieldsReported }?.cachedInputTokens,
                        reasoningTokens = responseUsage?.reasoningTokens,
                        cacheWriteTokens = responseUsage?.cacheWriteTokens
                    ))
                    diagnostics.debug("ResponsesTerminal", ResponsesTerminalDiagnostics.summarize(json, emittedContentChars))
                }
                OpenAIResponsesProtocol.parseEvent(json, state).forEach {
                    if (it is LLMStreamEvent.Content) emittedContentChars += it.text.length
                    emit(it)
                }
                if (json["type"]?.jsonPrimitive?.contentOrNull == "response.completed") {
                    responseCompleted = true
                    break@responsesLoop
                }
            }
            reader.close()
            OpenAIResponsesProtocol.requireCompleted(responseCompleted)
        } catch (error: Throwable) {
            exchangeFailure = error
            coroutineContext.ensureActive()
            throw error
        } finally {
            cancellationBinding.complete()
            exchange.finish(exchangeFailure)
            val usage = responseUsage
            usage?.let { diagnostics.recordTokenUsage(it.inputTokens, it.outputTokens) }
            tokenUsageRepository.recordRequest(ModelRequestUsage(
                provider = providerName,
                model = model,
                inputTokens = usage?.takeIf { it.inputTokensReported }?.inputTokens?.toLong(),
                outputTokens = usage?.takeIf { it.outputTokensReported }?.outputTokens?.toLong(),
                cachedInputTokens = usage?.takeIf { it.cacheFieldsReported }?.cachedInputTokens?.toLong(),
                scope = coroutineContext[ModelDiagnosticScope]
            ))
            if (!responseCompleted) state.abortResponse(previousPendingComputerCallId)
            runCatching { response.close() }
            activeCalls.remove(call)
        }
    }.flowOn(Dispatchers.IO)

    private class RequestCancelledBeforeRegistrationException : IOException(
        "LLM request cancelled before registration"
    )

    // ====== JSON 构建 ======

    /**
     * 构建 OpenAI 兼容格式的请求体
     */
    private fun buildRequestJson(
        messages: List<ChatMessage>,
        model: String,
        tools: List<ToolDefinition>,
        systemPrompt: String?,
        supportsVision: Boolean = false,
        imageDetail: ImageDetailOption = ImageDetailOption.SERVICE_DEFAULT,
        maxOutputTokens: Int? = null,
        reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT
    ): JsonObject {
        return buildJsonObject {
            put("model", model)
            maxOutputTokens?.takeIf { it > 0 }?.let { put("max_tokens", it) }
            put("stream", true)
            reasoningEffort.wireValue?.let { put("reasoning_effort", it) }
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") {
                // 系统提示词
                systemPrompt?.let {
                    addJsonObject {
                        put("role", "system")
                        put("content", it)
                    }
                }

                ProviderMessageProjection.forOpenAiCompatible(messages).forEach { msg ->
                    addJsonObject {
                        put("role", when (msg.role) {
                            Role.USER -> "user"
                            Role.ASSISTANT -> "assistant"
                            Role.TOOL -> "tool"
                            else -> "user"
                        })
                        val images = msg.imageBase64List.ifEmpty {
                            msg.imageBase64?.let { listOf(it) } ?: emptyList()
                        }
                        // 带图片的 user 消息用 content array 格式（仅当 Provider 支持视觉时）
                        if (images.isNotEmpty() && msg.role == Role.USER && supportsVision) {
                            putJsonArray("content") {
                                if (msg.content.isNotBlank()) {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", msg.content)
                                    }
                                }
                                images.forEach { imageBase64 ->
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            val mimeType = detectImageMimeType(imageBase64)
                                            put("url", "data:$mimeType;base64,$imageBase64")
                                            imageDetail.wireValue?.let { put("detail", it) }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Preserve public progress alongside tool calls in the durable history.
                            if (msg.role == Role.ASSISTANT && !msg.toolCalls.isNullOrEmpty() && msg.content.isBlank()) {
                                put("content", JsonNull)
                            } else {
                                put("content", msg.content)
                            }
                        }
                        msg.toolCallId?.let { put("tool_call_id", it) }
                        msg.reasoningContent?.let { put("reasoning_content", it) }

                        val assistantToolCalls = msg.toolCalls
                        if (msg.role == Role.ASSISTANT && assistantToolCalls != null) {
                            putJsonArray("tool_calls") {
                                assistantToolCalls.forEach { tc ->
                                    addJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", tc.name)
                                            put("arguments", tc.arguments)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 工具定义
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                // Keep optional fields optional across provider schema adapters.
                                put("strict", false)
                                putJsonObject("parameters") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        tool.parameters.forEach { (name, param) ->
                                            put(name, param.toJsonSchema())
                                        }
                                    }
                                    putJsonArray("required") {
                                        tool.parameters.filter { it.value.required }
                                            .forEach { add(it.key) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shouldRetryTransientStreamError(error: Exception): Boolean {
        return error is IOException && error !is RequestCancelledBeforeRegistrationException
    }

    private fun shouldFallbackModel(error: Exception): Boolean =
        error.providerHttpException()?.category in setOf(
            ProviderErrorCategory.RATE_LIMIT,
            ProviderErrorCategory.BILLING,
            ProviderErrorCategory.MODEL_UNAVAILABLE
        )

}
