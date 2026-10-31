// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeOverlayVisibilityTest {
    @Test fun homeAndOtherAppsRestoreIslandEvenWithVirtualDisplayActivityResumed() {
        val hosts = RuntimeForegroundActivities<String>()
        assertEquals(true, hosts.resume("chat", onMainDisplay = true))
        assertEquals(true, hosts.resume("virtual-host", onMainDisplay = false))
        val outside = hosts.remove("chat")
        assertEquals(false, outside)
        assertEquals(true, RuntimeOverlayVisibility.shouldShow(true, true, outside, false))
        assertEquals(true, hosts.resume("viewer", onMainDisplay = true))
        assertEquals(false, hosts.remove("viewer"))
    }

    @Test fun delayedStopOfOldActivityCannotHideNewForegroundHost() {
        val hosts = RuntimeForegroundActivities<String>()
        hosts.resume("chat", true)
        hosts.resume("viewer", true)
        assertEquals(true, hosts.remove("chat"))
        assertEquals(true, hosts.remove("chat"))
        assertEquals(false, hosts.remove("viewer"))
    }

    @Test fun externalIslandRequiresActiveMobileTaskOutsideHostAndViewer() {
        for (active in listOf(false, true)) {
            for (mobileUse in listOf(false, true)) {
                for (hostForeground in listOf(false, true)) {
                    for (viewerExpanded in listOf(false, true)) {
                        assertEquals(
                            "active=$active mobileUse=$mobileUse host=$hostForeground viewer=$viewerExpanded",
                            active && mobileUse && !hostForeground && !viewerExpanded,
                            RuntimeOverlayVisibility.shouldShow(active, mobileUse, hostForeground, viewerExpanded)
                        )
                    }
                }
            }
        }
    }
}
