// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import java.time.OffsetDateTime
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Owns per-Agent long-term memory documents and their semantic edit boundary. */
@Singleton
class LongTermMemoryStore @Inject constructor(
    private val fileManager: MemoryDocumentStorage
) {
    private val lock = ReentrantLock()
    @Volatile private var committedContext: MemoryDocumentSnapshot? = null

    fun read(agentName: String = DEFAULT_AGENT): String = lock.withLock {
        (fileManager.readFile(path(agentName)) ?: "").also {
            if (agentName == DEFAULT_AGENT) committedContext = MemoryDocumentSnapshot(it)
        }
    }

    fun readForDisplay(agentName: String = DEFAULT_AGENT): String =
        DurableMemoryDocument.displayProjection(read(agentName))

    fun editSnapshot(agentName: String = DEFAULT_AGENT): MemoryEditSnapshot = lock.withLock {
        val source = read(agentName)
        MemoryEditSnapshot(source, DurableMemoryDocument.displayProjection(source))
    }

    fun saveDisplayEdit(
        sourceDocument: String,
        editedDisplay: String,
        agentName: String = DEFAULT_AGENT
    ): MemoryDisplaySaveResult = lock.withLock {
        val current = read(agentName)
        if (current != sourceDocument) return@withLock MemoryDisplaySaveResult.STALE
        val next = DurableMemoryDocument.mergeDisplayEdit(
            existing = current,
            editedDisplay = editedDisplay,
            updatedAt = OffsetDateTime.now().toString()
        ) ?: return@withLock MemoryDisplaySaveResult.AMBIGUOUS
        writeLocked(next, agentName)
        MemoryDisplaySaveResult.SAVED
    }

    internal fun applyGroundedUpdates(updates: List<DurableMemoryUpdate>): Int = lock.withLock {
        require(updates.all { it.scope == DurableMemoryScope.LONG_TERM }) {
            "LongTermMemoryStore accepts only long-term updates"
        }
        if (updates.isEmpty()) return@withLock 0
        val current = read()
        val next = DurableMemoryDocument.apply(
            existing = current,
            header = "# Long-Term Memory",
            updates = updates,
            updatedAt = OffsetDateTime.now().toString()
        )
        if (next.changed == 0) return@withLock 0
        writeLocked(next.document, DEFAULT_AGENT)
        next.changed
    }

    internal fun contextSnapshot(): MemoryDocumentSnapshot {
        committedContext?.let { return it }
        if (!lock.tryLock()) return MemoryDocumentSnapshot(null, "MEMORY_CONTEXT_LOADING")
        return try {
            read()
            checkNotNull(committedContext)
        } catch (error: Exception) {
            MemoryDocumentSnapshot(null, "MEMORY_CONTEXT_UNAVAILABLE:${error.javaClass.simpleName}")
        } finally { lock.unlock() }
    }

    fun initializeIfMissing(agentName: String) {
        lock.withLock {
            val path = path(agentName)
            val exists = fileManager.exists(path)
            read(agentName)
            if (!exists) writeLocked("# Long-Term Memory\n\n", agentName)
        }
    }

    private fun writeLocked(content: String, agentName: String) {
        val path = path(agentName)
        fileManager.writeFile(path, content)
        if (agentName == DEFAULT_AGENT) committedContext = MemoryDocumentSnapshot(content)
    }

    private fun path(agentName: String): String = "agents/$agentName/MEMORY.md"

    companion object {
        const val DEFAULT_AGENT = "default"
    }
}
