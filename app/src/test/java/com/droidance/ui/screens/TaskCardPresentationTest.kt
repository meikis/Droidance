// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.screens.home.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCardPresentationTest {
    @Test
    fun statusPresentationOnlyTracksRuntimeLifecycle() {
        assertEquals("RUNNING", effectiveTaskStatusName("RUNNING", isPaused = false, isStreaming = true))
        assertEquals("PAUSED", effectiveTaskStatusName("RUNNING", isPaused = true, isStreaming = true))
        assertEquals("UNFINISHED", effectiveTaskStatusName("RUNNING", isPaused = false, isStreaming = false))
        assertEquals("PAUSED", effectiveTaskStatusName("DONE", isPaused = true, isStreaming = false))
        assertEquals("DONE", effectiveTaskStatusName("DONE", isPaused = false, isStreaming = false))
    }

    @Test
    fun timestampFallsBackToStoredLabelWhenEpochIsMissing() {
        assertEquals("12:34", formatMessageTimestamp(0L, "12:34", nowMs = 1_000L))
    }

    @Test
    fun chatAndTakeoverCardsUseOnlyEventStorePresentation() {
        val source = File("src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt").readText()

        assertTrue(source.contains("private fun EventStoreTaskCardBubble("))
        assertTrue(source.contains("turnPresentations: List<TurnPresentationUiState>"))
        assertTrue(source.contains("ExecutionProcessHistory("))
        assertFalse(source.contains("ExecutionEvent"))
        assertFalse(source.contains("private fun TaskCardBubble("))
        assertFalse(source.contains("projectedTaskCard("))
        assertFalse(source.contains("parseMessageSections("))
    }

    @Test
    fun appTakeoverKeepsVirtualSpaceAndStopControls() {
        val source = File("src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt").readText()
        val takeover = source.substringAfter("internal fun AppTakeoverLogCard(")
            .substringBefore("private fun TakeoverActionBlock(")

        assertTrue(takeover.contains("ExecutionLogControls("))
        assertTrue(takeover.contains("onOpenVirtualSpace = onOpenVirtualSpace"))
        assertTrue(takeover.contains("onStop = onStop"))
    }

    @Test
    fun processTickerStopsDuringPauseAndRunsOnlyWhileExecuting() {
        assertTrue(isProcessTickerActive("RUNNING", startedAtMs = 10_000L, isStreaming = true))
        assertFalse(isProcessTickerActive("PAUSED", startedAtMs = 10_000L, isStreaming = true))
        assertFalse(isProcessTickerActive("RUNNING", startedAtMs = 10_000L, isStreaming = false))
        assertFalse(isProcessTickerActive("PAUSED", startedAtMs = 10_000L, isStreaming = false))
        assertFalse(isProcessTickerActive("DONE", startedAtMs = 10_000L, isStreaming = true))
        assertFalse(isProcessTickerActive("STOPPED", startedAtMs = 10_000L, isStreaming = false))
        assertFalse(isProcessTickerActive("RUNNING", startedAtMs = 0L, isStreaming = true))
    }

    @Test
    fun completedProcessDurationUsesPersistedActiveTimeAndKeepsZero() {
        assertEquals(
            3_000L,
            computeProcessDurationMs(startedAtMs = 10_000L, completedAtMs = 20_000L, activeDurationMillis = 3_000L)
        )
        assertEquals(0L, computeProcessDurationMs(10_000L, 20_000L, activeDurationMillis = 0L))
        assertEquals(
            15_000L,
            computeProcessDurationMs(startedAtMs = 10_000L, completedAtMs = 25_000L, activeDurationMillis = null)
        )
        // Coerce negative duration to 0
        assertEquals(
            0L,
            computeProcessDurationMs(startedAtMs = 25_000L, completedAtMs = 10_000L, activeDurationMillis = null)
        )
        // Zero if timestamps are invalid
        assertEquals(
            0L,
            computeProcessDurationMs(startedAtMs = 0L, completedAtMs = 0L, activeDurationMillis = null)
        )

        val source = File("src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt").readText()
        assertTrue(source.contains("activeDurationMillis = activity.activeDurationMillis"))
    }

    @Test
    fun terminalTaskKeepsTheReadersExpandedHistoryAndDropsTheHandoffReason() {
        val source = File("src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt").readText()
        val presentation = File("src/main/java/com/droidance/ui/execution/TurnPresentationUiState.kt").readText()
        assertTrue(source.contains("var expanded by remember(presentation.messageId) { mutableStateOf(false) }"))
        assertFalse(source.contains("LaunchedEffect(presentation.isTerminal)"))
        assertTrue(source.contains("val intervention = presentation.handoffReason(liveIntervention)"))
        // A terminal or superseded segment never presents a pending handoff reason.
        assertTrue(presentation.contains("!isCurrentSegment || isTerminal -> \"\""))
    }
}
