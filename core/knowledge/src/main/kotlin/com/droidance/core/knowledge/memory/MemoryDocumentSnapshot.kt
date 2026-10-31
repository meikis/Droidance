// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

/** Null means unavailable, whereas an empty document is a confirmed empty store. */
internal data class MemoryDocumentSnapshot(val document: String?, val diagnostic: String? = null)

data class DurableMemoryContextSnapshot(
    val entries: List<ProgressiveMemoryEntry>,
    val retiredRefs: Set<String>,
    val diagnostics: List<String>
) {
    fun requireAvailable(): DurableMemoryContextSnapshot {
        check(diagnostics.isEmpty()) { diagnostics.joinToString("; ") }
        return this
    }

}

class DeferredMemoryEnqueueException(cause: Exception) :
    Exception("MEMORY_ENQUEUE_FAILED", cause)
