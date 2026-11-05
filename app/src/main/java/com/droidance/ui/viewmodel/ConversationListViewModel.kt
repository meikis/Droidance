// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.event.AgentThreadReadModel
import com.droidance.core.agent.event.ConversationEntryKind
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 对话列表页 ViewModel
 */
@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val history: ConversationHistoryRepository,
    private val displayCache: ConversationDisplayCache,
    private val agentRuntime: AgentRuntime,
    private val historyDeletion: com.droidance.agent.ConversationHistoryDeletion,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    val historySnapshot = history.threads()
        .map { snapshot -> HistorySnapshot(snapshot.value?.filterNot {
            it.metadata.threadId == com.droidance.agent.INTERNAL_SKILL_THREAD_ID
        }?.map(AgentThreadReadModel::toConversationListItem), snapshot.status) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            HistorySnapshot<List<ConversationListItem>>(null, HistoryLoadStatus.LOADING))

    /** 当前正在跑任务的会话 ID（用于抽屉显示绿色脉冲点） */
    val runningConversationId: StateFlow<String?> = combine(
        agentRuntime.state,
        agentRuntime.currentConversationId
    ) { state, id ->
        if (state != AgentState.IDLE) id else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun deleteConversation(id: String, onComplete: (Boolean) -> Unit = {}) {
        appScope.launch(Dispatchers.IO) {
            val deleted = try {
                historyDeletion.delete(setOf(id))
                displayCache.invalidate(setOf(id))
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("ConversationList", "Conversation deletion failed", error)
                false
            }
            withContext(Dispatchers.Main.immediate) { onComplete(deleted) }
        }
    }
}

data class ConversationListItem(
    val id: String,
    val title: String,
    val summary: String,
    val messages: List<ConversationListMessage>,
    val isPinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

data class ConversationListMessage(
    val text: String,
    val isUser: Boolean,
    val occurredAt: Long
)

private fun AgentThreadReadModel.toConversationListItem(): ConversationListItem =
    ConversationListItem(
        id = metadata.threadId,
        title = metadata.title,
        summary = metadata.summary.orEmpty(),
        messages = conversation.entries.map { entry ->
            ConversationListMessage(
                text = entry.text,
                isUser = entry.kind == ConversationEntryKind.USER_MESSAGE ||
                    entry.kind == ConversationEntryKind.USER_STEER,
                occurredAt = entry.occurredAt
            )
        },
        isPinned = metadata.pinned,
        createdAt = metadata.createdAt,
        updatedAt = metadata.lastActivityAt
    )
