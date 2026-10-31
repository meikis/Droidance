// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

/**
 * The immutable sidecar identity frozen into a committed execution attempt.
 *
 * It is intentionally smaller than the Harness execution context: the bridge only needs the
 * selected runtime mode, display and binder identity. Callers must not derive a replacement
 * binding from current settings when this identity no longer matches.
 */
data class SidecarSessionBinding(
    val runtimeMode: RuntimeMode,
    val displayId: Int,
    val backendSessionId: String
) {
    init {
        require(displayId >= 0) { "Sidecar displayId cannot be negative" }
        require(backendSessionId.isNotBlank()) { "Sidecar backendSessionId cannot be blank" }
    }

    fun matches(runtimeMode: RuntimeMode, displayId: Int, backendSessionId: String): Boolean =
        runtimeMode == RuntimeMode.STANDARD && this.runtimeMode == runtimeMode &&
            this.displayId == displayId && this.backendSessionId == backendSessionId
}
