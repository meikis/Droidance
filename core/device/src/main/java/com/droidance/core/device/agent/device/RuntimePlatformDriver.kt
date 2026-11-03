// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

/** Authenticated platform Binder handoff never waits for the GUI's application graph. */
object RuntimePlatformDriver {
    @Synchronized fun connect(): INativeInputDriver {
        check(RuntimeProcessRole.isOwner)
        val sidecar = IVirtualSpaceSidecar.Stub.asInterface(requireNotNull(SidecarBinderRegistry.get(2000)))
        sidecar.platformDriver?.let { return INativeInputDriver.Stub.asInterface(it) }
        check(sidecar.requestPlatformDriver())
        val deadline = android.os.SystemClock.uptimeMillis() + 5000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            sidecar.platformDriver?.let { return INativeInputDriver.Stub.asInterface(it) }
            android.os.SystemClock.sleep(20)
        }
        error("The Android platform driver did not connect")
    }
}
