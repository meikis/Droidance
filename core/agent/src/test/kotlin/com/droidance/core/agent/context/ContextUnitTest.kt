// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.harness.PlannedToolCall
import com.droidance.core.agent.harness.ProviderToolCall
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.harness.RegisteredToolContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextUnitTest {
    @Test
    fun `builds one indivisible unit per contiguous model round`() {
        val items = listOf(
            user(1, "round-1"),
            assistant(2, "round-1"),
            call(3, "round-1", "call-1", 0),
            call(4, "round-1", "call-2", 1),
            result(5, "round-1", "call-2"),
            result(6, "round-1", "call-1"),
            observation(7, "round-1", "call-1"),
            assistant(8, "round-2", PublicMessageKind.FINAL)
        )

        val units = ContextUnitBuilder.build(items)

        assertEquals(listOf("round-1", "round-2"), units.map(ContextUnit::roundId))
        assertEquals(1L, units.first().startSequence)
        assertEquals(7L, units.first().endSequence)
        assertEquals((1L..7L).toList(), units.first().items.map(AgentItem::sequence))
    }

    @Test
    fun `copies mutable structured result data when a unit is built`() {
        val metrics = mutableMapOf("latencyMs" to 10L)
        val sourceResult = result(4, "round-1", "call-1", metrics)
        val unit = ContextUnitBuilder.build(
            listOf(user(1, "round-1"), assistant(2, "round-1"), call(3, "round-1", "call-1", 0), sourceResult)
        ).single()

        metrics["latencyMs"] = 99L

        val stored = unit.items.filterIsInstance<ToolResultAgentItem>().single()
        assertEquals(10L, stored.result.metrics.getValue("latencyMs"))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (stored.result.metrics as MutableMap<String, Long>)["other"] = 1L
        }
    }

    @Test
    fun `snapshots preserve a query contract barrier`() {
        val sourceCall = call(
            sequence = 2,
            roundId = "round-1",
            callId = "call-1",
            providerOrder = 0,
            requiresResultBeforeAction = true
        )
        val unit = ContextUnitBuilder.build(
            listOf(
                user(1, "round-1"),
                sourceCall,
                result(3, "round-1", "call-1")
            )
        ).single()

        val storedCall = unit.items.filterIsInstance<ToolCallAgentItem>().single().call
        assertTrue(storedCall.contract.requiresResultBeforeAction)
        assertEquals(sourceCall.call.contract, storedCall.contract)
    }

    @Test
    fun `rejects non increasing or duplicate item identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(listOf(user(2, "round-1"), assistant(1, "round-1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(user(1, "round-1", itemId = "same"), assistant(2, "round-1", itemId = "same"))
            )
        }
    }

    @Test
    fun `rejects a round that reappears after another round`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(
                    user(1, "round-1"),
                    assistant(2, "round-1"),
                    user(3, "round-2"),
                    assistant(4, "round-2"),
                    user(5, "round-1"),
                    assistant(6, "round-1")
                )
            )
        }
    }

    @Test
    fun `rejects missing orphaned and duplicate tool results`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(user(1, "round"), call(2, "round", "call-1", 0))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(user(1, "round"), assistant(2, "round"), result(3, "round", "orphan"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(
                    user(1, "round"),
                    call(2, "round", "call-1", 0),
                    result(3, "round", "call-1"),
                    result(4, "round", "call-1")
                )
            )
        }
    }

    @Test
    fun `rejects a harness callId reused by another round`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(
                    user(1, "round-1"),
                    call(2, "round-1", "shared-call", 0),
                    result(3, "round-1", "shared-call"),
                    user(4, "round-2"),
                    call(5, "round-2", "shared-call", 0),
                    result(6, "round-2", "shared-call")
                )
            )
        }
    }

    @Test
    fun `rejects illegal role ordering inside a round`() {
        val invalidStreams = listOf(
            listOf(user(1, "round"), assistant(2, "round"), user(3, "round")),
            listOf(user(1, "round"), call(2, "round", "call-1", 0), assistant(3, "round"), result(4, "round", "call-1")),
            listOf(user(1, "round"), call(2, "round", "call-1", 0), result(3, "round", "call-1"), call(4, "round", "call-2", 1), result(5, "round", "call-2"))
        )

        invalidStreams.forEach { stream ->
            assertThrows(IllegalArgumentException::class.java) {
                ContextUnitBuilder.build(stream)
            }
        }
    }

    @Test
    fun `requires provider order while allowing parallel results to settle out of order`() {
        ContextUnitBuilder.build(
            listOf(
                user(1, "round"),
                call(2, "round", "call-1", 0),
                call(3, "round", "call-2", 1),
                result(4, "round", "call-2"),
                result(5, "round", "call-1")
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(
                    user(1, "round"),
                    call(2, "round", "call-2", 1),
                    call(3, "round", "call-1", 0),
                    result(4, "round", "call-2"),
                    result(5, "round", "call-1")
                )
            )
        }
    }

    @Test
    fun `requires a user anchor while allowing later rounds without new user input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(listOf(assistant(1, "round-1")))
        }

        val units = ContextUnitBuilder.build(
            listOf(
                user(1, "round-1"), assistant(2, "round-1"),
                assistant(3, "round-2", PublicMessageKind.PROGRESS),
                call(4, "round-2", "call-1", 0),
                result(5, "round-2", "call-1"),
                assistant(6, "round-3", PublicMessageKind.FINAL)
            )
        )
        assertEquals(3, units.size)
    }

    @Test
    fun `requires observation references to stay with a closed call in the same unit`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(
                    user(1, "round"),
                    call(2, "round", "call-1", 0),
                    result(3, "round", "call-1"),
                    observation(4, "round", "another-call")
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitBuilder.build(
                listOf(
                    user(1, "round"),
                    call(2, "round", "call-1", 0),
                    observation(3, "round", "call-1"),
                    result(4, "round", "call-1")
                )
            )
        }
    }

    @Test
    fun `protecting one item protects its whole unit`() {
        val units = threeTextUnits()

        val protected = ContextUnitProtector.protect(units, setOf(2L))

        assertTrue(protected.first().isProtected)
        assertEquals(setOf(2L), protected.first().protectedItemSequences)
        assertEquals(2, protected.first().unit.items.size)
        assertTrue(protected.drop(1).none(ProtectedContextUnit::isProtected))
    }

    @Test
    fun `rejects a protection reference that is absent from the projected units`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextUnitProtector.protect(threeTextUnits(), setOf(999L))
        }
    }

    @Test
    fun `summarized units are replaced while protocol anchors remain`() {
        val units = fiveTextUnits()
        val protected = ContextUnitProtector.protect(units, setOf(3L, 9L))

        val plan = ContextUnitDropSelector.selectSummarized(protected)

        assertEquals(listOf("round-1", "round-3", "round-4"), plan.dropped.map(ContextUnit::roundId))
        assertEquals(listOf("round-2", "round-5"), plan.retained.map(ContextUnit::roundId))
    }

    @Test
    fun `all summarized units can be replaced`() {
        val units = fiveTextUnits()
        val plan = ContextUnitDropSelector.selectSummarized(
            ContextUnitProtector.protect(units, emptySet())
        )

        assertEquals(listOf("round-1", "round-2", "round-3", "round-4", "round-5"), plan.dropped.map(ContextUnit::roundId))
        assertEquals(emptyList<String>(), plan.retained.map(ContextUnit::roundId))
    }

    @Test
    fun `drops the only eligible unit and makes no selection when all are protected`() {
        val units = threeTextUnits()
        val oneEligible = ContextUnitProtector.protect(units, setOf(1L, 5L))
        val onePlan = ContextUnitDropSelector.selectSummarized(oneEligible)
        assertEquals(listOf("round-2"), onePlan.dropped.map(ContextUnit::roundId))

        val allProtected = ContextUnitProtector.protect(units, setOf(1L, 3L, 5L))
        val nonePlan = ContextUnitDropSelector.selectSummarized(allProtected)
        assertTrue(nonePlan.dropped.isEmpty())
        assertEquals(units.map(ContextUnit::roundId), nonePlan.retained.map(ContextUnit::roundId))
    }

    @Test
    fun `selection is deterministic for the same unit projection and protection set`() {
        val protected = ContextUnitProtector.protect(fiveTextUnits(), setOf(5L))

        val first = ContextUnitDropSelector.selectSummarized(protected)
        val second = ContextUnitDropSelector.selectSummarized(protected)

        assertEquals(first.dropped.map(ContextUnit::startSequence), second.dropped.map(ContextUnit::startSequence))
        assertEquals(first.retained.map(ContextUnit::startSequence), second.retained.map(ContextUnit::startSequence))
    }

    private fun threeTextUnits(): List<ContextUnit> = ContextUnitBuilder.build(
        listOf(
            user(1, "round-1"), assistant(2, "round-1"),
            user(3, "round-2"), assistant(4, "round-2"),
            user(5, "round-3"), assistant(6, "round-3")
        )
    )

    private fun fiveTextUnits(): List<ContextUnit> = ContextUnitBuilder.build(
        (1..5).flatMap { index ->
            val firstSequence = index * 2L - 1L
            listOf(
                user(firstSequence, "round-$index"),
                assistant(firstSequence + 1L, "round-$index")
            )
        }
    )

    private fun user(
        sequence: Long,
        roundId: String,
        itemId: String = "item-$sequence"
    ) = UserInputAgentItem(itemId, sequence, roundId, UserInputKind.MESSAGE, "request-$sequence")

    private fun assistant(
        sequence: Long,
        roundId: String,
        kind: PublicMessageKind = PublicMessageKind.START,
        itemId: String = "item-$sequence"
    ) = AssistantOutputAgentItem(itemId, sequence, roundId, kind, "reply-$sequence")

    private fun call(
        sequence: Long,
        roundId: String,
        callId: String,
        providerOrder: Int,
        requiresResultBeforeAction: Boolean = false
    ): ToolCallAgentItem {
        val name = "tool-$callId"
        return ToolCallAgentItem(
            itemId = "item-$sequence",
            sequence = sequence,
            roundId = roundId,
            call = PlannedToolCall(
                providerOrder = providerOrder,
                callId = callId,
                providerCall = ProviderToolCall(callId, name, "{}"),
                contract = RegisteredToolContract(
                    name,
                    "$name description",
                    ToolBinding.TURN_LOCAL,
                    ToolEffect.READ_ONLY,
                    emptyMap(),
                    requiresResultBeforeAction = requiresResultBeforeAction
                )
            )
        )
    }

    private fun result(
        sequence: Long,
        roundId: String,
        callId: String,
        metrics: Map<String, Long> = emptyMap()
    ) = ToolResultAgentItem(
        itemId = "item-$sequence",
        sequence = sequence,
        roundId = roundId,
        callId = callId,
        result = ToolExecutionResult(
            outputText = "result",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
            metrics = metrics
        )
    )

    private fun observation(sequence: Long, roundId: String, callId: String) = ObservationRefAgentItem(
        itemId = "item-$sequence",
        sequence = sequence,
        roundId = roundId,
        observationId = "observation-$sequence",
        content = TextObservationContentRef("sha256:observation-$sequence"),
        callId = callId
    )
}
