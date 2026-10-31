// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Zero disables an application deadline; parent cancellation always remains in force. */
suspend fun <T> withModelRequestTimeout(timeoutMs: Long, block: suspend () -> T): T {
    require(timeoutMs >= 0)
    return if (timeoutMs == 0L) block() else withTimeout(timeoutMs) { block() }
}

suspend fun <T> withModelRequestTimeoutOrNull(timeoutMs: Long, block: suspend () -> T): T? {
    require(timeoutMs >= 0)
    return if (timeoutMs == 0L) block() else withTimeoutOrNull(timeoutMs) { block() }
}
