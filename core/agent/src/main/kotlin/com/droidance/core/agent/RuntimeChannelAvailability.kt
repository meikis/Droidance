// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.device.RuntimeMode

class RuntimeChannelAvailability {
    var observedMode: RuntimeMode? = null
        private set
    private var wasReady = false

    fun update(mode: RuntimeMode?, ready: Boolean): Boolean {
        val lost = mode != null && mode == observedMode && wasReady && !ready
        observedMode = mode
        wasReady = ready
        return lost
    }
}
