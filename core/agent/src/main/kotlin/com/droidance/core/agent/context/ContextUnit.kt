// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import java.util.Collections

/** A complete ModelRound protocol group that context compression cannot split. */
class ContextUnit internal constructor(
    val roundId: String,
    items: List<AgentItem>
) {
    val items: List<AgentItem> = immutableList(items)
    val startSequence: Long = this.items.first().sequence
    val endSequence: Long = this.items.last().sequence

    init {
        require(roundId.isNotBlank()) { "ContextUnit roundId cannot be blank" }
        require(this.items.isNotEmpty()) { "ContextUnit cannot be empty" }
        require(this.items.all { it.roundId == roundId }) {
            "ContextUnit cannot contain items from another round"
        }
    }

    fun containsItemSequence(sequence: Long): Boolean = items.any { it.sequence == sequence }
}

class ProtectedContextUnit internal constructor(
    val unit: ContextUnit,
    protectedItemSequences: Set<Long>
) {
    val protectedItemSequences: Set<Long> = immutableSet(protectedItemSequences)
    val isProtected: Boolean get() = protectedItemSequences.isNotEmpty()
}

class ContextUnitDropPlan internal constructor(
    dropped: List<ContextUnit>,
    retained: List<ContextUnit>
) {
    val dropped: List<ContextUnit> = immutableList(dropped)
    val retained: List<ContextUnit> = immutableList(retained)
}

object ContextUnitBuilder {
    fun build(sortedItems: List<AgentItem>): List<ContextUnit> {
        if (sortedItems.isEmpty()) return emptyList()

        validateSortedInput(sortedItems)
        val snapshots = sortedItems.map(AgentItem::snapshot)
        val groups = mutableListOf<List<AgentItem>>()
        var currentRoundId = snapshots.first().roundId
        var current = mutableListOf<AgentItem>()
        val closedRoundIds = mutableSetOf<String>()

        snapshots.forEach { item ->
            if (item.roundId != currentRoundId) {
                closedRoundIds += currentRoundId
                require(item.roundId !in closedRoundIds) {
                    "Context items for round ${item.roundId} must be contiguous"
                }
                groups += current
                current = mutableListOf()
                currentRoundId = item.roundId
            }
            current += item
        }
        groups += current

        val units = groups.map { items ->
            validateUnit(items)
            ContextUnit(items.first().roundId, items)
        }
        validateCrossUnitRoles(units)
        validateGlobalCallIds(units)
        return immutableList(units)
    }

    private fun validateSortedInput(items: List<AgentItem>) {
        val itemIds = mutableSetOf<String>()
        items.forEachIndexed { index, item ->
            require(itemIds.add(item.itemId)) { "Agent itemIds must be unique" }
            if (index > 0) {
                require(items[index - 1].sequence < item.sequence) {
                    "Agent items must have unique, strictly increasing sequences"
                }
            }
        }
    }

    private fun validateUnit(items: List<AgentItem>) {
        var phase = UnitPhase.USER_INPUT
        items.forEach { item ->
            phase = when (item) {
                is UserInputAgentItem -> {
                    require(phase == UnitPhase.USER_INPUT) {
                        "User input must precede assistant output and tool protocol items"
                    }
                    phase
                }
                is AssistantOutputAgentItem -> {
                    require(phase <= UnitPhase.ASSISTANT_OUTPUT) {
                        "Assistant output must precede tool calls and results"
                    }
                    UnitPhase.ASSISTANT_OUTPUT
                }
                is ToolCallAgentItem -> {
                    require(phase <= UnitPhase.TOOL_CALL) {
                        "All tool calls must precede their results"
                    }
                    UnitPhase.TOOL_CALL
                }
                is ToolResultAgentItem -> {
                    require(phase == UnitPhase.TOOL_CALL || phase == UnitPhase.TOOL_RESULT) {
                        "Tool results must follow the round tool calls"
                    }
                    UnitPhase.TOOL_RESULT
                }
                is ObservationRefAgentItem -> {
                    require(phase == UnitPhase.TOOL_RESULT || phase == UnitPhase.OBSERVATION) {
                        "Observation refs must follow settled or rejected tool results"
                    }
                    UnitPhase.OBSERVATION
                }
            }
        }

        val calls = items.filterIsInstance<ToolCallAgentItem>()
        val results = items.filterIsInstance<ToolResultAgentItem>()
        require(items.any { it is AssistantOutputAgentItem } || calls.isNotEmpty()) {
            "A complete ContextUnit requires model output"
        }

        val callsById = calls.associateBy { it.call.callId }
        require(callsById.size == calls.size) { "Tool callIds must be unique within a ContextUnit" }
        require(calls.zipWithNext().all { (before, after) ->
            before.call.providerOrder < after.call.providerOrder
        }) { "Tool calls must preserve provider order" }

        val resultsByCallId = results.groupBy(ToolResultAgentItem::callId)
        require(resultsByCallId.values.all { it.size == 1 }) {
            "Each tool call must have exactly one settled or rejected result"
        }
        require(resultsByCallId.keys == callsById.keys) {
            val missing = callsById.keys - resultsByCallId.keys
            val orphaned = resultsByCallId.keys - callsById.keys
            "Tool protocol must be closed; missing=$missing orphaned=$orphaned"
        }

        items.filterIsInstance<ObservationRefAgentItem>().forEach { observation ->
            require(observation.callId == null || observation.callId in callsById) {
                "Observation ref points to a tool call outside its ContextUnit"
            }
        }
    }

