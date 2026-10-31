// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.persistence.agent.event.*

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ThreadProjectionFlowTest {
    @Test
    fun repeated_table_invalidations_do_not_replay_an_unchanged_thread() = runBlocking {
        val replays = AtomicInteger()
        val projected = flowOf<Long?>(null, null, 1L, 1L, 2L, 2L)
            .projectThreadRevisions { replays.incrementAndGet() }.toList()
        assertEquals(listOf(1, 2, 3), projected)
        assertEquals(3, replays.get())
    }

    @Test
    fun projection_does_not_run_on_the_ui_collectors_thread() {
        Executors.newSingleThreadExecutor { Thread(it, "projection-test-ui") }
            .asCoroutineDispatcher().use { ui ->
                runBlocking(ui) {
                    val collectorThread = Thread.currentThread()
                    val threads = flowOf<Long?>(1L).projectThreadRevisions {
                        Thread.currentThread()
                    }.toList()
                    assertSame(collectorThread, Thread.currentThread())
                    assertNotSame(collectorThread, threads.single())
                }
            }
    }
}
