// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role

/** Keeps provider history valid when a resumed takeover interrupts a multi-call turn. */
object InterruptedToolTurn {
    fun discardPendingCalls(
        messages: MutableList<ChatMessage>,
        assistantIndex: Int,
        completedCallCount: Int
    ) {
        val assistantMessage = messages.getOrNull(assistantIndex) ?: return
        val plannedCalls = assistantMessage.toolCalls.orEmpty()
        if (assistantMessage.role != Role.ASSISTANT || plannedCalls.isEmpty()) return

        val retainedCallCount = completedCallCount.coerceIn(0, plannedCalls.size)
        if (retainedCallCount == 0) {
            messages.removeAt(assistantIndex)
        } else {
            messages[assistantIndex] = assistantMessage.copy(
                toolCalls = plannedCalls.take(retainedCallCount)
            )
        }
    }
}
