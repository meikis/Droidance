// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillReflectionQueueTest {
    @Test
    fun processReopenPreservesExactSourcesAndDeduplicatesCompletedTurnAdmissions() {
        TempSkillDocumentStorage().use { storage ->
            val queue = SkillReflectionQueue(storage)
            queue.enqueue("conversation-one", "turn-one")
            queue.enqueue("conversation-one", "turn-one")
            queue.enqueue("conversation-two", "turn-two")
            val reopened = SkillReflectionQueue(storage)
            assertEquals(listOf(
                SkillReflectionQueue.Source("conversation-one", "turn-one"),
                SkillReflectionQueue.Source("conversation-two", "turn-two")
            ), reopened.pending())
            reopened.complete("turn-one")
            assertEquals(listOf(SkillReflectionQueue.Source("conversation-two", "turn-two")),
                SkillReflectionQueue(storage).pending())
        }
    }

    @Test
    fun failedSourcesRemainVisibleWithoutBeingAutomaticallyRetriedAndDisableClearsThem() {
        TempSkillDocumentStorage().use { storage ->
            val queue = SkillReflectionQueue(storage)
            queue.enqueue("conversation-one", "turn-one")
            queue.fail("turn-one", "ModelUnavailable")
            val reopened = SkillReflectionQueue(storage)
            assertTrue(reopened.pending().isEmpty())
            assertEquals("ModelUnavailable", reopened.sources.value.single().failure)
            reopened.enqueue("conversation-two", "turn-two")
            assertEquals("turn-two", reopened.pending().single().turnId)
            reopened.clear()
            val cleared = SkillReflectionQueue(storage)
            assertTrue(cleared.pending().isEmpty())
            assertTrue(cleared.sources.value.isEmpty())
        }
    }
}
