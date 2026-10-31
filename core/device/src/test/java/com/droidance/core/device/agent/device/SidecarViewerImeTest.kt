// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import android.os.IBinder
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SidecarViewerImeTest {
    @Test fun backgroundNeverQueriesTheKeyboardAndClosingRestoresHide() {
        val platform = FakePlatform()
        val ime = SidecarViewerIme(42, platform)
        assertEquals(0, ime.bottomInset())
        assertEquals(0, platform.reads)
        val lifetime = Lifetime()
        ime.setEnabled(true, "viewer", lifetime.binder)
        assertEquals(420, ime.bottomInset())
        ime.setEnabled(false, "viewer", null)
        assertEquals(0, ime.bottomInset())
        assertEquals(listOf(42 to 2, 42 to 2, 42 to 0, 42 to 2), platform.policies)
        assertEquals(1, platform.reads)
        assertEquals(listOf(42), platform.readDisplays)
        assertEquals(listOf(42, 0), platform.focusedDisplays)
        assertEquals(1, platform.hides)
        assertNull(lifetime.recipient)
    }

    @Test fun oldViewerCleanupAndDeathCannotDisableItsReplacement() {
        val platform = FakePlatform()
        val ime = SidecarViewerIme(9, platform)
        val old = Lifetime()
        val replacement = Lifetime()
        ime.setEnabled(true, "old", old.binder)
        val staleDeath = checkNotNull(old.recipient)
        ime.setEnabled(true, "replacement", replacement.binder)
        val changes = platform.policies.size
        ime.setEnabled(false, "old", null)
        staleDeath.binderDied()
        assertEquals(changes, platform.policies.size)
        assertEquals(420, ime.bottomInset())
        checkNotNull(replacement.recipient).binderDied()
        assertEquals(9 to 2, platform.policies.last())
        assertEquals(0, ime.bottomInset())
    }

    @Test fun failedActivationLeavesTheDisplayHiddenAndUnlinksTheOwner() {
        val platform = FakePlatform().apply { rejectAllowedPolicy = true }
        val ime = SidecarViewerIme(3, platform)
        val lifetime = Lifetime()
        assertThrows(IllegalStateException::class.java) { ime.setEnabled(true, "viewer", lifetime.binder) }
        assertEquals(3 to 2, platform.policies.last())
        assertEquals(0, ime.bottomInset())
        assertNull(lifetime.recipient)
    }

    @Test fun failedCleanupRetainsOwnershipUntilHideCanBeRetried() {
        val platform = FakePlatform()
        val ime = SidecarViewerIme(3, platform)
        val lifetime = Lifetime()
        ime.setEnabled(true, "viewer", lifetime.binder)
        platform.rejectHide = true
        assertThrows(IllegalStateException::class.java) { ime.setEnabled(false, "viewer", null) }
        assertNotNull(lifetime.recipient)
        platform.rejectHide = false
        ime.setEnabled(false, "viewer", null)
        assertEquals(0, ime.bottomInset())
        assertNull(lifetime.recipient)
    }

    @Test fun deadOwnerCannotEnableTheKeyboard() {
        val platform = FakePlatform()
        val ime = SidecarViewerIme(3, platform)
        val lifetime = Lifetime().apply { alive = false }
        assertThrows(IllegalArgumentException::class.java) { ime.setEnabled(true, "viewer", lifetime.binder) }
        assertEquals(listOf(3 to 2), platform.policies)
    }

    private class FakePlatform : SidecarViewerIme.Platform {
        val policies = mutableListOf<Pair<Int, Int>>()
        var reads = 0
        var hides = 0
        val readDisplays = mutableListOf<Int>()
        var rejectAllowedPolicy = false
        var rejectHide = false
        val focusedDisplays = mutableListOf<Int>()
        override fun setPolicy(displayId: Int, policy: Int) {
            if (policy == 0 && rejectAllowedPolicy || policy == 2 && rejectHide) error("Rejected policy")
            policies += displayId to policy
        }
        override fun bottomInset(displayId: Int): Int { reads++; readDisplays += displayId; return 420 }
        override fun focusDisplay(displayId: Int) { focusedDisplays += displayId }
        override fun hideIme() { hides++ }
    }

    private class Lifetime {
        var alive = true
        var recipient: IBinder.DeathRecipient? = null
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive" -> alive
                "linkToDeath" -> { recipient = args!![0] as IBinder.DeathRecipient; null }
                "unlinkToDeath" -> { recipient = null; true }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ViewerLifetime"
                else -> null
            }
        } as IBinder
    }
}
