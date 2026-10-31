// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventConflictException
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.core.agent.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** Ends interrupted Turns before this process accepts new Agent work. */
fun interface InterruptedNoticeProvider {
    fun getNotice(): String
}

@Singleton
class AgentProcessRecoveryCoordinator @Inject constructor(
    private val eventStore: AgentEventStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val noticeProvider: InterruptedNoticeProvider
) {
    @Volatile
    private var recoveryTask: Deferred<Unit>? = null

    fun start() {
        task()
    }

    suspend fun awaitReady() {
        task().await()
    }

    private fun task(): Deferred<Unit> = recoveryTask ?: synchronized(this) {
        recoveryTask ?: applicationScope.async(Dispatchers.IO) {
            recoverUnfinishedTurns()
        }.also { recoveryTask = it }
    }

    private suspend fun recoverUnfinishedTurns() {
        eventStore.unfinishedTurnScopesForRecovery().forEach { turnScope ->
            val turn = eventStore.threadReadModel(turnScope.threadId)
                ?.turns
                ?.singleOrNull { it.turnId == turnScope.turnId }
                ?: throw AgentEventConflictException(
                    "Unfinished Turn ${turnScope.turnId} is missing from its EventStore projection"
                )
            if (turn.state == TurnReadModelState.TERMINAL) {
                throw AgentEventConflictException(
                    "Terminal Turn ${turnScope.turnId} was returned for process recovery"
                )
            }
            val durableAnchor = eventStore.eventsForTurn(turnScope.turnId).last().sequence
            eventStore.terminateInterruptedTurn(
                scope = turnScope,
                recoveryId = "process:${turnScope.turnId}:$durableAnchor",
                notice = noticeProvider.getNotice()
            )
        }
    }
}
