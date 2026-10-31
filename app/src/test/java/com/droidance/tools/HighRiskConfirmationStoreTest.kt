// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.TurnEventScope
import org.junit.Assert.*
import org.junit.Test

class HighRiskConfirmationStoreTest {
    private val a = TurnEventScope("thread", "turn-a")
    private val b = TurnEventScope("thread", "turn-b")

    @Test fun grantCannotCrossTurnsOrBeReused() {
        val store = HighRiskConfirmationStore()
        assertFalse(store.consumeOrRequest(a, "delete"))
        assertFalse(store.confirm(b))
        assertTrue(store.confirm(a))
        assertFalse(store.consumeOrRequest(b, "delete"))
        assertTrue(store.consumeOrRequest(a, "delete"))
        assertFalse(store.consumeOrRequest(a, "delete"))
    }

    @Test fun expiredRequestCannotBeRevivedByConfirmation() {
        var time = 100L
        val store = HighRiskConfirmationStore({ time }, 10)
        assertFalse(store.consumeOrRequest(a, "delete"))
        time = 111
        assertFalse(store.confirm(a))
        assertFalse(store.consumeOrRequest(a, "delete"))
    }

    @Test fun changedPayloadAndCancellationRevokeGrant() {
        val store = HighRiskConfirmationStore()
        store.consumeOrRequest(a, "delete-a")
        assertTrue(store.confirm(a))
        assertFalse(store.consumeOrRequest(a, "delete-b"))
        assertFalse(store.consumeOrRequest(a, "delete-a"))
        assertTrue(store.confirm(a))
        store.revoke(a)
        assertFalse(store.confirm(a))
        assertFalse(store.consumeOrRequest(a, "delete-a"))
    }
}
