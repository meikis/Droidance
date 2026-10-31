// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Nested host calls share the original invocation deadline and access constraints. */
class ToolExecutionBudget(
    timeoutMs: Long,
    val deviceAccessAllowed: Boolean,
    val mutationsAllowed: Boolean,
    private val nanoTime: () -> Long = System::nanoTime
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolExecutionBudget>
    private val deadline = nanoTime() + timeoutMs * 1_000_000L
    fun remainingMs(): Long = ((deadline - nanoTime()) / 1_000_000L).coerceAtLeast(0)

    fun constrain(timeoutMs: Long): ToolExecutionBudget = ToolExecutionBudget(
        minOf(timeoutMs.coerceAtLeast(0), remainingMs()), deviceAccessAllowed, mutationsAllowed, nanoTime)
}
