// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AgentEventStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Singleton
class ConversationHistoryDeletion @Inject constructor(
    private val events: AgentEventStore,
    private val blobs: BlobStore,
    private val tasks: ConversationTaskController,
    private val runtime: AgentRuntime
) {
    suspend fun delete(threadIds: Set<String>? = null) = tasks.withHistoryMutation(threadIds) {
        // All production blob writers belong to this execution lane, including idle skill work.
        // UI attachment preparation retains encoded input; blobs are written when a Turn starts.
        runtime.withStorageMaintenance {
            val selected = threadIds ?: events.eventsByKind(AgentEventKind.THREAD_CREATED)
                .map { it.threadId }.filterNot { it == INTERNAL_SKILL_THREAD_ID }.toSet()
            purgeConversationData(events, blobs, selected)
            if (runtime.currentConversationId.value in selected) runtime.clearConversation()
        }
    }
}

/** Caller owns the runtime execution lane, so no put-to-event-commit gap can race the sweep. */
internal suspend fun purgeConversationData(events: AgentEventStore, blobs: BlobStore, threadIds: Set<String>) {
    events.purgeThreads(threadIds)
    val referenced = events.allReferencedBlobRefs()
    for (ref in blobs.listAllRefs() - referenced) {
        currentCoroutineContext().ensureActive()
        if (!blobs.delete(ref) && ref in blobs.listAllRefs()) {
            throw java.io.IOException("An unreferenced attachment could not be deleted")
        }
    }
}
