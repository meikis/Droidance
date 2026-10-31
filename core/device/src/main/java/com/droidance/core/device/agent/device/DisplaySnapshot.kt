// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

data class DisplaySnapshot(
    val observationId: String,
    val backendSessionId: String,
    val displayId: Int,
    val imagePng: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val foregroundComponent: String?,
    val capturedAtElapsedMs: Long,
    /** The exact captured source owns reusable derivatives; never looked up from a later cache. */
    val nativeFrame: com.droidance.core.device.tools.DeviceScreenFrame? = null
)
