// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.device

import com.droidance.core.agent.device.*

import javax.inject.Inject
import javax.inject.Singleton

data class ExecutionContext(
    val executionId: String,
    val backendSessionId: String,
    val strategy: DeviceExecutionStrategy,
    val space: ExecutionSpace,
    val displayId: Int,
    val mode: RuntimeMode,
    val readinessMessage: String? = null
) {
    val isVirtualSpace: Boolean
        get() = space == ExecutionSpace.VIRTUAL_SPACE

    companion object {
        fun fromStrategy(
            executionId: String,
            backendSessionId: String,
            strategy: DeviceExecutionStrategy,
            displayId: Int,
            readinessMessage: String? = null
        ): ExecutionContext = ExecutionContext(
            executionId = executionId,
            backendSessionId = backendSessionId,
            strategy = strategy,
            space = strategy.spaceSelection.effectiveSpace,
            displayId = displayId,
            mode = checkNotNull(strategy.selection.effectiveMode) {
                strategy.selection.unavailableReason ?: "Runtime mode is unavailable."
            },
            readinessMessage = readinessMessage
        )
    }
}

@Singleton
class ExecutionContextStore @Inject constructor() {
    @Volatile
    private var currentContext: ExecutionContext? = null

    private var observedForegroundPackage: String? = null

    fun current(): ExecutionContext? = currentContext

    /** Only device observations from this exact execution can update its return target. */
    @Synchronized
    fun recordForeground(context: ExecutionContext, component: String?) {
        if (currentContext != context) return
        component?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let { observedForegroundPackage = it }
    }

    @Synchronized
    fun foregroundPackage(context: ExecutionContext): String? =
        observedForegroundPackage.takeIf { currentContext == context }

    @Synchronized
    fun start(context: ExecutionContext) {
        require(context.mode == RuntimeMode.STANDARD && context.isVirtualSpace && context.displayId > 0) { "Only Standard virtual-space execution is supported" }
        check(currentContext == null) { "Execution context is already active" }
        observedForegroundPackage = null
        currentContext = context
    }

    fun requireCurrent(): ExecutionContext =
        checkNotNull(currentContext) { "Execution context is not active" }

    @Synchronized
    fun finish(executionId: String) {
        check(currentContext?.executionId == executionId) {
            "Execution id does not own the active context"
        }
        observedForegroundPackage = null
        currentContext = null
    }
}
