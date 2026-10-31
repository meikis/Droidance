// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

const val CHANNEL_UNAVAILABLE_MARKER = "[CHANNEL_UNAVAILABLE]"

sealed class ExecutedActionEvent {
    data class Tap(val x: Int, val y: Int) : ExecutedActionEvent()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val duration: Int) : ExecutedActionEvent()
}

data class DeviceInputResult(
    val success: Boolean,
    val message: String,
    val actionEvent: ExecutedActionEvent? = null,
    val textInputEvidence: TextInputEvidence? = null,
    val diagnosticCode: String? = null
)
