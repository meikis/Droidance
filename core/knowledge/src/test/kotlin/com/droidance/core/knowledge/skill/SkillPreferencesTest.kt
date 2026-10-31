// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SkillPreferencesTest {
    private val markdown = "---\nname: demo\ndescription: A reusable method\n---\nInspect current evidence."

    @Test fun togglingLocalPolicyPreservesSourceRevisionAssessmentsAndRunningAssessment() {
        TempSkillDocumentStorage().use { storage ->
            val engine = SkillEngine(storage, RecordingSkillScriptValidator())
            val draft = engine.saveDraft(markdown)
            engine.publishDraft(draft.id, draft.revision)
            val snapshot = engine.findExecutionSnapshot("demo")!!
            val record = buildJsonObject { put("outcome", "unknown"); put("assessed_at", 1) }
            engine.recordAssessment(snapshot, record)
            engine.setImplicitInvocation("demo", false)
            assertEquals(snapshot.revision, engine.loadSkill("demo")!!.revision)
            assertEquals(snapshot.files, engine.findExecutionSnapshot("demo")!!.files)
            assertEquals(1, engine.assessments("demo", false).size)
            engine.recordAssessment(snapshot, record)
            assertFalse(SkillEngine(storage, RecordingSkillScriptValidator()).loadSkill("demo")!!.allowImplicitInvocation)
            engine.setSkillEnabled("demo", false)
            assertEquals(snapshot.revision, engine.loadSkill("demo")!!.revision)
            assertTrue(engine.searchSkills("*").isEmpty())
            assertNotNull(engine.recallSkill("demo"))
        }
    }

    @Test fun localPolicyDoesNotLeakIntoEditedPackagesOrExportsAndDeletionClearsIt() {
        TempSkillDocumentStorage().use { storage ->
            val engine = SkillEngine(storage, RecordingSkillScriptValidator())
            val draft = engine.saveDraft(markdown)
            engine.publishDraft(draft.id, draft.revision)
            engine.setImplicitInvocation("demo", false)
            val active = engine.loadSkill("demo")!!
            engine.updateSkill("demo", "Updated method", "Inspect current evidence.", active.revision,
                "en", "Demo", "Updated method")
            val updated = engine.loadSkill("demo", true)!!
            assertTrue(updated.allowImplicitInvocation)
            engine.publishDraft("demo", updated.revision)
            assertFalse(engine.loadSkill("demo")!!.allowImplicitInvocation)
            val exported = SkillPackageArchive.decode(engine.exportPackage("demo", false))
            assertTrue(exported.getValue("SKILL.md").contains("allow_implicit_invocation: true"))
            assertFalse(exported.keys.any { it.contains("policy") })
            engine.deleteSkill("demo")
            val recreated = engine.saveDraft(markdown)
            engine.publishDraft("demo", recreated.revision)
            assertTrue(engine.loadSkill("demo")!!.allowImplicitInvocation)
        }
    }

    @Test fun executionSnapshotReadsEachPackageFileOnlyOnce() {
        TempSkillDocumentStorage().use { storage ->
            val reads = mutableMapOf<String, Int>()
            val counted = object : SkillDocumentStorage by storage {
                override fun readFile(relativePath: String): String? {
                    reads[relativePath] = (reads[relativePath] ?: 0) + 1
                    return storage.readFile(relativePath)
                }
            }
            val engine = SkillEngine(counted, RecordingSkillScriptValidator())
            engine.saveDraft(markdown, mapOf("references/guide.md" to "Evidence"))
            reads.clear()
            assertNotNull(engine.findExecutionSnapshot("demo", true))
            assertEquals(1, reads["skill-drafts/demo/SKILL.md"])
            assertEquals(1, reads["skill-drafts/demo/references/guide.md"])
        }
    }
}
