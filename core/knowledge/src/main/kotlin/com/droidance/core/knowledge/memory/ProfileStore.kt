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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Owns the single durable user-profile document and its trusted update paths. */
@Singleton
class ProfileStore @Inject constructor(
    private val fileManager: MemoryDocumentStorage
) {
    private val lock = ReentrantLock()
    @Volatile private var committedContext: MemoryDocumentSnapshot? = null
    private val mutableUpdates = MutableSharedFlow<String>(replay = 1)
    val updates: SharedFlow<String> = mutableUpdates.asSharedFlow()

    fun readForDisplay(): String = DurableMemoryDocument.displayProjection(readDocument())

    fun editSnapshot(): MemoryEditSnapshot = lock.withLock {
        val source = readDocument()
        MemoryEditSnapshot(source, DurableMemoryDocument.displayProjection(source))
    }

    fun saveDisplayEdit(
        sourceDocument: String,
        editedDisplay: String
    ): MemoryDisplaySaveResult = lock.withLock {
        val current = readDocument()
        if (current != sourceDocument) return@withLock MemoryDisplaySaveResult.STALE
        val next = DurableMemoryDocument.mergeDisplayEdit(
            existing = current,
            editedDisplay = editedDisplay,
            updatedAt = OffsetDateTime.now().toString()
        ) ?: return@withLock MemoryDisplaySaveResult.AMBIGUOUS
        writeLocked(next)
        MemoryDisplaySaveResult.SAVED
    }


    internal fun applyGroundedUpdates(updates: List<DurableMemoryUpdate>): Int = lock.withLock {
        require(updates.all { it.scope == DurableMemoryScope.USER_PROFILE }) {
            "ProfileStore accepts only user-profile updates"
        }
        if (updates.isEmpty()) return@withLock 0
        val current = readDocument()
        val next = DurableMemoryDocument.apply(
            existing = current,
            header = "# User Profile",
            updates = updates,
            updatedAt = OffsetDateTime.now().toString()
        )
        if (next.changed == 0) return@withLock 0
        writeLocked(next.document)
        next.changed
    }

    internal fun contextSnapshot(): MemoryDocumentSnapshot {
        committedContext?.let { return it }
        if (!lock.tryLock()) return MemoryDocumentSnapshot(null, "MEMORY_CONTEXT_LOADING")
        return try {
            readDocument()
            checkNotNull(committedContext)
        } catch (error: Exception) {
            MemoryDocumentSnapshot(null, "MEMORY_CONTEXT_UNAVAILABLE:${error.javaClass.simpleName}")
        } finally { lock.unlock() }
    }

    fun isInitialized(): Boolean = fileManager.exists(PATH)

    fun initializeIfMissing(content: String) {
        lock.withLock {
            if (fileManager.exists(PATH)) { readDocument(); return }
            committedContext = MemoryDocumentSnapshot("")
            writeLocked(content)
        }
    }

    private fun readDocument(): String = lock.withLock {
        (fileManager.readFile(PATH) ?: "").also { committedContext = MemoryDocumentSnapshot(it) }
    }

    private fun writeLocked(content: String) {
        fileManager.writeFile(PATH, content)
        committedContext = MemoryDocumentSnapshot(content)
        mutableUpdates.tryEmit(content)
    }

    companion object {
        const val PATH = "global/USER.md"
    }
}
