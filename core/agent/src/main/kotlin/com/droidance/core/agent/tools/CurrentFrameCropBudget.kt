// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/** Bounds derivations of one live observation; historical evidence inspection has no live-frame lease. */
class CurrentFrameCropBudget {
    private var observationId: String? = null
    private var count = 0

    @Synchronized fun acquire(id: String, limit: Int): Boolean {
        require(limit > 0)
        if (observationId != id) { observationId = id; count = 0 }
        if (count >= limit) return false
        count++
        return true
    }

    @Synchronized fun releaseFailed(id: String) {
        if (observationId == id) count = (count - 1).coerceAtLeast(0)
    }
}
