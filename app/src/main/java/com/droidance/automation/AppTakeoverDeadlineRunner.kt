// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds one existing execution, including waits; changes may shorten or extend its deadline. */
object AppTakeoverDeadlineRunner {
    suspend fun run(
        changes: StateFlow<Long>,
        deadlineAtMs: () -> Long?,
        maxSleepMs: Long,
        nowMs: () -> Long = System::currentTimeMillis,
        onDeadlineReached: () -> Unit = {},
        operation: suspend () -> Unit
    ): Boolean {
        val operationContext = currentCoroutineContext().minusKey(Job)
        return withContext(Dispatchers.Default) {
            coroutineScope {
                val updates = changes.produceIn(this)
                val worker = launch(operationContext, start = CoroutineStart.LAZY) { operation() }
                var expired = false
                try {
                    while (!worker.isCompleted) {
                        val remainingMs = deadlineAtMs()?.minus(nowMs())
                        if (remainingMs != null && remainingMs <= 0L) {
                            expired = true
                            worker.cancel(CancellationException("TASK_DEADLINE_REACHED"))
                            onDeadlineReached()
                            worker.join()
                            break
                        }
                        worker.start()
                        val waitMs = remainingMs?.coerceAtMost(maxSleepMs) ?: maxSleepMs
                        withTimeoutOrNull(waitMs) {
                            select<Unit> {
                                worker.onJoin { }
                                updates.onReceive { }
                            }
                        }
                    }
                } finally {
                    updates.cancel()
                }
                if (deadlineAtMs()?.let { nowMs() >= it } == true) expired = true
                if (!expired && worker.isCancelled) throw CancellationException("EXECUTION_CANCELLED")
                !expired
            }
        }
    }
}
