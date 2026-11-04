// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge

/** The explicit sidecar permission marker is a protocol fact, independent of the device brand. */
internal fun sidecarInputDiagnostic(result: VirtualSpaceSidecarBridge.CommandResult): String? = when {
    result.ok -> null
    result.inputPermissionDenied -> "INPUT_PERMISSION_DENIED"
    result.channelUnavailable -> "CHANNEL_DOWN"
    else -> "INPUT_DELIVERY_FAILED"
}
