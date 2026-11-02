// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Tasks share admission; an adbd mutation requires every task and preflight to be idle. */
@Singleton
class AdbLifecycleGate @Inject constructor() {
    private val admission = Mutex()
    private val tasks = MutableStateFlow(0)

    suspend fun <T> withTask(block: suspend () -> T): T {
        admission.withLock { tasks.value++ }
        return try { block() } finally {
            withContext(NonCancellable) { admission.withLock { tasks.value-- } }
        }
    }

    suspend fun <T> whileIdle(block: suspend () -> T): T {
        while (true) {
            tasks.first { it == 0 }
            admission.lock()
            if (tasks.value == 0) {
                return try { block() } finally { admission.unlock() }
            }
            admission.unlock()
        }
    }

    suspend fun <T> tryWhileIdle(block: suspend () -> T): T? {
        if (!admission.tryLock()) return null
        return try { if (tasks.value == 0) block() else null } finally { admission.unlock() }
    }
}
