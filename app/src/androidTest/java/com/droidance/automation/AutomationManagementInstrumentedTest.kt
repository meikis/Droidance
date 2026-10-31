// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.memory.MarkdownFileManager
import com.droidance.core.agent.tools.ToolInvocation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith

private const val MINUTE_MS = 60_000L
private const val DAY_MS = 24L * 60L * MINUTE_MS

@RunWith(AndroidJUnit4::class)
class AutomationManagementInstrumentedTest {
    @Test
    fun chatManagementPersistsPrecisionDeduplicatesAndKeepsDeliverySeparate() = runBlocking {
        withSuspendFixture { fixture ->
        val fixedNow = System.currentTimeMillis()
        val tool = AutomationTool(
            fixture.manager,
            fixture.store,
            { TurnEventScope("test-thread", "test-turn") },
            { "Manage tasks" },
            requestTime = { _ -> fixedNow }
        )
        val request = """{"op":"create","title":"Test","instruction":"Read local task files.","cadence":"DAILY","hour":8,"minute":0,"time_zone":"Asia/Tokyo"}"""
        val created = withContext(ToolInvocation("create")) { tool.execute(request) }
        assertTrue(created.outputText, created.isVerifiedSuccess)
        val value = Json.parseToJsonElement(created.outputText).jsonObject
        val id = value.getValue("id").jsonPrimitive.content
        fixture.track(id)
        assertNotNull(value.getValue("alarmPrecision"))
        assertTrue(withContext(ToolInvocation("create")) { tool.execute(request) }.isVerifiedSuccess)
        assertEquals(1, fixture.store.definitions.value.size)
        assertNotNull(AutomationStore(fixture.files).definition(id)?.alarmPrecision)

        val definition = fixture.store.definition(id)!!
        assertTrue(fixture.manager.onAlarm(id, definition.version, definition.nextRunAtMs, definition.nextRunAtMs))
        assertFalse(fixture.manager.onAlarm(id, definition.version, definition.nextRunAtMs, definition.nextRunAtMs))
        val occurrence = fixture.store.claimNextOccurrence()!!
        fixture.store.bindTurn(occurrence.id, "actual-turn")
        fixture.store.completeOccurrence(occurrence.id, AutomationExecutionState.SUCCEEDED, "done", "test result")
        fixture.store.markDeliveryFailed(occurrence.id, "TEST_UNAVAILABLE")
        fixture.store.retryDelivery(occurrence.id)
        assertNull(fixture.store.claimNextOccurrence())
        assertEquals("actual-turn", AutomationStore(fixture.files).occurrences.value.single().turnId)
        assertEquals(AutomationExecutionState.SUCCEEDED, fixture.store.nextDelivery()!!.executionState)
        fixture.manager.setEnabled(id, false)
        assertFalse(fixture.store.definition(id)!!.enabled)
        }
    }

    @Test
    fun onceIsConsumedOnlyOnceAndFinishedStateSurvivesReopen() = withFixture { fixture ->
        val now = System.currentTimeMillis()
        val runAt = now + DAY_MS
        val id = fixture.track("once-${UUID.randomUUID()}")
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.ONCE,
            hour = 0,
            minute = 0,
            timeZoneId = "UTC",
            runAtMs = runAt
        )
        fixture.manager.create("Once", "Run once", false, schedule, nowMs = now, id = id)
        val definition = fixture.store.definition(id)!!

