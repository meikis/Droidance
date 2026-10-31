// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlinx.coroutines.delay

object TextInputRoutes {
    suspend fun clearFocusedText(
        selectAll: suspend () -> DeviceInputResult,
        forwardDelete: suspend () -> DeviceInputResult
    ): DeviceInputResult {
        val select = selectAll()
        if (select.success) {
            delay(50)
            val delete = forwardDelete()
            if (delete.success) return DeviceInputResult(true, "Input field cleared [key-combo]")
        }
        return DeviceInputResult(false, "$CHANNEL_UNAVAILABLE_MARKER clear input failed: no available input channel")
    }
}
