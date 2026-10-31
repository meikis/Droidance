// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.prompt.StablePrefix
import com.droidance.core.agent.llm.session.FrozenTurnModelSession

/** Current turn state used by the atomic checkpoint coordinator. */
interface ContextCheckpointSession {
    val scope: TurnEventScope
    val stablePrefix: StablePrefix
    var session: FrozenTurnModelSession
}
