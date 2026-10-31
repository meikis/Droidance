// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.model.llm.models.ToolParameter
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.Serializable

data class ProviderToolCall(
    val providerCallId: String?,
    val name: String,
    val argumentsJson: String
) {
    init {
        require(providerCallId == null || providerCallId.isNotBlank()) {
            "Provider call id cannot be blank"
        }
        require(name.isNotBlank()) { "Provider tool name cannot be blank" }
        require(argumentsJson.isNotBlank()) { "Provider tool arguments cannot be blank" }
    }
}

class NormalizedModelRound private constructor(
    publicBlocks: List<String>,
    toolCalls: List<ProviderToolCall>,
    finishMetadata: Map<String, String>
) {
    val publicBlocks: List<String> = Collections.unmodifiableList(ArrayList(publicBlocks))
    val toolCalls: List<ProviderToolCall> = Collections.unmodifiableList(ArrayList(toolCalls))
    val finishMetadata: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(finishMetadata))

    companion object {
        fun create(
            publicBlocks: List<String>,
            toolCalls: List<ProviderToolCall>,
            finishMetadata: Map<String, String> = emptyMap()
        ): NormalizedModelRound = NormalizedModelRound(
            publicBlocks = publicBlocks.mapNotNull { block ->
                block.replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .takeIf { normalized -> normalized.isNotBlank() }
            },
            toolCalls = toolCalls.map(ProviderToolCall::copy),
            finishMetadata = finishMetadata
        )
    }

    val publicText: String?
        get() = publicBlocks.takeIf(List<String>::isNotEmpty)?.joinToString("\n")

    override fun equals(other: Any?): Boolean = other is NormalizedModelRound &&
        publicBlocks == other.publicBlocks &&
        toolCalls == other.toolCalls &&
        finishMetadata == other.finishMetadata

    override fun hashCode(): Int {
        var result = publicBlocks.hashCode()
        result = 31 * result + toolCalls.hashCode()
        result = 31 * result + finishMetadata.hashCode()
        return result
    }

    override fun toString(): String =
        "NormalizedModelRound(publicBlocks=$publicBlocks, toolCalls=$toolCalls, finishMetadata=$finishMetadata)"
}

