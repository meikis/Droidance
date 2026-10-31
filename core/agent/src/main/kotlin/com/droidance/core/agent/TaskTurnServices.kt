// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.workspace.TaskWorkspace

import com.droidance.core.agent.harness.DurableModelRoundCoordinator
import com.droidance.core.agent.harness.ToolContractCatalog
import com.droidance.core.agent.context.DurableContextCheckpointCoordinator
import com.droidance.core.agent.prompt.StablePrefix
import com.droidance.core.agent.prompt.StablePrefixBuilder
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.ports.AgentConfiguration
import com.droidance.core.agent.debug.DebugLogAppender
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.agent.tools.ToolCatalog
import com.droidance.core.agent.ports.ToolExecutorPort
import com.droidance.core.agent.tools.OperatorToolProfile
import com.droidance.core.agent.tools.UnifiedDeviceToolProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TaskTurnServices @Inject constructor(
    internal val llmRouter: ModelClient,
    internal val toolExecutor: ToolExecutorPort,
    internal val debugLogManager: DebugLogAppender,
    internal val evidenceLedger: TaskEvidenceLedger,
    internal val evidenceRecorder: OperatorEvidenceRecorder,
    internal val observationFreshnessPolicy: ObservationFreshnessPolicy,
    internal val ruleConfigManager: AgentConfiguration,
    internal val latencyTracker: com.droidance.core.agent.debug.TaskLatencyTracker,
    internal val toolContractCatalog: ToolContractCatalog,
    internal val durableModelRoundCoordinator: DurableModelRoundCoordinator,
    internal val taskContextCompactor: TaskContextCompactor,
    internal val durableContextCheckpointCoordinator: DurableContextCheckpointCoordinator,
    internal val eventStore: AgentEventStore,
    internal val blobStore: BlobStore,
    internal val runtimeInfo: com.droidance.core.agent.ports.TaskRuntimeInfo
    ) {
    internal val MAX_TASK_NOTE_CHARS = 4000
    internal val workspaceContextReader = TaskWorkspace(eventStore, blobStore)

    internal val modelContextAssembler = com.droidance.core.agent.context.ModelContextAssembler(
        maxTaskNoteChars = MAX_TASK_NOTE_CHARS,
        pureVlmMode = com.droidance.core.agent.tools.ObservationModes.PURE_VLM,
        text = { key, values ->
            values.entries.fold(ruleConfigManager.readMarkdownSection("model_context.md", key)) { template, (name, value) ->
                template.replace("{$name}", value)
            }
        }
    )

    internal data class OperatorTooling(
        val tools: List<ToolDefinition>,
        val contentTools: List<ToolDefinition>,
        val scheduler: OperatorRoundScheduler,
        val completionGroundingByTool: Map<String, Boolean>,
        val registeredTools: List<com.droidance.core.agent.harness.RegisteredHarnessTool>
    )

    /**
     * Captures the exact Operator tool schema used by this turn as part of its stable
     * model-session identity. Context budgeting must count the same schema that the
     * provider receives; an empty placeholder would undercount every long task.
     */
    fun stablePrefix(
        mode: com.droidance.core.agent.device.RuntimeMode?,
        executionSpace: com.droidance.core.agent.device.ExecutionSpace?,
        toolProfile: OperatorToolProfile = OperatorToolProfile.DEFAULT,
        extraSystemContext: String = ""
    ): StablePrefix {
        val tooling = buildOperatorTooling(
            mode,
            executionSpace,
            ruleConfigManager.harnessPrompts().communicationContract,
            toolProfile
        )
        return StablePrefixBuilder.build(
            systemPrompt = trustedOperatorSystemPrompt(extraSystemContext),
            tools = tooling.tools,
            indexEntries = emptyList()
        )
    }

    internal val operatorStreamCollector = OperatorStreamCollector(llmRouter) {
        debugLogManager.appendEvent(it)
    }

    internal fun buildOperatorTooling(
        mode: com.droidance.core.agent.device.RuntimeMode?,
        executionSpace: com.droidance.core.agent.device.ExecutionSpace?,
        communication: com.droidance.core.agent.config.CommunicationContract,
        toolProfile: OperatorToolProfile = OperatorToolProfile.DEFAULT
    ): OperatorTooling {
        val schemaPolicy = Json.decodeFromString<com.droidance.core.agent.tools.ToolSchemaContextPolicy>(
            ruleConfigManager.readMarkdownSection("tool_context.md", "schema_context_policy"))
        val baseTools = com.droidance.core.agent.tools.ToolSchemaContext.omitParameterDocumentation(
            toolExecutor.getOperatorToolDefinitions(mode, executionSpace, toolProfile), schemaPolicy)
        val registeredOperatorTools = toolContractCatalog.resolve(
            toolExecutor.getOperatorTools(mode, executionSpace, toolProfile)
        )
        val tools = ActionControlProtocol.addControlParameters(
            tools = GroundedActionProtocol.addScreenEvidenceParameter(
                tools = baseTools,
                parameterDescription = communication.screenEvidenceParameter,
                applyTo = ToolCatalog.screenEvidenceToolNames
            ),
            descriptions = ActionControlProtocol.Descriptions(
                evidenceRefs = communication.evidenceRefsParameter,
                terminalObservationRef = communication.terminalObservationRefParameter,
                claims = ruleConfigManager.readMarkdownSection("completion_recovery.md", "result_entries_parameter"),
                claimResult = ruleConfigManager.readMarkdownSection("completion_recovery.md", "result_text_parameter"),
                unresolvedRequirements = ruleConfigManager.readMarkdownSection("completion_recovery.md", "unresolved_requirements_parameter"),
                requirementChecks = ruleConfigManager.readMarkdownSection("completion_recovery.md", "requirement_checks_parameter")
            ),
            completionTools = ToolCatalog.finalizationToolNames
        )
        val actionTitledTools = ActionTitleProtocol.addParameter(
            ActionControlProtocol.submissionTools(tools), ruleConfigManager.readMarkdownSection("agent_core.md", "action_title_parameter")
                .replace("{language}", ruleConfigManager.outputLanguage()))
        val metadataCarriers = actionTitledTools.filter { schemaPolicy.turnMetadataTools?.contains(it.name) ?: true }
        val carriersWithMetadata = TaskDeadlineProtocol.addParameter(ConversationTitleProtocol.addParameter(TaskTitleProtocol.addParameter(
            metadataCarriers,
            ruleConfigManager.readMarkdownSection("agent_core.md", "task_title_parameter")
                .replace("{language}", ruleConfigManager.outputLanguage())),
            ruleConfigManager.readMarkdownSection("conversation_title.md", "tool_parameter")),
            ruleConfigManager.readMarkdownSection("agent_core.md", "task_deadline_parameter")).associateBy { it.name }
        val titledTools = actionTitledTools.map { carriersWithMetadata[it.name] ?: it }
        // Effort hints are offered only when the provider model accepts an adaptive level.
        // The effort hint rides on the same task-opening tools as the other turn metadata.
        val submissionTools = if (adaptiveReasoningAvailable()) {
            val hinted = ReasoningEffortProtocol.addParameter(metadataCarriers,
                ruleConfigManager.readMarkdownSection("agent_core.md", "next_reasoning_effort_parameter"),
                reasoningControlPolicy()).associate { it.name to it.parameters.getValue(ReasoningEffortProtocol.ARGUMENT) }
            titledTools.map { tool -> hinted[tool.name]?.let { tool.copy(parameters = tool.parameters +
                (ReasoningEffortProtocol.ARGUMENT to it)) } ?: tool }
        } else titledTools
        return OperatorTooling(
            tools = com.droidance.core.agent.tools.ToolSchemaContext.compact(submissionTools, schemaPolicy),
            contentTools = tools,
            scheduler = OperatorRoundScheduler(
                registrations = registeredOperatorTools.map { it.contract },
                capabilityRequirements = registeredOperatorTools.mapNotNull { it.capabilityRequirement }
            ),
            completionGroundingByTool = registeredOperatorTools.associate {
                it.contract.name to it.groundsCompletion
            },
            registeredTools = registeredOperatorTools
        )
    }

    internal fun reasoningControlPolicy(): ReasoningControlPolicy = Json.decodeFromString<ReasoningControlPolicy>(
        ruleConfigManager.readMarkdownSection("agent_core.md", "reasoning_control_policy")
    ).also { it.validate() }

    internal fun adaptiveReasoningAvailable(): Boolean =
        llmRouter.explicitReasoningEfforts().any { it in reasoningControlPolicy().adaptiveEfforts }

    /** 唯一 Operator system prompt：通用执行协议 + 输出语言。 */
    internal fun operatorSystemPrompt(): String = ruleConfigManager.operatorSystemPrompt() + "\n\n" +
        ruleConfigManager.readMarkdownSection("tool_context.md", "contract") +
        if (adaptiveReasoningAvailable()) "\n\n" + ruleConfigManager.readMarkdownSection("agent_core.md", "reasoning_control") else ""

    internal fun trustedOperatorSystemPrompt(extraSystemContext: String): String {
        val operatorPrompt = operatorSystemPrompt() + "\n\n" +
            ruleConfigManager.readMarkdownSection("conversation_title.md", "policy")
        return if (extraSystemContext.isNotBlank()) {
            "<trusted_system_instruction>\n$operatorPrompt\n\n$extraSystemContext\n</trusted_system_instruction>"
        } else {
            "<trusted_system_instruction>\n$operatorPrompt\n</trusted_system_instruction>"
        }
    }

    internal fun guardText(key: String, values: Map<String, String> = emptyMap()): String {
        val template = ruleConfigManager.message(key)
        return values.entries.fold(template) { acc, (name, value) ->
            acc.replace("{$name}", value)
        }
    }

    internal fun fallbackText(key: String, values: Map<String, String> = emptyMap()): String {
        val template = ruleConfigManager.publicFallback(key)
        return values.entries.fold(template) { text, (name, value) ->
            text.replace("{$name}", value)
        }
    }

    internal fun screenshotMimeType(bytes: ByteArray): String = when {
        bytes.size >= 8 &&
            bytes[0].toInt() and 0xff == 0x89 &&
            bytes[1].toInt() and 0xff == 0x50 &&
            bytes[2].toInt() and 0xff == 0x4e &&
            bytes[3].toInt() and 0xff == 0x47 -> "image/png"
        bytes.size >= 2 &&
            bytes[0].toInt() and 0xff == 0xff &&
            bytes[1].toInt() and 0xff == 0xd8 -> "image/jpeg"
        else -> "image/png"
    }

    internal fun jsonStringArg(arguments: String, vararg keys: String): String? {
        val obj = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return null
        return keys.firstNotNullOfOrNull { key ->
            obj[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    internal fun isScreenObservation(toolCall: ToolCall): Boolean =
        UnifiedDeviceToolProtocol.isScreenObservation(toolCall.name, toolCall.arguments)

    /**
     * 执行当前用户目标的唯一模型循环。
     *
     * @param taskGoal 当前用户目标
     * @param additionalContext 当前会话中与该目标直接相关的附加上下文
     * @param onStreamContent 流式输出回调
     * @param stateFlow Agent 状态流（用于更新 UI）
     * @param onActionUpdate 工具执行前的动作描述回调
     * @param onTaskTitle 当前任务的固定短标题，在正常响应提交后发布一次
     * @param onManualTakeoverNeeded 模型识别到真实用户介入边界时暴露人工接管入口
     * @param waitIfPaused 暂停检查回调
     * @param takeManualResumeInstruction 用户接管后恢复时，取一次恢复提示并丢弃暂停前的旧动作
     */

    internal fun promptValuePreview(value: String, maxChars: Int): String {
        return value.replace(Regex("\\s+"), " ").trim().take(maxChars)
    }

    /**
     * Promote the explicitly public instruction/reason when the model omits its reply.
     * The planner validates the proposal before committing a public handoff; rejected calls
     * remain tool feedback for the same Agent, while accepted handoffs require human resume.
     */
    internal fun interventionPublicReply(toolCalls: List<ToolCall>, publicText: String): String? {
        val intervention = toolCalls.firstOrNull { it.name == "ask_human" } ?: return null
        if (publicText.isNotBlank()) return publicText
        val arguments = runCatching {
            Json.parseToJsonElement(intervention.arguments).jsonObject
        }.getOrNull() ?: return guardText("user_intervention_invalid_request")
        return sequenceOf("instruction", "reason")
            .mapNotNull { key -> (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() }
            .firstOrNull(String::isNotBlank) ?: guardText("user_intervention_invalid_request")
    }

    /**
     * Whether the paired shell control channel is unavailable.
     * Accepts structured tool codes and the input backend's current channel marker.
     */
    internal fun looksLikeRootUnavailable(result: ToolExecutionResult): Boolean =
        result.diagnosticCode == "CHANNEL_DOWN"

    internal fun isLlmTransportTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is InterruptedIOException ||
                current is java.net.SocketTimeoutException
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    internal companion object {
        /** 动作叙事保留的条数：够模型看清"已经试过什么"，又不会把上下文撑大。 */
        const val ACTION_NARRATIVE_LIMIT = 8

    }

    /**
     * 生成供悬浮窗实时显示的操作标签
     * 目标：让用户在悬浮窗上直观看到"Agent 当前在做什么"
     */
    internal fun buildActionLabel(tc: ToolCall): String {
        val operation = UnifiedDeviceToolProtocol.operation(tc.name, tc.arguments)
        return if (operation == null) tc.name else "${tc.name}.$operation"
    }

}

internal fun decodeModelFrame(base64: String): ByteArray? {
    val payload = if (base64.startsWith("data:")) base64.substringAfter(',') else base64
    return runCatching { java.util.Base64.getDecoder().decode(payload) }.getOrNull()
}
