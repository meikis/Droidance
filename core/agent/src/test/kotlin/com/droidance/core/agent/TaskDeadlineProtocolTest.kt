// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.LLMStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskDeadlineProtocolTest {
    private fun call(value: String) = ToolCall("one", "android_act", """{"op":"open","task_deadline":$value}""")
    @Test fun durationIsAnchoredToTheHostMessageAndRemovedFromEffectArguments() {
        val r = TaskDeadlineProtocol.extract(listOf(call("""{"duration_seconds":120}""")))
        assertEquals(121_000L, r.bound!!.at(1_000L))
        assertFalse(r.calls.single().arguments.contains("task_deadline"))
    }
    @Test fun explicitOffsetAndIdenticalMetadataAcrossCallsAreAccepted() {
        val c = call("""{"deadline_at":"2026-10-12T08:00:00+08:00"}""")
        assertEquals(java.time.Instant.parse("2026-10-12T00:00:00Z").toEpochMilli(),
            TaskDeadlineProtocol.extract(listOf(c, c.copy(id = "two"))).bound!!.at(0L))
    }
    @Test fun invalidOrConflictingBudgetsCannotSilentlyBecomeUnbounded() {
        for (v in listOf("{}", "null", """{"duration_seconds":"120"}""", """{"duration_seconds":0}""",
            """{"duration_seconds":1,"deadline_at":"2026-10-12T08:00:00Z"}""",
            """{"deadline_at":"2026-10-12T08:00:00"}""", """{"duration_seconds":9223372036854775807}""")) {
            assertThrows("Rejected: $v", Exception::class.java) { TaskDeadlineProtocol.extract(listOf(call(v))) }
        }
        assertThrows(Exception::class.java) { TaskDeadlineProtocol.extract(listOf(
            call("""{"duration_seconds":10}"""), call("""{"duration_seconds":20}"""))) }
    }
    @Test fun firstOrdinaryResponseBindsBeforeEffectsAndAnExpiredBudgetWithholdsLaterEffects() = runBlocking {
        val f = TaskCoordinatorTestFixture()
        val t = f.startTurn("early-deadline", "Work within ten seconds")
        f.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0,"first","android_act",
            """{"op":"launch","package_name":"example.target","task_deadline":{"duration_seconds":10}}"""))
        f.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0,"late","android_act",
            """{"op":"launch","package_name":"example.other"}"""))
        var deadline: Long? = null
        var now = 1_000L
        var decisions = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                f.coordinator.coordinateTask(
taskGoal="Work within ten seconds",
additionalContext="",
durableTurn=t.active,
turnContext="",
expectedBackend=t.expectedBackend,
executionStrategy=t.executionStrategy,
mode=t.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val onTaskDeadline: suspend (TaskDeadlineProtocol.Bound) -> Unit = { bound -> assertTrue(f.toolExecutor.deviceCalls.isEmpty());deadline=bound.at(1_000L) }
    override val checkTaskDeadline: () -> Unit = { if (deadline?.let { now >= it } == true) throw CancellationException("TASK_DEADLINE_REACHED") }
    override val roundContext: () -> String = { if (++decisions == 2) now=20_000L; "host_clock=$now" }
}
)
            }
        }
        assertEquals(11_000L,deadline)
        assertEquals(1,f.toolExecutor.deviceCalls.count { it.name=="android_act" })
        assertTrue(f.toolExecutor.deviceCalls.none { it.arguments.contains("task_deadline") })
    }
}
