// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.config.TokenUsageRepository
import com.droidance.config.TokenUsageTotals

data class ConversationTokenUsage(
    val turns: Map<String, TokenUsageTotals> = emptyMap(),
    val hasUnrecordedTurns: Boolean = false,
    val hasReplies: Boolean = false
) {
    val total: TokenUsageTotals = turns.values.fold(TokenUsageTotals(), TokenUsageTotals::plus)
}

internal fun conversationTokenUsage(
    threadId: String,
    messages: List<UIMessage>,
    records: List<TokenUsageRepository.TurnUsage>
): ConversationTokenUsage {
    val turns = records.filter { it.threadId == threadId }.associate { it.turnId to it.totals }
    val replyTurns = messages.filter { !it.isUser }.mapNotNull { it.turnId }.toSet()
    return ConversationTokenUsage(turns, replyTurns.any { it !in turns }, replyTurns.isNotEmpty())
}
