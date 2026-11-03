// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.event.TurnActivationKind
import com.droidance.core.agent.event.TurnReadModel
import com.droidance.core.agent.event.TurnReadModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Only return for a device task observed running during this viewing session. */
internal fun Flow<List<TurnReadModel>>.completedViewerTasks(): Flow<TurnReadModel> = flow {
    val observed = mutableSetOf<String>()
    collect { turns ->
        for (turn in turns) {
            if (turn.state != TurnReadModelState.TERMINAL) {
                observed += turn.turnId
            } else if (observed.remove(turn.turnId) && turn.activationKind == TurnActivationKind.ANDROID) {
                emit(turn)
            }
        }
        observed.retainAll(turns.map { it.turnId }.toSet())
    }
}