    private fun validateCrossUnitRoles(units: List<ContextUnit>) {
        require(units.first().items.first() is UserInputAgentItem) {
            "The first ContextUnit must start with user input"
        }
    }

    private fun validateGlobalCallIds(units: List<ContextUnit>) {
        val callIds = units.flatMap { unit ->
            unit.items.filterIsInstance<ToolCallAgentItem>().map { it.call.callId }
        }
        require(callIds.toSet().size == callIds.size) {
            "Harness callIds must be globally unique across ContextUnits"
        }
    }

    private enum class UnitPhase {
        USER_INPUT,
        ASSISTANT_OUTPUT,
        TOOL_CALL,
        TOOL_RESULT,
        OBSERVATION
    }
}

object ContextUnitProtector {
    fun protect(
        sortedUnits: List<ContextUnit>,
        protectedItemSequences: Set<Long>
    ): List<ProtectedContextUnit> {
        validateSortedUnits(sortedUnits)
        val knownSequences = sortedUnits.flatMap { unit -> unit.items.map(AgentItem::sequence) }.toSet()
        val unknownSequences = protectedItemSequences - knownSequences
        require(unknownSequences.isEmpty()) {
            "Protected item sequences do not exist in the ContextUnits: $unknownSequences"
        }
        return immutableList(sortedUnits.map { unit ->
            ProtectedContextUnit(
                unit = unit,
                protectedItemSequences = protectedItemSequences.filterTo(mutableSetOf()) {
                    unit.containsItemSequence(it)
                }
            )
        })
    }
}

object ContextTaskStateSelector {
    /** A typed public progress is preferred; otherwise the latest closed tool result preserves
     * the current actionable outcome without promoting an ordinary public reply into task state. */
    fun latestClosedTaskState(sortedUnits: List<ContextUnit>): AgentItem? {
        validateSortedUnits(sortedUnits)
        val items = sortedUnits.asSequence()
            .flatMap { it.items.asSequence() }
            .toList()
        return items.asSequence()
            .filterIsInstance<AssistantOutputAgentItem>()
            .filter { it.kind in setOf(com.droidance.core.agent.harness.PublicMessageKind.PROGRESS,
                com.droidance.core.agent.harness.PublicMessageKind.STAGE_RESULT) }
            .maxByOrNull(AssistantOutputAgentItem::sequence)
            ?: items.filterIsInstance<ToolResultAgentItem>().maxByOrNull(ToolResultAgentItem::sequence)
    }
}

object ContextUnitDropSelector {
    /** Replaces closed history with its model-authored summary; protocol anchors stay intact. */
    fun selectSummarized(sortedUnits: List<ProtectedContextUnit>): ContextUnitDropPlan {
        validateSortedUnits(sortedUnits.map(ProtectedContextUnit::unit))
        val eligible = sortedUnits.filterNot(ProtectedContextUnit::isProtected)
        val dropped = eligible.map(ProtectedContextUnit::unit)
        val droppedRoundIds = dropped.map(ContextUnit::roundId).toSet()
        val retained = sortedUnits.map(ProtectedContextUnit::unit)
            .filterNot { it.roundId in droppedRoundIds }
        return ContextUnitDropPlan(dropped, retained)
    }
}

private fun validateSortedUnits(units: List<ContextUnit>) {
    units.zipWithNext().forEach { (before, after) ->
        require(before.endSequence < after.startSequence) {
            "ContextUnits must be sorted and non-overlapping"
        }
    }
    require(units.map(ContextUnit::roundId).toSet().size == units.size) {
        "ContextUnit roundIds must be unique"
    }
}

private fun AgentItem.snapshot(): AgentItem = when (this) {
    is UserInputAgentItem -> copy()
    is AssistantOutputAgentItem -> copy()
    is ToolCallAgentItem -> copy(
        call = call.copy(
            providerCall = call.providerCall.copy(),
            contract = com.droidance.core.agent.harness.RegisteredToolContract(
                name = call.contract.name,
                description = call.contract.description,
                binding = call.contract.binding,
                effect = call.contract.effect,
                parameters = call.contract.parameters,
                requiresResultBeforeAction = call.contract.requiresResultBeforeAction
            )
        )
    )
    is ToolResultAgentItem -> copy(
        result = result.copy(metrics = immutableMap(result.metrics))
    )
    is ObservationRefAgentItem -> copy(
        content = when (val ref = content) {
            is TextObservationContentRef -> ref.copy()
            is ScreenshotObservationContentRef -> ref.copy()
        }
    )
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