        assertTrue(fixture.store.consumeDeadline(id, definition.version, runAt, runAt, AutomationTrigger.SCHEDULED))
        assertFalse(fixture.store.consumeDeadline(id, definition.version, runAt, runAt + 1L, AutomationTrigger.RECOVERY))
        val reopened = AutomationStore(fixture.files)
        assertEquals(1, reopened.occurrences.value.size)
        assertEquals(0L, reopened.definition(id)!!.nextRunAtMs)
        assertTrue(reopened.definition(id)!!.finishedAtMs > 0L)
        assertFalse(reopened.consumeDeadline(id, definition.version, runAt, runAt + DAY_MS, AutomationTrigger.RECOVERY))
        assertEquals(1, reopened.occurrences.value.size)
    }

    @Test
    fun finishedDailyTriggerLimitCanBeRescheduledWithoutChangingItsTime() = withFixture { fixture ->
        val now = System.currentTimeMillis()
        val id = fixture.track("rearm-daily-${UUID.randomUUID()}")
        val schedule = AutomationSchedule(AutomationCadence.DAILY, 9, 0, timeZoneId = "UTC", maxOccurrences = 1L)
        val original = fixture.manager.create("Daily", "Original instruction", false, schedule, nowMs = now, id = id)
        val deadline = original.nextRunAtMs
        assertTrue(fixture.store.consumeDeadline(id, original.version, deadline, deadline, AutomationTrigger.SCHEDULED))
        val running = fixture.store.claimNextOccurrence(deadline)!!
        assertTrue(fixture.store.definition(id)!!.finishedAtMs > 0L)

        val updated = fixture.manager.update(id, "Daily again", "Updated instruction", schedule, nowMs = deadline + 1L)!!
        assertTrue(updated.enabled)
        assertEquals(0L, updated.finishedAtMs)
        assertEquals(0L, updated.triggeredCount)
        assertEquals(deadline + DAY_MS, updated.nextRunAtMs)
        val retained = fixture.store.occurrences.value.single { it.id == running.id }
        assertEquals(AutomationExecutionState.RUNNING, retained.executionState)
        assertEquals("Original instruction", retained.instructionSnapshot)
        assertFalse(fixture.store.consumeDeadline(id, original.version, deadline, deadline + 2L, AutomationTrigger.RECOVERY))
        assertEquals(updated, AutomationStore(fixture.files).definition(id))
    }

    @Test
    fun expiredOnceCannotBeCreated() = withFixture { fixture ->
        val now = System.currentTimeMillis()
        val id = "expired-${UUID.randomUUID()}"
        val schedule = AutomationSchedule(
            cadence = AutomationCadence.ONCE,
            hour = 0,
            minute = 0,
            timeZoneId = "UTC",
            runAtMs = now - MINUTE_MS
        )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.manager.create("Expired", "Must not run", false, schedule, nowMs = now, id = id)
        }
        assertNull(fixture.store.definition(id))
        assertTrue(AutomationStore(fixture.files).definitions.value.isEmpty())
    }

    @Test
    fun intervalDelayZeroEnqueuesOriginalAnchorWithoutDrift() = withFixture { fixture ->
        val reference = System.currentTimeMillis() + DAY_MS
        val intervalMinutes = 10L
        val schedule = AutomationScheduleInput.parse(
            buildJsonObject {
                put("cadence", "INTERVAL")
                put("delay_minutes", 0)
                put("interval_minutes", intervalMinutes)
                put("time_zone", "UTC")
            },
            reference
        )
        assertEquals(reference, schedule.runAtMs)

        val simulatedNow = reference + 1L
        val id = fixture.track("interval-${UUID.randomUUID()}")
        fixture.manager.create("Interval", "Run repeatedly", false, schedule, nowMs = simulatedNow, id = id)
        val initial = fixture.store.definition(id)!!
        assertEquals(reference, initial.nextRunAtMs)
        assertTrue(fixture.manager.onAlarm(id, initial.version, reference, simulatedNow))
        val afterFirst = fixture.store.definition(id)!!
        assertEquals(reference + intervalMinutes * MINUTE_MS, afterFirst.nextRunAtMs)
        assertEquals(reference, fixture.store.occurrences.value.single().scheduledForMs)

        val secondDeadline = afterFirst.nextRunAtMs
        val delayedNow = secondDeadline + 2L * intervalMinutes * MINUTE_MS + 1L
        assertFalse(fixture.manager.onAlarm(id, afterFirst.version, secondDeadline, delayedNow))
        val afterDelayed = fixture.store.definition(id)!!
        assertEquals(reference + 4L * intervalMinutes * MINUTE_MS, afterDelayed.nextRunAtMs)
        assertEquals(3L, fixture.store.occurrences.value.single().coalescedCount)
    }

    @Test
    fun maxOccurrencesAndEndAtStopFurtherDeadlines() = withFixture { fixture ->
        val base = System.currentTimeMillis() + DAY_MS
        val maxId = fixture.track("max-${UUID.randomUUID()}")
        val maxSchedule = AutomationSchedule(
            cadence = AutomationCadence.INTERVAL,
            hour = 0,
            minute = 0,
            timeZoneId = "UTC",
            runAtMs = base,
            intervalMinutes = 10L,
            maxOccurrences = 2L
        )
        fixture.manager.create("Max", "Limited", false, maxSchedule, nowMs = base - 1L, id = maxId)
        val maxDefinition = fixture.store.definition(maxId)!!
        assertTrue(fixture.manager.onAlarm(maxId, maxDefinition.version, base, base + 3L * MINUTE_MS * 10L))
        val maxAfter = fixture.store.definition(maxId)!!
        assertEquals(2L, maxAfter.triggeredCount)
        assertEquals(0L, maxAfter.nextRunAtMs)
        assertFalse(maxAfter.enabled)
        assertFalse(fixture.store.consumeDeadline(maxId, maxAfter.version, base, base + DAY_MS, AutomationTrigger.RECOVERY))

        val endId = fixture.track("end-${UUID.randomUUID()}")
        val endAt = base + 20L * MINUTE_MS
        val endSchedule = maxSchedule.copy(maxOccurrences = 0L, endAtMs = endAt)
        fixture.store.upsertDefinition(testDefinition(endId, endSchedule, base))
        assertTrue(fixture.store.consumeDeadline(endId, 1, base, endAt + MINUTE_MS, AutomationTrigger.SCHEDULED))
        val endAfter = fixture.store.definition(endId)!!
        assertEquals(3L, endAfter.triggeredCount)
        assertEquals(0L, endAfter.nextRunAtMs)
        assertFalse(endAfter.enabled)
    }

    @Test
    fun missedRunOnceDeadlinesMergeIntoOneQueuedOccurrence() = withFixture { fixture ->
        val base = System.currentTimeMillis() + DAY_MS
        val id = fixture.track("run-once-${UUID.randomUUID()}")
        val schedule = intervalSchedule(base)
        fixture.store.upsertDefinition(testDefinition(id, schedule, base))
        assertTrue(fixture.store.consumeDeadline(id, 1, base, base + 30L * MINUTE_MS + 1L, AutomationTrigger.RECOVERY))
        assertFalse(fixture.store.consumeDeadline(id, 1, base + 40L * MINUTE_MS, base + 60L * MINUTE_MS, AutomationTrigger.RECOVERY))
        val occurrences = fixture.store.occurrences.value.filter { it.automationId == id }
        assertEquals(1, occurrences.size)
        assertEquals(AutomationExecutionState.QUEUED, occurrences.single().executionState)
        assertEquals(6L, occurrences.single().coalescedCount)
        assertEquals(base + 60L * MINUTE_MS, occurrences.single().latestScheduledForMs)
    }

    @Test
    fun missedSkipIsExpiredByDispatchAndByClaim() = withFixture { fixture ->
        val base = System.currentTimeMillis() + DAY_MS
        val dispatchId = fixture.track("dispatch-skip-${UUID.randomUUID()}")
        val schedule = intervalSchedule(base, missedPolicy = AutomationMissedPolicy.SKIP)
        fixture.manager.create("Dispatch skip", "Skip late work", false, schedule, nowMs = base - 1L, id = dispatchId)
        assertEquals(0, fixture.manager.dispatchDue(base + 40L * MINUTE_MS + MINUTE_MS + 1L))
        assertEquals(AutomationExecutionState.SKIPPED, fixture.store.occurrences.value.single { it.automationId == dispatchId }.executionState)

        val claimId = fixture.track("claim-skip-${UUID.randomUUID()}")
        fixture.store.upsertDefinition(testDefinition(claimId, schedule, base))
        assertTrue(fixture.store.consumeDeadline(claimId, 1, base, base + 30_000L, AutomationTrigger.SCHEDULED))
        assertNull(fixture.store.claimNextOccurrence(base + 2L * MINUTE_MS))
        assertEquals(AutomationExecutionState.SKIPPED, fixture.store.occurrences.value.single { it.automationId == claimId }.executionState)
    }

    @Test
    fun oneRunningOccurrenceAllowsOnlyOneQueuedAndMergesLaterDeadlines() = withFixture { fixture ->
        val base = System.currentTimeMillis() + DAY_MS
        val id = fixture.track("running-queue-${UUID.randomUUID()}")
        val schedule = intervalSchedule(base)
        fixture.store.upsertDefinition(testDefinition(id, schedule, base))
        assertTrue(fixture.store.consumeDeadline(id, 1, base, base, AutomationTrigger.SCHEDULED))
        val running = fixture.store.claimNextOccurrence(base)!!
        assertEquals(AutomationExecutionState.RUNNING, running.executionState)
        val secondDeadline = base + 10L * MINUTE_MS
        assertTrue(fixture.store.consumeDeadline(id, 1, secondDeadline, secondDeadline, AutomationTrigger.SCHEDULED))
        val thirdDeadline = base + 20L * MINUTE_MS
        assertFalse(fixture.store.consumeDeadline(id, 1, thirdDeadline, thirdDeadline, AutomationTrigger.SCHEDULED))
        val occurrences = fixture.store.occurrences.value.filter { it.automationId == id }
        assertEquals(2, occurrences.size)
        assertEquals(1, occurrences.count { it.executionState == AutomationExecutionState.RUNNING })
        val queued = occurrences.single { it.executionState == AutomationExecutionState.QUEUED }
        assertEquals(1L, queued.coalescedCount)
        assertEquals(thirdDeadline, queued.latestScheduledForMs)
    }

    @Test
    fun editCancelsQueuedButKeepsRunningInstructionSnapshotAndPauseCancelsOldQueue() = withFixture { fixture ->
        val base = System.currentTimeMillis() + DAY_MS
        val id = fixture.track("edit-pause-${UUID.randomUUID()}")
        val schedule = intervalSchedule(base)
        fixture.manager.create("Original", "Original instruction", false, schedule, nowMs = base - 1L, id = id)
        val first = fixture.store.definition(id)!!
        assertTrue(fixture.manager.onAlarm(id, first.version, base, base))
        val firstQueued = fixture.store.occurrences.value.single { it.executionState == AutomationExecutionState.QUEUED }

        val edited = fixture.manager.update(id, "Edited", "Edited instruction", schedule, nowMs = base + 1L)!!
        assertEquals(AutomationExecutionState.CANCELLED, fixture.store.occurrences.value.single { it.id == firstQueued.id }.executionState)
        assertEquals("PLAN_CHANGED", fixture.store.occurrences.value.single { it.id == firstQueued.id }.failureCode)

        assertTrue(fixture.manager.onAlarm(id, edited.version, edited.nextRunAtMs, edited.nextRunAtMs))
        val running = fixture.store.claimNextOccurrence(edited.nextRunAtMs)!!
        assertEquals("Edited instruction", running.instructionSnapshot)
        val runningId = running.id
        val next = fixture.store.definition(id)!!.nextRunAtMs
        assertTrue(fixture.manager.onAlarm(id, fixture.store.definition(id)!!.version, next, next))
        val queuedBeforePause = fixture.store.occurrences.value.single {
            it.automationId == id && it.executionState == AutomationExecutionState.QUEUED
        }

        fixture.manager.update(id, "Latest", "Latest instruction", schedule, nowMs = next + 1L)
        assertEquals(AutomationExecutionState.CANCELLED, fixture.store.occurrences.value.single { it.id == queuedBeforePause.id }.executionState)
        assertEquals("Edited instruction", fixture.store.occurrences.value.single { it.id == runningId }.instructionSnapshot)
        assertEquals(AutomationExecutionState.RUNNING, fixture.store.occurrences.value.single { it.id == runningId }.executionState)

        val afterEdit = fixture.store.definition(id)!!
        assertTrue(fixture.manager.onAlarm(id, afterEdit.version, afterEdit.nextRunAtMs, afterEdit.nextRunAtMs))
        val queuedBeforeDisable = fixture.store.occurrences.value.single {
            it.automationId == id && it.executionState == AutomationExecutionState.QUEUED
        }
        fixture.manager.setEnabled(id, false, nowMs = afterEdit.nextRunAtMs + 1L)
        assertEquals(AutomationExecutionState.CANCELLED, fixture.store.occurrences.value.single { it.id == queuedBeforeDisable.id }.executionState)
        assertEquals("Edited instruction", fixture.store.occurrences.value.single { it.id == runningId }.instructionSnapshot)
        assertFalse(fixture.store.definition(id)!!.enabled)
    }

    @Test
    fun markdownStateRoundTripsDefinitionsAndOccurrencesAfterReopen() = withFixture { fixture ->
        val base = System.currentTimeMillis() + DAY_MS
        val id = fixture.track("roundtrip-${UUID.randomUUID()}")
        val schedule = intervalSchedule(base)
        val definition = testDefinition(id, schedule, base).copy(
            title = "Round trip",
            instruction = "Persist exact state"
        )
        fixture.store.upsertDefinition(definition)
        fixture.store.consumeDeadline(id, definition.version, base, base, AutomationTrigger.SCHEDULED)
        assertTrue(fixture.files.readFile("automations/state.json")!!.contains(id))

        val reopened = AutomationStore(fixture.files)
        assertEquals(definition.copy(nextRunAtMs = base + 10L * MINUTE_MS, triggeredCount = 1L), reopened.definition(id))
        assertEquals(fixture.store.occurrences.value, reopened.occurrences.value)
    }

    @Test
    fun failedWriteDoesNotPublishHalfState() = withFixture { fixture ->
        val blockedDirectory = File(fixture.root, "no-backup/droidance-data/automations")
        blockedDirectory.parentFile!!.mkdirs()
        blockedDirectory.writeText("blocked")
        val now = System.currentTimeMillis()
        val id = "failed-write-${UUID.randomUUID()}"
        val definition = testDefinition(id, intervalSchedule(now + DAY_MS), now)

        assertThrows(Exception::class.java) { fixture.store.upsertDefinition(definition) }
        assertTrue(fixture.store.definitions.value.isEmpty())
        assertTrue(fixture.store.occurrences.value.isEmpty())
        assertEquals(0L, fixture.store.changeVersion.value)
        assertTrue(AutomationStore(fixture.files).definitions.value.isEmpty())
    }

    @Test
    fun historyTrimRetainsRunningAndQueuedActiveOccurrences() = withFixture { fixture ->
        val base = System.currentTimeMillis() + DAY_MS
        val id = fixture.track("history-${UUID.randomUUID()}")
        val schedule = intervalSchedule(base, intervalMinutes = 1L)
        fixture.store.upsertDefinition(testDefinition(id, schedule, base))
        repeat(250) {
            val current = fixture.store.definition(id)!!
            val scheduled = current.nextRunAtMs
            assertTrue(fixture.store.consumeDeadline(id, current.version, scheduled, scheduled, AutomationTrigger.SCHEDULED))
            val occurrence = fixture.store.claimNextOccurrence(scheduled)!!
            fixture.store.completeOccurrence(
                occurrence.id,
                AutomationExecutionState.SUCCEEDED,
                "done",
                "history $it",
                completedAtMs = scheduled
            )
            fixture.store.markDeliveryAccepted(occurrence.id, scheduled)
        }

        val runningDeadline = fixture.store.definition(id)!!.nextRunAtMs
        assertTrue(fixture.store.consumeDeadline(id, 1, runningDeadline, runningDeadline, AutomationTrigger.SCHEDULED))
        val running = fixture.store.claimNextOccurrence(runningDeadline)!!
        val queuedDeadline = fixture.store.definition(id)!!.nextRunAtMs
        assertTrue(fixture.store.consumeDeadline(id, 1, queuedDeadline, queuedDeadline, AutomationTrigger.SCHEDULED))

        val retained = fixture.store.occurrences.value.filter { it.automationId == id }
        assertTrue(retained.any { it.id == running.id && it.executionState == AutomationExecutionState.RUNNING })
        assertTrue(retained.any { it.executionState == AutomationExecutionState.QUEUED })
        assertTrue(retained.count {
            it.executionState != AutomationExecutionState.RUNNING && it.executionState != AutomationExecutionState.QUEUED &&
                it.deliveryState == AutomationDeliveryState.HANDOFF_ACCEPTED
        } <= 240)
    }

    private fun intervalSchedule(
        runAtMs: Long,
        intervalMinutes: Long = 10L,
        missedPolicy: AutomationMissedPolicy = AutomationMissedPolicy.RUN_ONCE
    ) = AutomationSchedule(
        cadence = AutomationCadence.INTERVAL,
        hour = 0,
        minute = 0,
        timeZoneId = "UTC",
        runAtMs = runAtMs,
        intervalMinutes = intervalMinutes,
        missedPolicy = missedPolicy
    )

    private fun testDefinition(id: String, schedule: AutomationSchedule, nowMs: Long) = AutomationDefinition(
        id = id,
        version = 1,
        title = "Test $id",
        instruction = "Instruction $id",
        titleWasGenerated = false,
        schedule = schedule,
        nextRunAtMs = schedule.runAtMs,
        createdAtMs = nowMs,
        updatedAtMs = nowMs
    )

    private fun <T> withFixture(block: (Fixture) -> T): T {
        val fixture = fixture()
        return try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private suspend fun <T> withSuspendFixture(block: suspend (Fixture) -> T): T {
        val fixture = fixture()
        return try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun fixture(): Fixture {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "automation-instrumented-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
        }
        val files = MarkdownFileManager(context)
        val store = AutomationStore(files)
        val scheduler = AutomationAlarmScheduler(context)
        return Fixture(root, files, store, scheduler, AutomationManager(store, scheduler))
    }

    private class Fixture(
        val root: File,
        val files: MarkdownFileManager,
        val store: AutomationStore,
        val scheduler: AutomationAlarmScheduler,
        val manager: AutomationManager
    ) {
        private val ids = linkedSetOf<String>()

        fun track(id: String): String {
            ids += id
            return id
        }

        fun close() {
            ids.forEach { runCatching { manager.delete(it) } }
            root.deleteRecursively()
        }
    }
}
