// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.TurnEventScope

/** One-shot grants belong to the exact Turn and payload, and expire without renewal on confirmation. */
internal class HighRiskConfirmationStore(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 60_000L
) {
    private data class Pending(val actionKey: String, val expiresAtMs: Long, val authorized: Boolean = false)
    private val pending = mutableMapOf<TurnEventScope, Pending>()

    @Synchronized
    fun confirm(scope: TurnEventScope): Boolean {
        val request = pending[scope] ?: return false
        if (request.expiresAtMs < nowMs()) {
            pending.remove(scope)
            return false
        }
        pending[scope] = request.copy(authorized = true)
        return true
    }

    @Synchronized
    fun consumeOrRequest(scope: TurnEventScope, actionKey: String): Boolean {
        val now = nowMs()
        pending.entries.removeAll { it.value.expiresAtMs < now }
        val request = pending.remove(scope)
        if (request?.authorized == true && request.actionKey == actionKey && request.expiresAtMs >= now) return true
        pending[scope] = Pending(actionKey, now + windowMs)
        return false
    }

    @Synchronized
    fun revoke(scope: TurnEventScope) { pending.remove(scope) }
}
