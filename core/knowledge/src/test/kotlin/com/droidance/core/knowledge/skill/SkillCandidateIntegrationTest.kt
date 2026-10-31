// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SkillCandidateIntegrationTest {
    @Test fun candidatesReadBoundedCurrentRevisionFeedbackWithoutScanningTheAuditArchive() {
        TempSkillDocumentStorage().use { storage ->
            val reads = mutableListOf<String>()
            val observed = object : SkillDocumentStorage by storage {
                override fun listFiles(directoryPath: String): List<String> {
                    reads += directoryPath
                    return storage.listFiles(directoryPath)
                }
            }
            val engine = SkillEngine(observed, RecordingSkillScriptValidator())
            val draft = engine.saveDraft(markdown)
            engine.publishDraft(draft.id, draft.revision)
            val snapshot = engine.findExecutionSnapshot(draft.id)!!
            repeat(25) { index -> engine.recordAssessment(snapshot, buildJsonObject {
                put("run_id", "run-$index"); put("outcome", if (index == 24) "unknown" else "succeeded")
                put("assessed_at", index); put("summary", "Observed environment $index.")
            }) }
            reads.clear()
            val candidate = engine.searchSkills("reusable").single()
            assertTrue(candidate.rawContent.isEmpty())
            assertEquals(snapshot.revision, candidate.revision)
            assertEquals(5, candidate.recentFeedback!!.recentCount)
            assertEquals(1, candidate.recentFeedback!!.unknown)
            assertEquals(4, candidate.recentFeedback!!.succeeded)
            assertTrue(reads.none { it.startsWith("skill-assessments/") })
            val context = engine.catalogContext(emptySet(), 1000, "reusable")
            assertTrue(context.contains("unknown=1"))
            assertFalse(context.contains("PRIVATE METHOD BODY"))
            assertTrue(context.length <= 1000)
            assertTrue(engine.catalogContext(emptySet(), 256, "reusable").contains("candidate"))
            engine.saveDraft(markdown + "\nChanged method.", expectedRevision = snapshot.revision)
            val next = engine.findExecutionSnapshot(draft.id, true)!!
            engine.publishDraft(draft.id, next.revision)
            assertEquals(0, engine.searchSkills("reusable").single().recentFeedback!!.recentCount)
        }
    }

    private val markdown = """
        ---
        name: candidate
        description: A reusable method.
        ---
        PRIVATE METHOD BODY
    """.trimIndent()
}
