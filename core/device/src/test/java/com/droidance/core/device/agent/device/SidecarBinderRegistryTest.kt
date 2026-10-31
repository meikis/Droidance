// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import android.os.Binder
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SidecarBinderRegistryTest {
    @Test
    fun registryRejectsUnsupportedProcessIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            SidecarBinderRegistry.set(0, LiveBinder())
        }
    }

    @Test
    fun rebroadcastKeepsLiveBinderReferenceStable() {
        val first = LiveBinder()
        val rebroadcastProxy = LiveBinder()
        SidecarBinderRegistry.clear(first)
        SidecarBinderRegistry.clear(rebroadcastProxy)

        SidecarBinderRegistry.set(2000, first)
        SidecarBinderRegistry.set(2000, rebroadcastProxy)

        assertSame(first, SidecarBinderRegistry.get(2000))
        SidecarBinderRegistry.clear(first)
        SidecarBinderRegistry.clear(rebroadcastProxy)
    }

    private class LiveBinder : Binder() {
        override fun isBinderAlive(): Boolean = true
    }
}
