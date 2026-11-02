// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge.CommandResult
import org.junit.Assert.*
import org.junit.Test

class SidecarInputFailureTest {
    @Test fun permissionDenialDoesNotBecomeADisconnectedChannel() {
        for (disconnected in listOf(false, true)) {
            assertEquals("INPUT_PERMISSION_DENIED", sidecarInputDiagnostic(CommandResult(false,
                "SecurityException: INPUT_INJECTION_DENIED: system blocked input", channelUnavailable = disconnected)))
        }
    }

    @Test fun commandRejectionAndUnknownDeliveryRemainDistinctFromDisconnection() {
        assertEquals("INPUT_DELIVERY_FAILED", sidecarInputDiagnostic(CommandResult(false, "input rejected")))
        assertEquals("CHANNEL_DOWN", sidecarInputDiagnostic(CommandResult(false, "binder dead", channelUnavailable = true)))
        assertNull(sidecarInputDiagnostic(CommandResult(true, "tap ok")))
    }
}
