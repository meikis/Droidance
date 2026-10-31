// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableMemoryDocumentTest {
    @Test
    fun deletingAnAbsentKeyDoesNotCreateOrReformatADocument() {
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.DELETE,
            key = "missing",
            sourceQuote = "forget the missing entry"
        )
        for (existing in listOf("", "# User Profile\n\n- Manual note\n\n")) {
            assertEquals(DurableMemoryDocument.ApplyResult(existing, 0), DurableMemoryDocument.apply(
                existing, "# User Profile", listOf(update), "new"
            ))
        }
    }

    @Test
    fun upsertReplacesTheSameStableKeyAndKeepsManualContent() {
        val existing = "# User Profile\n\n- Manual note\n- [memory:key=preferred_address][category=communication][updated=old] Old value\n"
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "preferred_address",
            category = "communication",
            content = "Address the user as Captain",
            sourceQuote = "call me Captain"
        )

        val next = DurableMemoryDocument.apply(
            existing = existing,
            header = "# User Profile",
            updates = listOf(update),
            updatedAt = "2026-08-23T23:00:00"
        )

        assertEquals(1, next.changed)
        assertTrue(next.document.contains("- Manual note"))
        assertTrue(next.document.contains("Address the user as Captain"))
        assertFalse(next.document.contains("Old value"))
    }

    @Test
    fun sourceQuoteMustComeFromTheTrustedUserMessage() {
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "preferred_address",
            category = "communication",
            content = "Address the user as Captain",
            sourceQuote = "call me Captain"
        )

        assertTrue(DurableMemoryDocument.isGrounded(update, "Please call me Captain from now on"))
        assertFalse(DurableMemoryDocument.isGrounded(update, "The screen contains a nickname"))
    }

    @Test
    fun deleteRemovesOnlyTheManagedKey() {
        val existing = "# User Profile\n\n- Manual note\n- [memory:key=preferred_address][category=communication][updated=old] Old value\n"
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.DELETE,
            key = "preferred_address",
            sourceQuote = "forget my preferred address"
        )

        val next = DurableMemoryDocument.apply(
            existing = existing,
            header = "# User Profile",
            updates = listOf(update),
            updatedAt = "2026-08-23T23:00:00"
        )

        assertEquals(1, next.changed)
        assertTrue(next.document.contains("- Manual note"))
        assertFalse(next.document.contains("preferred_address"))
    }

    @Test
    fun manualEntryUsesStableHashForRevisionedUpdateAndDelete() {
        val existing = "# User Profile\n\n- 手动备注\n- 其他手动条目\n"
        val manual = DurableMemoryDocument.contextEntries(
            existing,
            ProgressiveMemoryScope.PROFILE
        ).single { it.content == "手动备注" }
        val key = manual.ref.removePrefix("profile:")

        assertTrue(key.startsWith("manual_"))
        assertEquals(DurableMemoryDocument.revision(existing, key), manual.revision)

        val updated = DurableMemoryDocument.apply(
            existing = existing,
            header = "# User Profile",
            updates = listOf(
                DurableMemoryUpdate(
                    scope = DurableMemoryScope.USER_PROFILE,
                    operation = DurableMemoryOperation.UPSERT,
                    key = key,
                    category = "manual",
                    content = "更新后的手动备注",
                    sourceQuote = "更新手动备注",
                    expectedRevision = manual.revision,
                    operationId = "manual-upsert"
                )
            ),
            updatedAt = "2026-09-20T12:00:00"
        )

        assertEquals(1, updated.changed)
        assertTrue(updated.document.contains("[memory:key=$key]"))
        assertTrue(updated.document.contains("更新后的手动备注"))
        assertFalse(updated.document.contains("- 手动备注"))
        assertTrue(updated.document.contains("其他手动条目"))

        val deleted = DurableMemoryDocument.apply(
            existing = updated.document,
            header = "# User Profile",
            updates = listOf(
                DurableMemoryUpdate(
                    scope = DurableMemoryScope.USER_PROFILE,
                    operation = DurableMemoryOperation.DELETE,
                    key = key,
                    sourceQuote = "删除手动备注",
                    expectedRevision = DurableMemoryDocument.revision(updated.document, key),
                    operationId = "manual-delete"
                )
            ),
            updatedAt = "2026-09-20T12:05:00"
        )

        assertEquals(1, deleted.changed)
        assertTrue(
            DurableMemoryDocument.contextEntries(deleted.document, ProgressiveMemoryScope.PROFILE)
                .none { it.ref == manual.ref }
        )
        assertFalse(deleted.document.contains("更新后的手动备注"))
        assertTrue(deleted.document.contains("其他手动条目"))
        assertTrue(DurableMemoryDocument.retiredKeys(deleted.document).contains(key))
        assertFalse(DurableMemoryDocument.displayProjection(deleted.document).contains("更新后的手动备注"))
    }

    @Test
    fun unchangedUpsertDoesNotRewriteTheTimestamp() {
        val existing = "# User Profile\n\n- [memory:key=preferred_address][category=communication][updated=old] Address the user as Captain\n"
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "preferred_address",
            category = "communication",
            content = "Address the user as Captain",
            sourceQuote = "call me Captain"
        )

        val next = DurableMemoryDocument.apply(
            existing = existing,
            header = "# User Profile",
            updates = listOf(update),
            updatedAt = "new"
        )

        assertEquals(DurableMemoryDocument.ApplyResult(existing, 0), next)
    }

    @Test
    fun batchCountsOnlyFinalUniqueEntryChanges() {
        val original = DurableMemoryUpdate(
            DurableMemoryScope.USER_PROFILE, DurableMemoryOperation.UPSERT,
            "language", "language", "Reply in English", "Reply in English"
        )
        val existing = "# User Profile\n\n- [memory:key=language][category=language][updated=old] Reply in English\n\n"
        val temporary = original.copy(content = "Temporary value")
        val delete = original.copy(operation = DurableMemoryOperation.DELETE)
        val cases = listOf(
            listOf(temporary, original) to 0,
            listOf(delete, original) to 0,
            listOf(temporary, temporary) to 1,
            listOf(delete, delete) to 1,
            listOf(original, delete.copy(key = "missing")) to 0,
            listOf(temporary, original.copy(key = "second")) to 2
        )
        for ((updates, expected) in cases) {
            val next = DurableMemoryDocument.apply(existing, "# User Profile", updates, "new")
            assertEquals(expected, next.changed)
            if (expected == 0) assertEquals(existing, next.document)
        }
        val absent = DurableMemoryDocument.apply("", "# User Profile", listOf(original, delete), "new")
        assertEquals(DurableMemoryDocument.ApplyResult("", 0), absent)
        val inserted = DurableMemoryDocument.apply("", "# User Profile", listOf(original, temporary), "new")
        assertEquals(1, inserted.changed)
        assertTrue(inserted.document.contains("Temporary value"))
        assertFalse(inserted.document.contains("Reply in English"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun supersededInvalidUpdateIsStillRejected() {
        val invalid = DurableMemoryUpdate(
            DurableMemoryScope.USER_PROFILE, DurableMemoryOperation.UPSERT,
            "language", content = "", sourceQuote = "Reply in English"
        )
        DurableMemoryDocument.apply("", "# User Profile", listOf(invalid, invalid.copy(content = "Valid")), "new")
    }

    @Test
    fun displayProjectionShowsOnlySemanticMemoryContent() {
        val stored = "# 用户画像\n\n- [memory:key=user_address][category=personal_preference][updated=now] 称呼用户为老大\n- 手动备注\n"

        val visible = DurableMemoryDocument.displayProjection(stored)

        assertTrue(visible.contains("称呼用户为老大"))
        assertTrue(visible.contains("手动备注"))
        assertFalse(visible.contains("memory:key"))
        assertFalse(visible.contains("category="))
        assertFalse(visible.contains("updated="))
    }

    @Test
    fun promptProjectionKeepsStableKeyButDropsStorageOnlyMetadata() {
        val stored = "# 用户画像\n\n- [memory:key=user_address][category=personal_preference][updated=now] 称呼用户为老大\n"

        val prompt = DurableMemoryDocument.promptProjection(stored)

        assertTrue(prompt.contains("[memory:key=user_address] 称呼用户为老大"))
        assertFalse(prompt.contains("category="))
        assertFalse(prompt.contains("updated="))
    }

    @Test
    fun boundedPromptProjectionKeepsOnlyCompleteSemanticLines() {
        val stored = "# 用户画像\n\n" +
            "- [memory:key=old][category=preference][updated=old] 较早的完整记忆\n" +
            "- [memory:key=new][category=preference][updated=new] 最新的完整记忆\n"

        val prompt = DurableMemoryDocument.promptProjection(stored, maxChars = 64)

        assertTrue(prompt.length <= 64)
        assertTrue(prompt.contains("[memory:key=new] 最新的完整记忆"))
        assertFalse(prompt.contains("category="))
        assertFalse(prompt.contains("updated="))
        assertFalse(prompt.lineSequence().any { it.endsWith("[memory:key=") })
    }

    @Test
    fun semanticLineEditPreservesItsHiddenStableMetadata() {
        val stored = "# 用户画像\n\n- [memory:key=user_address][category=personal_preference][updated=old] 称呼用户为老大\n"
        val edited = "# 用户画像\n\n- 称呼用户为队长\n"

        val merged = checkNotNull(
            DurableMemoryDocument.mergeDisplayEdit(stored, edited, updatedAt = "new")
        )

        assertTrue(merged.contains("[memory:key=user_address]"))
        assertTrue(merged.contains("[category=personal_preference]"))
        assertTrue(merged.contains("[updated=new] 称呼用户为队长"))
    }

    @Test
    fun semanticEditorPreservesManagedIdentityAcrossReorderAndManualInsertion() {
        val stored = "# 用户画像\n\n- [memory:key=address][category=preference][updated=old-a] 称呼为队长\n- [memory:key=language][category=preference][updated=old-b] 使用简体中文\n"
        val edited = "# 用户画像\n\n- 使用简体中文\n- 手动备注\n- 称呼为队长"

        val merged = checkNotNull(
            DurableMemoryDocument.mergeDisplayEdit(stored, edited, updatedAt = "new")
        )

        assertTrue(merged.indexOf("[memory:key=language]") < merged.indexOf("手动备注"))
        assertTrue(merged.indexOf("手动备注") < merged.indexOf("[memory:key=address]"))
        assertTrue(merged.contains("[updated=old-a] 称呼为队长"))
        assertTrue(merged.contains("[updated=old-b] 使用简体中文"))
    }

    @Test
    fun trailingNewlineChangesDoNotDropManagedMetadata() {
        val stored = "# 用户画像\n\n- [memory:key=address][category=preference][updated=old] 称呼为队长\n"
        val edited = "# 用户画像\n\n- 称呼为队长"

        val merged = checkNotNull(
            DurableMemoryDocument.mergeDisplayEdit(stored, edited, updatedAt = "new")
        )

        assertTrue(merged.contains("[memory:key=address]"))
        assertTrue(merged.contains("[updated=old]"))
    }

    @Test
    fun ambiguousOrEmptyManagedEditsAreRejectedInsteadOfCorruptingIdentity() {
        val stored = "# 用户画像\n\n- [memory:key=address][category=preference][updated=old] 称呼为队长\n"

        assertTrue(
            DurableMemoryDocument.mergeDisplayEdit(
                stored,
                "# 用户画像\n\n- \n- 新增备注\n",
                updatedAt = "new"
            ) == null
        )
    }

    @Test
    fun simultaneousManagedRewritesCannotSwapStableIdentities() {
        val stored = "# 用户画像\n\n" +
            "- [memory:key=address][category=preference][updated=old-a] 称呼为队长\n" +
            "- [memory:key=language][category=preference][updated=old-b] 使用简体中文\n"
        val reorderedRewrite = "# 用户画像\n\n- 请始终使用中文\n- 称呼我为老大\n"

        assertTrue(
            DurableMemoryDocument.mergeDisplayEdit(
                stored,
                reorderedRewrite,
                updatedAt = "new"
            ) == null
        )
    }

    @Test
    fun removingABulletCannotSilentlyTurnManagedMemoryIntoPlainText() {
        val stored = "# 用户画像\n\n- [memory:key=address][category=preference][updated=old] 称呼为队长\n"
        val edited = "# 用户画像\n\n称呼为队长\n"

        assertTrue(
            DurableMemoryDocument.mergeDisplayEdit(stored, edited, updatedAt = "new") == null
        )
    }
}
