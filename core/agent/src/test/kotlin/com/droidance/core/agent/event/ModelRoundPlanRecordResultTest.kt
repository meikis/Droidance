// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRoundPlanRecordResultTest {
    @Test
    fun newly_committed_plan_execution_can_be_claimed_only_once_concurrently() {
        val recorded = recordedPlan()
        val fresh = NewlyCommittedModelRoundPlan(recorded)
        val workerCount = 16
        val barrier = CyclicBarrier(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val claims = (0 until workerCount).map {
                executor.submit<Result<RecordedModelRoundPlan>> {
                    barrier.await()
                    runCatching { fresh.claimForExecution() }
                }
            }.map { it.get() }

            assertEquals(1, claims.count { it.isSuccess })
            assertSame(recorded, claims.single { it.isSuccess }.getOrThrow())
            assertTrue(
                claims.filter { it.isFailure }.all {
                    it.exceptionOrNull() is IllegalStateException
                }
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun recordedPlan(): RecordedModelRoundPlan = RecordedModelRoundPlan(
        completed = AgentEventEnvelope(
            schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
            eventId = "round-completed",
            eventKind = AgentEventKind.MODEL_ROUND_COMPLETED,
            sequence = 1L,
            occurredAt = 1L,
            threadId = "thread",
            turnId = "turn",
            roundId = "round",
            attemptId = null,
            executionId = null,
            callId = null,
            payloadHash = "0".repeat(64),
            payloadJson = "{}",
            idempotencyKey = "round:completed"
        ),
        toolCalls = emptyList(),
        publicEvent = null,
        attemptCreated = null,
        outcomes = emptyList(),
        starts = emptyList(),
        interventionRequests = emptyList()
    )
}
