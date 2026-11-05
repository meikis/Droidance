// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

/** Authenticated platform Binder handoff never waits for the GUI's application graph. */
object RuntimePlatformDriver {
    @Synchronized fun connect(): INativeInputDriver {
        check(RuntimeProcessRole.isOwner)
        val sidecar = IVirtualSpaceSidecar.Stub.asInterface(requireNotNull(SidecarBinderRegistry.get(2000)))
        fun availableDriver(): INativeInputDriver? = sidecar.platformDriver
            ?.takeIf { it.isBinderAlive && it.pingBinder() }
            ?.let(INativeInputDriver.Stub::asInterface)
        availableDriver()?.let { return it }
        check(sidecar.requestPlatformDriver())
        val deadline = android.os.SystemClock.uptimeMillis() + 5000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            availableDriver()?.let { return it }
            android.os.SystemClock.sleep(20)
        }
        error("The Android platform driver did not connect")
    }
}
