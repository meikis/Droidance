// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.ui.viewmodel

import android.content.Context
import android.util.AtomicFile
import com.droidance.core.agent.event.*
import com.droidance.runtime.RuntimeConnectionState
import com.droidance.runtime.RuntimeProcessClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class HistoryLoadStatus { LOADING, REFRESHING, READY, UNAVAILABLE }
data class HistorySnapshot<T>(val value: T?, val status: HistoryLoadStatus)

/** Display-only snapshots. Agent execution and writes always use the authoritative EventStore. */
@Singleton
class ConversationDisplayCache @Inject constructor(@ApplicationContext context: Context) {
    private var generation = 0L
    @Synchronized fun generation(): Long = generation
    @Synchronized fun saveIfCurrent(expected: Long, save: () -> Unit) {
        if (expected == generation) save()
    }
    private val root = File(context.noBackupFilesDir, "conversation-display")
    private val index = AtomicFile(File(root, "index.json"))
    private fun key(id: String) = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun threadFile(id: String) = AtomicFile(File(root, key(id) + ".json"))
    private fun imageDirectory(id: String) = File(root, key(id) + "-images")

    @Synchronized fun image(id: String, ref: String): ByteArray? = try {
        AtomicFile(File(imageDirectory(id), key(ref))).openRead().use { it.readBytes() }
    } catch (_: Exception) { null }

    @Synchronized fun saveImage(id: String, ref: String, bytes: ByteArray) {
        imageDirectory(id).mkdirs()
        val file = AtomicFile(File(imageDirectory(id), key(ref)))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    private fun removeThread(id: String) {
        threadFile(id).delete()
        imageDirectory(id).deleteRecursively()
    }

    private inline fun <reified T> read(file: AtomicFile): T? = try {
        file.openRead().bufferedReader().use { Json.decodeFromString<T>(it.readText()) }
    } catch (_: Exception) { null }

    private inline fun <reified T> write(file: AtomicFile, value: T) {
        root.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(Json.encodeToString(value).toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }

    @Synchronized fun thread(id: String): AgentThreadProjectionResult? =
        read<List<AgentEventEnvelope>>(threadFile(id))?.let {
            AgentThreadProjectionResult.fromEvents(it).takeIf { result -> result is AgentThreadProjectionResult.Projected }
        }

    @Synchronized fun threads(): List<AgentThreadReadModel>? = read<List<String>>(index)?.mapNotNull { id ->
        (thread(id) as? AgentThreadProjectionResult.Projected)?.thread?.takeUnless { it.metadata.tombstoned }
    }?.sortedWith(compareByDescending<AgentThreadReadModel> { it.metadata.pinned }
        .thenByDescending { it.metadata.lastActivitySequence })

    @Synchronized fun saveThread(id: String, projection: AgentThreadProjectionResult) {
        val thread = (projection as? AgentThreadProjectionResult.Projected)?.thread?.takeUnless { it.metadata.tombstoned }
        val ids = read<List<String>>(index).orEmpty().toMutableSet()
        if (thread == null) { removeThread(id); ids.remove(id) }
        else { write(threadFile(id), thread.completeRecord.events); ids.add(id) }
        write(index, ids.toList())
    }

    @Synchronized fun saveThreads(threads: List<AgentThreadReadModel>) {
        val active = threads.filterNot { it.metadata.tombstoned }
        val ids = active.map { it.metadata.threadId }
        active.forEach { write(threadFile(it.metadata.threadId), it.completeRecord.events) }
        // Successful empty reads and deletions must invalidate old display snapshots too.
        (read<List<String>>(index).orEmpty() - ids.toSet()).forEach(::removeThread)
        write(index, ids)
    }

    @Synchronized fun invalidate(ids: Set<String>?) {
        generation++ // Fence off readers that started before a confirmed deletion.
        val old = read<List<String>>(index).orEmpty()
        val removed = ids ?: old.toSet()
        removed.forEach(::removeThread)
        write(index, old.filterNot { it in removed })
    }
}

@Singleton
class ConversationHistoryRepository @Inject constructor(
    private val events: AgentEventStore,
    private val cache: ConversationDisplayCache,
    private val runtime: RuntimeProcessClient
) {
    fun thread(id: String): Flow<HistorySnapshot<AgentThreadProjectionResult>> = observe(
        { cache.thread(id) }, { events.observeThreadProjectionResult(id) }, { cache.saveThread(id, it) })

    fun threads(): Flow<HistorySnapshot<List<AgentThreadReadModel>>> = observe(
        { cache.threads() }, { events.observeActiveThreadReadModels() }, cache::saveThreads)

    private data class Read<T>(val value: T?, val fresh: Boolean = false, val failed: Boolean = false)
    private fun <T> observe(cached: () -> T?, live: () -> Flow<T>, save: (T) -> Unit): Flow<HistorySnapshot<T>> {
        val reads = flow {
            val generation = cache.generation()
            var latest = cached()
            emit(Read(latest))
            while (currentCoroutineContext().isActive) {
                try {
                    live().collect { value ->
                        latest = value
                        // A cache write failure must never hide authoritative history.
                        try { cache.saveIfCurrent(generation) { save(value) } } catch (_: Exception) {
                            android.util.Log.w("ConversationHistory", "Display cache write failed")
                        }
                        emit(Read(value, fresh = true))
                    }
                    return@flow
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    emit(Read(latest, failed = true))
                    delay(1_000)
                }
            }
        }.flowOn(Dispatchers.IO)
        return combine(reads, runtime.connectionState) { read, connection ->
            val status = when {
                read.failed || connection == RuntimeConnectionState.UNAVAILABLE -> HistoryLoadStatus.UNAVAILABLE
                read.fresh -> HistoryLoadStatus.READY
                read.value != null -> HistoryLoadStatus.REFRESHING
                else -> HistoryLoadStatus.LOADING
            }
            HistorySnapshot(read.value, status)
        }
    }
}