class RegisteredToolContract(
    val name: String,
    val description: String,
    val binding: ToolBinding,
    val effect: ToolEffect,
    parameters: Map<String, ToolParameter>,
    val requiresResultBeforeAction: Boolean = false
) {
    val parameters: Map<String, ToolParameter> = Collections.unmodifiableMap(
        LinkedHashMap(parameters.mapValues { (_, parameter) ->
            freezeParameter(parameter)
        })
    )
    val contractHash: String

    init {
        require(name.isNotBlank()) { "Registered tool name cannot be blank" }
        require(description.isNotBlank()) { "Registered tool description cannot be blank" }
        require(!requiresResultBeforeAction || effect == ToolEffect.READ_ONLY) {
            "Only READ_ONLY contracts may require their result before an action"
        }
        ToolArgumentSchemaValidator.requireValidSchema(this.parameters)
        contractHash = calculateContractHash(
            name = name,
            description = description,
            binding = binding,
            effect = effect,
            parameters = this.parameters,
            requiresResultBeforeAction = requiresResultBeforeAction
        )
    }

    fun forArguments(arguments: String): RegisteredToolContract {
        if (name !in com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.argumentDependentTools) return this
        val requirements = com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.requirements(name, arguments)
        val resolvedBinding = if (requirements.requiresBackend) ToolBinding.DEVICE_BOUND else ToolBinding.TURN_LOCAL
        if (resolvedBinding == binding) return this
        return RegisteredToolContract(name, description, resolvedBinding, effect, parameters, requiresResultBeforeAction)
    }

    override fun equals(other: Any?): Boolean = other is RegisteredToolContract &&
        name == other.name && description == other.description && binding == other.binding &&
        effect == other.effect && parameters == other.parameters &&
        requiresResultBeforeAction == other.requiresResultBeforeAction

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + binding.hashCode()
        result = 31 * result + effect.hashCode()
        result = 31 * result + parameters.hashCode()
        result = 31 * result + requiresResultBeforeAction.hashCode()
        return result
    }

    override fun toString(): String =
        "RegisteredToolContract(name=$name, description=$description, binding=$binding, " +
            "effect=$effect, parameters=$parameters, requiresResultBeforeAction=$requiresResultBeforeAction)"

    private companion object {
        fun calculateContractHash(
            name: String,
            description: String,
            binding: ToolBinding,
            effect: ToolEffect,
            parameters: Map<String, ToolParameter>,
            requiresResultBeforeAction: Boolean
        ): String {
            val fields = buildList {
                add(name)
                add(description)
                add(binding.name)
                add(effect.name)
                add(requiresResultBeforeAction.toString())
                add(parameters.size.toString())
                fun addParameter(parameterName: String, parameter: ToolParameter) {
                    add(parameterName)
                    add(parameter.type)
                    add(parameter.description)
                    add(parameter.required.toString())
                    parameter.requiredWhen.toSortedMap().forEach { (selector, values) ->
                        add(selector)
                        add(values.size.toString())
                        values.forEach(::add)
                    }
                    parameter.allowedWhen.toSortedMap().forEach { (selector, values) ->
                        add("allowedWhen")
                        add(selector)
                        add(values.size.toString())
                        values.forEach(::add)
                    }
                    val enumValues = parameter.enumValues
                    add(enumValues?.size?.toString() ?: "-1")
                    enumValues?.forEach(::add)
                    add(parameter.additionalProperties?.toString().orEmpty())
                    parameter.items?.let { addParameter("$parameterName[]", it) }
                    parameter.properties.orEmpty().toSortedMap().forEach { (name, nested) ->
                        addParameter("$parameterName.$name", nested)
                    }
                    parameter.additionalPropertySchema?.let { addParameter("$parameterName.*", it) }
                }
                parameters.toSortedMap().forEach { (parameterName, parameter) ->
                    addParameter(parameterName, parameter)
                }
            }
            val canonical = fields.joinToString(separator = "") { field ->
                "${field.length}:$field"
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun freezeParameter(parameter: ToolParameter): ToolParameter = parameter.copy(
            enumValues = parameter.enumValues?.let { Collections.unmodifiableList(ArrayList(it)) },
            requiredWhen = Collections.unmodifiableMap(parameter.requiredWhen.toSortedMap().mapValues {
                Collections.unmodifiableList(ArrayList(it.value))
            }),
            allowedWhen = Collections.unmodifiableMap(parameter.allowedWhen.toSortedMap().mapValues {
                Collections.unmodifiableList(ArrayList(it.value))
            }),
            items = parameter.items?.let(::freezeParameter),
            properties = parameter.properties?.toSortedMap()?.mapValues { (_, nested) ->
                freezeParameter(nested)
            }?.let { Collections.unmodifiableMap(LinkedHashMap(it)) },
            additionalPropertySchema = parameter.additionalPropertySchema?.let(::freezeParameter)
        )
    }
}

@Serializable
enum class RepairPurpose {
    NONE
}

@Serializable
enum class PublicMessageKind {
    START,
    USER_INTERVENTION,
    PROGRESS,
    STAGE_RESULT,
    FINAL
}

/**
 * Shared by scheduling and durable-commit validation. Text after the opening is ordinary progress and
 * stays in the conversation; STAGE_RESULT remains only so earlier persisted rounds still project.
 */
internal fun NormalizedModelRound.actionPublicMessageKind(startCommitted: Boolean): PublicMessageKind =
    if (!startCommitted) PublicMessageKind.START else PublicMessageKind.PROGRESS

data class PublicMessagePlan(
    val kind: PublicMessageKind,
    val text: String
) {
    init {
        require(text.isNotBlank()) { "Public model message cannot be blank" }
    }
}

@Serializable
enum class RoundExecutionMode {
    NONE,
    SERIAL,
    PARALLEL
}

@Serializable
enum class RoundFollowUp {
    NONE,
    COMPLETE_AFTER_RESULTS,
    WAIT_FOR_USER,
    TERMINATE_PROTOCOL
}

object RoundDiagnosticCode {
    const val UNKNOWN_TOOL = "UNKNOWN_TOOL"
    const val TOOL_NOT_PRESENTED = "TOOL_NOT_PRESENTED"
    const val TASK_EXECUTION_BUDGET_EXHAUSTED = "TASK_EXECUTION_BUDGET_EXHAUSTED"
    const val QUERY_RESULT_REQUIRED = "QUERY_RESULT_REQUIRED"
    const val PRIOR_CALL_REJECTED = "PRIOR_CALL_REJECTED"
    const val INTERVENTION_EXCLUSIVE = "INTERVENTION_EXCLUSIVE"
    const val COMPLETION_WITH_ACTION = "COMPLETION_WITH_ACTION"
    const val ONE_COMPLETION_PER_ROUND = "ONE_COMPLETION_PER_ROUND"
    const val MISSING_INTERVENTION_REPLY = "MISSING_INTERVENTION_REPLY"
    const val EMPTY_MODEL_RESPONSE = "EMPTY_MODEL_RESPONSE"
}

data class PlannedToolCall(
    val providerOrder: Int,
    val callId: String,
    val providerCall: ProviderToolCall,
    val contract: RegisteredToolContract
) {
    init {
        require(providerOrder >= 0) { "Provider order cannot be negative" }
        require(callId.isNotBlank()) { "Harness callId cannot be blank" }
        require(providerCall.name == contract.name) {
            "Tool contract must match the Provider tool name"
        }
    }
}

data class RejectedToolCall(
    val call: PlannedToolCall,
    val diagnosticCode: String,
    /** IDs visible in function_call/tool messages, with the existing canonical fallback. */
    val prerequisiteProviderCallIds: List<String> = emptyList()
) {
    init {
        require(diagnosticCode.isNotBlank()) { "Rejected tool call requires a diagnostic code" }
        require(prerequisiteProviderCallIds.all(String::isNotBlank)) {
            "Prerequisite references must be nonblank model-visible call IDs"
        }
    }
}

class RoundExecutionPlan(
    val publicMessage: PublicMessagePlan?,
    selectedCalls: List<PlannedToolCall>,
    rejectedCalls: List<RejectedToolCall>,
    val executionMode: RoundExecutionMode,
    val followUp: RoundFollowUp,
    val terminalDiagnosticCode: String? = null
) {
    val selectedCalls: List<PlannedToolCall> =
        Collections.unmodifiableList(ArrayList(selectedCalls))
    val rejectedCalls: List<RejectedToolCall> =
        Collections.unmodifiableList(ArrayList(rejectedCalls))

    init {
        val selectedIds = this.selectedCalls.map(PlannedToolCall::callId)
        val rejectedIds = this.rejectedCalls.map { it.call.callId }
        require(selectedIds.toSet().size == selectedIds.size) {
            "Selected Harness callIds must be unique"
        }
        require(rejectedIds.toSet().size == rejectedIds.size) {
            "Rejected Harness callIds must be unique"
        }
        require(selectedIds.none(rejectedIds::contains)) {
            "A Harness call cannot be both selected and rejected"
        }
        require((this.selectedCalls.isEmpty()) == (executionMode == RoundExecutionMode.NONE)) {
            "Execution mode must agree with the selected tool calls"
        }
        require(executionMode != RoundExecutionMode.PARALLEL || this.selectedCalls.all(::isPureLocalRead)) {
            "Only TURN_LOCAL READ_ONLY calls may execute in parallel"
        }
        require(terminalDiagnosticCode == null || terminalDiagnosticCode.isNotBlank()) {
            "Terminal diagnostic code cannot be blank"
        }
    }

    val allCallIds: Set<String>
        get() = (selectedCalls.map { it.callId } + rejectedCalls.map { it.call.callId }).toSet()
}

class RoundExecutionPlanner(
    registrations: Collection<RegisteredToolContract>,
    presentedToolNames: Set<String>? = null,
    private val callIdFactory: (Int, ProviderToolCall) -> String = { _, _ -> UUID.randomUUID().toString() },
    private val preflight: (PlannedToolCall) -> String? = { null },
    private val mutationsRestricted: Boolean = false
) {
    private val registrations = registrations.toList().also { contracts ->
        require(contracts.map(RegisteredToolContract::name).toSet().size == contracts.size) {
            "Tool registrations must use unique names"
        }
    }.associateBy(RegisteredToolContract::name)
    private val presented = (presentedToolNames ?: this.registrations.keys).toSet()
    private val unknownTool = RegisteredToolContract(
        name = "__unknown__",
        description = "Unregistered Provider tool",
        binding = ToolBinding.TURN_LOCAL,
        effect = ToolEffect.STATE_CHANGING,
        parameters = emptyMap()
    )

    fun plan(
        round: NormalizedModelRound,
        startCommitted: Boolean,
        repairPurpose: RepairPurpose
    ): RoundExecutionPlan {
        if (round.isZeroToolActivation()) {
            require(round.finishMetadata[ZERO_TOOL_OPENING_VERSION_KEY] == ZERO_TOOL_OPENING_VERSION)
            require(round.toolCalls.isEmpty() && !startCommitted) { "Activation must precede tools and START" }
            val summary = requireNotNull(round.publicText) { "Activation requires a public summary" }
            return RoundExecutionPlan(
                publicMessage = PublicMessagePlan(PublicMessageKind.START, summary),
                selectedCalls = emptyList(), rejectedCalls = emptyList(),
                executionMode = RoundExecutionMode.NONE,
                followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS
            )
        }
        val calls = assignCallIds(round.toolCalls)
        val publicText = round.publicText
        val interventions = calls.filter { it.contract.effect == ToolEffect.USER_INTERVENTION }
        val completions = calls.filter { it.contract.effect == ToolEffect.COMPLETION }
        val actions = calls.filter {
            it.contract.effect == ToolEffect.READ_ONLY || it.contract.effect == ToolEffect.STATE_CHANGING
        }

        if (interventions.isNotEmpty()) {
            if (publicText == null) {
                return rejectAll(
                    calls = calls,
                    diagnosticCode = RoundDiagnosticCode.MISSING_INTERVENTION_REPLY,
                    terminalDiagnosticCode = RoundDiagnosticCode.MISSING_INTERVENTION_REPLY
                )
            }
            val selected = interventions.first()
            return planWithPreflight(
                publicMessage = PublicMessagePlan(PublicMessageKind.USER_INTERVENTION, publicText),
                selected = listOf(selected),
                rejected = calls.filterNot { it.callId == selected.callId }
                    .map { RejectedToolCall(it, RoundDiagnosticCode.INTERVENTION_EXCLUSIVE) },
                mode = RoundExecutionMode.SERIAL,
                followUp = RoundFollowUp.WAIT_FOR_USER
            )
        }

        if (actions.isNotEmpty()) {
            val message = when {
                publicText == null -> null
                else -> PublicMessagePlan(round.actionPublicMessageKind(startCommitted), publicText)
            }
            return scheduleActions(calls, actions, completions, message)
        }

        return completionOrFinal(calls, completions, publicText)
    }

    private fun assignCallIds(providerCalls: List<ProviderToolCall>): List<PlannedToolCall> {
        val calls = providerCalls.mapIndexed { index, providerCall ->
            val callId = callIdFactory(index, providerCall)
            require(callId.isNotBlank()) { "Harness callId cannot be blank" }
            PlannedToolCall(
                providerOrder = index,
                callId = callId,
                providerCall = providerCall,
                contract = registrations[providerCall.name]?.forArguments(providerCall.argumentsJson) ?: RegisteredToolContract(
                    name = providerCall.name,
                    description = unknownTool.description,
                    binding = unknownTool.binding,
                    effect = unknownTool.effect,
                    parameters = unknownTool.parameters
                )
            )
        }
        require(calls.map { it.callId }.toSet().size == calls.size) { "Harness callIds must be unique" }
        return calls
    }

    private fun scheduleActions(
        allCalls: List<PlannedToolCall>,
        actions: List<PlannedToolCall>,
        completions: List<PlannedToolCall>,
        publicMessage: PublicMessagePlan?
    ): RoundExecutionPlan {
        val rejected = allCalls
            .filter { it.providerCall.name !in presented }
            .mapTo(mutableListOf()) { call ->
                val code = when {
                    call.providerCall.name !in registrations -> RoundDiagnosticCode.UNKNOWN_TOOL
                    mutationsRestricted && call.contract.binding == ToolBinding.DEVICE_BOUND &&
                        call.contract.effect == ToolEffect.STATE_CHANGING -> RoundDiagnosticCode.TASK_EXECUTION_BUDGET_EXHAUSTED
                    else -> RoundDiagnosticCode.TOOL_NOT_PRESENTED
                }
                RejectedToolCall(call, code)
            }
        val selected = mutableListOf<PlannedToolCall>()
        // Calls before a query are already grounded; calls after it cannot consume an
        // unread result. Local writes have their own ordering, not a device-action slot.
        var awaitsQueryResult = false
        val prerequisiteProviderCallIds = mutableListOf<String>()
        actions.filter { it.providerCall.name in presented }.forEach { call ->
            if (awaitsQueryResult && call.contract.binding == ToolBinding.DEVICE_BOUND &&
                call.contract.effect == ToolEffect.STATE_CHANGING
            ) {
                rejected += RejectedToolCall(call, RoundDiagnosticCode.QUERY_RESULT_REQUIRED,
                    prerequisiteProviderCallIds.toList())
            } else if (mutationsRestricted && call.contract.binding == ToolBinding.DEVICE_BOUND &&
                call.contract.effect == ToolEffect.STATE_CHANGING
            ) {
                rejected += RejectedToolCall(call, RoundDiagnosticCode.TASK_EXECUTION_BUDGET_EXHAUSTED)
            } else {
                selected += call
            }
            val requiresResult = call.contract.requiresResultBeforeAction ||
                com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.requestsFrameWait(call.providerCall.name, call.providerCall.argumentsJson)
            if (requiresResult) prerequisiteProviderCallIds +=
                call.providerCall.providerCallId?.takeIf(String::isNotBlank) ?: call.callId
            awaitsQueryResult = awaitsQueryResult || requiresResult
        }
        rejected += completions.map { RejectedToolCall(it, RoundDiagnosticCode.COMPLETION_WITH_ACTION) }
        val mode = if (selected.isNotEmpty() && selected.all(::isPureLocalRead)) {
            RoundExecutionMode.PARALLEL
        } else if (selected.isNotEmpty()) {
            RoundExecutionMode.SERIAL
        } else {
            RoundExecutionMode.NONE
        }
        return planWithPreflight(
            publicMessage = publicMessage,
            selected = selected,
            rejected = rejected,
            mode = mode,
            followUp = RoundFollowUp.NONE
        )
    }

    private fun completionOrFinal(
        allCalls: List<PlannedToolCall>,
        completions: List<PlannedToolCall>,
        publicText: String?
    ): RoundExecutionPlan {
        val selected = completions.take(1)
        val rejected = completions.drop(1)
            .map { RejectedToolCall(it, RoundDiagnosticCode.ONE_COMPLETION_PER_ROUND) }
        if (publicText != null || selected.isNotEmpty()) {
            return planWithPreflight(
                publicMessage = publicText?.let { PublicMessagePlan(PublicMessageKind.FINAL, it) },
                selected = selected,
                rejected = rejected,
                mode = if (selected.isEmpty()) RoundExecutionMode.NONE else RoundExecutionMode.SERIAL,
                followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS
            )
        }
        val diagnostic = if (allCalls.isEmpty()) {
            RoundDiagnosticCode.EMPTY_MODEL_RESPONSE
        } else {
            null
        }
        return planWithPreflight(
            publicMessage = null,
            selected = selected,
            rejected = rejected,
            mode = if (selected.isEmpty()) RoundExecutionMode.NONE else RoundExecutionMode.SERIAL,
            followUp = RoundFollowUp.TERMINATE_PROTOCOL,
            terminalDiagnosticCode = diagnostic
        )
    }

    private fun rejectAll(
        calls: List<PlannedToolCall>,
        diagnosticCode: String,
        terminalDiagnosticCode: String?,
        followUp: RoundFollowUp = RoundFollowUp.TERMINATE_PROTOCOL
    ): RoundExecutionPlan = RoundExecutionPlan(
        publicMessage = null,
        selectedCalls = emptyList(),
        rejectedCalls = calls.map { RejectedToolCall(it, diagnosticCode) },
        executionMode = RoundExecutionMode.NONE,
        followUp = followUp,
        terminalDiagnosticCode = terminalDiagnosticCode
    )

    private fun planWithPreflight(
        publicMessage: PublicMessagePlan?,
        selected: List<PlannedToolCall>,
        rejected: List<RejectedToolCall>,
        mode: RoundExecutionMode,
        followUp: RoundFollowUp,
        terminalDiagnosticCode: String? = null
    ): RoundExecutionPlan {
        val accepted = mutableListOf<PlannedToolCall>()
        val allRejected = rejected.toMutableList()
        var rejectedStatefulPrefix = false
        selected.forEach { call ->
            val diagnostic = ToolArgumentSchemaValidator.diagnosticFor(
                contract = call.contract,
                argumentsJson = call.providerCall.argumentsJson
            ) ?: preflight(call)
            val earlierRejection = call.contract.effect in setOf(ToolEffect.READ_ONLY, ToolEffect.STATE_CHANGING) &&
                rejected.any { it.call.providerOrder < call.providerOrder }
            if (diagnostic == null && !rejectedStatefulPrefix && !earlierRejection) {
                accepted += call
            } else {
                allRejected += RejectedToolCall(call, diagnostic ?: RoundDiagnosticCode.PRIOR_CALL_REJECTED)
                rejectedStatefulPrefix = true
            }
        }
        val effectiveMode = when {
            accepted.isEmpty() -> RoundExecutionMode.NONE
            accepted.all(::isPureLocalRead) -> RoundExecutionMode.PARALLEL
            else -> mode
        }
        // A proposal rejected before dispatch is not an accepted human handoff.
        val interventionRejected = followUp == RoundFollowUp.WAIT_FOR_USER && accepted.isEmpty()
        return RoundExecutionPlan(
            publicMessage = publicMessage.takeUnless { interventionRejected },
            selectedCalls = accepted,
            rejectedCalls = allRejected.sortedBy { it.call.providerOrder },
            executionMode = effectiveMode,
            followUp = if (interventionRejected) RoundFollowUp.NONE else followUp,
            terminalDiagnosticCode = terminalDiagnosticCode
        )
    }

}

private fun isPureLocalRead(call: PlannedToolCall): Boolean =
    call.contract.binding == ToolBinding.TURN_LOCAL && call.contract.effect == ToolEffect.READ_ONLY
