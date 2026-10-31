// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Coordinates trusted updates and bounded context across the two durable memory stores. */
@Singleton
class DurableMemoryStore @Inject constructor(
    private val profileStore: ProfileStore,
    private val longTermMemoryStore: LongTermMemoryStore
) {
    suspend fun applyUpdates(
        updates: List<DurableMemoryUpdate>,
        loadTrustedUserTexts: suspend () -> List<String>
    ): Result<DurableMemoryApplyResult> = try {
        currentCoroutineContext().ensureActive()
        val trustedUserTexts = loadTrustedUserTexts()
        currentCoroutineContext().ensureActive()
        val grounded = updates.filter { update ->
            trustedUserTexts.any { trusted -> DurableMemoryDocument.isGrounded(update, trusted) }
        }
        // A batch declares the final state of each key. Intermediate proposals must not
        // reach disk, otherwise a net-unchanged batch mutates timestamps and revisions.
        val finalGroundedIndex = updates.indices.filter { updates[it] in grounded }
            .associateBy { updates[it].scope to updates[it].key }
        var changed = 0
        val results = updates.mapIndexed { index, update ->
            if (update !in grounded) MemoryItemResult(update.key, "rejected", null, "MEMORY_SOURCE_REJECTED")
            else if (finalGroundedIndex[update.scope to update.key] != index)
                MemoryItemResult(update.key, "superseded", null)
            else try {
                val count = when (update.scope) {
                    DurableMemoryScope.USER_PROFILE -> profileStore.applyGroundedUpdates(listOf(update))
                    DurableMemoryScope.LONG_TERM -> longTermMemoryStore.applyGroundedUpdates(listOf(update))
                }
                changed += count
                val document = when (update.scope) {
                    DurableMemoryScope.USER_PROFILE -> profileStore.editSnapshot().sourceDocument
                    DurableMemoryScope.LONG_TERM -> longTermMemoryStore.read()
                }
                MemoryItemResult(update.key, if (count > 0) "committed" else "unchanged",
                    DurableMemoryDocument.revision(document, update.key))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                MemoryItemResult(update.key, "failed", null, error.message ?: error.javaClass.simpleName)
            }
        }
        Result.success(DurableMemoryApplyResult(updates.size, grounded.size, changed, results))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun expand(ref: String): ProgressiveMemoryEntry? =
        contextSnapshot().requireAvailable().entries.singleOrNull { it.ref == ref }

    /** Capture each committed document once, so entries and tombstones use the same revisions. */
    fun contextSnapshot(): DurableMemoryContextSnapshot {
        val documents = listOf(
            ProgressiveMemoryScope.PROFILE to profileStore.contextSnapshot(),
            ProgressiveMemoryScope.LONG_TERM to longTermMemoryStore.contextSnapshot()
        )
        return DurableMemoryContextSnapshot(
            entries = documents.flatMap { (scope, snapshot) ->
                snapshot.document?.let { DurableMemoryDocument.contextEntries(it, scope) }.orEmpty()
            },
            retiredRefs = documents.flatMap { (scope, snapshot) ->
                snapshot.document?.let { document ->
                    DurableMemoryDocument.retiredKeys(document).map { "${scope.refPrefix}:$it" }
                }.orEmpty()
            }.toSet(),
            diagnostics = documents.mapNotNull { (scope, snapshot) ->
                snapshot.diagnostic?.let { "${scope.refPrefix}:$it" }
            }
        )
    }
}
