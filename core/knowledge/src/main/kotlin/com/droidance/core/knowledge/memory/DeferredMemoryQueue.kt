// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable outbox. The model proposes content; this component only validates and commits it. */
@Singleton
class DeferredMemoryQueue @Inject constructor(
    private val storage: MemoryDocumentStorage,
    private val memories: DurableMemoryStore
) {
    @Serializable
    data class Entry(
        val id: String,
        val threadId: String,
        val turnId: String,
        val update: DurableMemoryUpdate,
        val trustedTexts: List<String>,
        val failure: String? = null
    )

    private val mutex = Mutex()
    private val drainMutex = Mutex()
    private val mutableFailures = MutableStateFlow<List<Entry>>(emptyList())
    val failures = mutableFailures.asStateFlow()

    private data class Snapshot(val entries: List<Entry>, val diagnostic: String? = null)
    @Volatile private var snapshot: Snapshot? = null

    private fun publish(entries: List<Entry>) {
        snapshot = Snapshot(entries.toList())
        mutableFailures.value = entries.filter { it.failure != null }
    }

    private fun read(): List<Entry> {
        snapshot?.takeIf { it.diagnostic == null }?.let { return it.entries }
        return try {
            val entries = storage.readFile(PATH)?.let { Json.decodeFromString<List<Entry>>(it) } ?: emptyList()
            publish(entries)
            entries
        } catch (error: Exception) {
            markUnavailable(error)
            throw error
        }
    }

    suspend fun initialize() = mutex.withLock { read(); Unit }

    suspend fun reportStorageFailure(error: Exception) = mutex.withLock {
        // A newer successful queue commit must not inherit an older worker's storage failure.
        if (snapshot == null || snapshot?.diagnostic != null) markUnavailable(error)
    }

    private fun markUnavailable(error: Exception) {
        val entries = snapshot?.entries.orEmpty()
        snapshot = Snapshot(entries, error.javaClass.simpleName)
        mutableFailures.value = entries.map { it.copy(failure = error.javaClass.simpleName) }
    }

    private fun write(entries: List<Entry>) {
        try {
            storage.writeFile(PATH, Json.encodeToString(entries))
        } catch (error: Exception) {
            markUnavailable(error)
            throw error
        }
        // A proposal becomes visible only after its outbox commit succeeds.
        publish(entries)
    }

    suspend fun enqueue(threadId: String, turnId: String, updates: List<DurableMemoryUpdate>, trustedTexts: List<String>) = mutex.withLock {
        require(updates.isNotEmpty())
        updates.forEach { update ->
            DurableMemoryDocument.validate(update)
            require(update.expectedRevision != null) { "Memory revision is required" }
            require(trustedTexts.any { DurableMemoryDocument.isGrounded(update, it) }) { "MEMORY_SOURCE_REJECTED" }
        }
        val entries = read().toMutableList()
        updates.withIndex().associateBy { it.value.scope to it.value.key }.values.forEach { (index, update) ->
            val id = "$turnId:deferred-memory:$index"
            if (entries.any { it.id == id }) return@forEach
            // A newer grounded proposal for a key supersedes an older queued proposal, never vice versa.
            entries.removeAll { it.update.scope == update.scope && it.update.key == update.key }
            entries += Entry(id, threadId, turnId,
                update.copy(operationId = id, sourceRef = turnId), trustedTexts.toList())
        }
        write(entries)
    }

    suspend fun drain() = drainMutex.withLock {
        // Populate both committed document snapshots before performing any background writes.
        memories.contextSnapshot()
        initialize()
        while (true) {
            val entry = mutex.withLock { read().firstOrNull { it.failure == null } } ?: break
            val failure = try {
                val result = memories.applyUpdates(listOf(entry.update)) { entry.trustedTexts }.getOrThrow()
                result.items.firstOrNull { it.status == "failed" || it.status == "rejected" }
                    ?.let { it.error ?: "MEMORY_WRITE_FAILED" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error.javaClass.simpleName
            }
            mutex.withLock {
                val current = read()
                // A newly enqueued proposal may have superseded this in-flight entry.
                if (current.any { it.id == entry.id }) {
                    val next = if (failure == null) current.filterNot { it.id == entry.id }
                    else current.map { if (it.id == entry.id) it.copy(failure = failure) else it }
                    write(next)
                }
            }
        }
    }

    /** Read a published snapshot without waiting for the worker or touching storage. */
    fun context(threadId: String): String {
        val current = snapshot ?: return "memory_outbox_state=loading; pending writes are unconfirmed"
        val diagnostic = current.diagnostic?.let {
            "memory_outbox_unavailable=$it; pending writes are unconfirmed\n"
        }.orEmpty()
        return diagnostic + current.entries.filter { it.failure == null || it.threadId == threadId }
            .joinToString("\n") { entry ->
                val state = if (entry.failure == null) "pending" else "failed"
                "memory_write_state=$state scope=${entry.update.scope} key=${entry.update.key} " +
                    "operation=${entry.update.operation} content=${entry.update.content} diagnostic=${entry.failure.orEmpty()}"
            }
    }

    companion object { const val PATH = "runtime/memory-outbox.json" }
}
