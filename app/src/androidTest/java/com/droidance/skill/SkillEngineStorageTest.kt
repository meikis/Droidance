// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.skill

import com.droidance.core.knowledge.skill.*

import com.droidance.skill.*

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.memory.MarkdownFileManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillEngineStorageTest {
    private lateinit var root: File
    private lateinit var files: MarkdownFileManager

    @Before
    fun setUp() {
        root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "skill-storage-${UUID.randomUUID()}").also { it.mkdirs() }
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        files = MarkdownFileManager(object : ContextWrapper(appContext) {
            override fun getNoBackupFilesDir(): File = root
            override fun getExternalFilesDir(type: String?): File = root
        })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun draftPublicationKeepsRevisionAndLeavesInstalledVersionUnchangedUntilPublish() {
        val engine = SkillEngine(files, com.droidance.skill.RhinoSkillScriptValidator())
        engine.installSkillPackage(archive(1, "Original workflow"))
        val firstDraft = requireNotNull(engine.findExecutionSnapshot(ID, true))
        engine.publishDraft(ID, firstDraft.revision)
        val installed = requireNotNull(engine.findExecutionSnapshot(ID))

        engine.saveDraft(
            markdown(version = 2, body = "Updated workflow"),
            attachments = mapOf("scripts/run.js" to "print('updated');"),
            expectedRevision = installed.revision
        )
        val pending = requireNotNull(engine.findExecutionSnapshot(ID, true))
        assertEquals(1, installed.skill.version)
        assertEquals(2, pending.skill.version)
        assertEquals("Original workflow", installed.skill.rawContent)
        assertEquals("Updated workflow", pending.skill.rawContent)
        assertTrue(pending.skill.enabled)
        assertTrue(pending.skill.allowImplicitInvocation)

        engine.publishDraft(ID, pending.revision)
        val published = requireNotNull(engine.findExecutionSnapshot(ID))
        assertEquals(2, published.skill.version)
        assertEquals(pending.revision, published.revision)
        assertFalse(files.exists("skill-drafts/$ID/SKILL.md"))
    }

    @Test
    fun stalePreviewCannotPublishAReplacementDraft() {
        val engine = SkillEngine(files, com.droidance.skill.RhinoSkillScriptValidator())
        engine.installSkillPackage(archive(1, "Original workflow"))
        val first = requireNotNull(engine.findExecutionSnapshot(ID, true))
        engine.publishDraft(ID, first.revision)
        val installed = requireNotNull(engine.findExecutionSnapshot(ID))
        engine.saveDraft(markdown(version = 2, body = "Pending workflow"), expectedRevision = installed.revision)
        val pending = requireNotNull(engine.findExecutionSnapshot(ID, true))

        val error = assertThrows(IllegalStateException::class.java) {
            engine.publishDraft(ID, installed.revision)
        }
        assertEquals("SKILL_REVISION_CHANGED", error.message)
        assertEquals(pending.revision, requireNotNull(engine.findExecutionSnapshot(ID, true)).revision)
        assertEquals(1, requireNotNull(engine.findExecutionSnapshot(ID)).skill.version)
    }

    @Test
    fun assessmentIsLocalToTheExactRevisionAndNeverAppearsInAnExport() {
        val engine = SkillEngine(files, com.droidance.skill.RhinoSkillScriptValidator())
        engine.installSkillPackage(archive(1, "Assess this workflow"))
        val draft = requireNotNull(engine.findExecutionSnapshot(ID, true))
        engine.publishDraft(ID, draft.revision)
        val snapshot = requireNotNull(engine.findExecutionSnapshot(ID))
        val record = buildJsonObject {
            put("run_id", "run-1")
            put("revision", snapshot.revision)
            put("outcome", "unknown")
            put("evidence_refs", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("event:1")) })
        }
        engine.recordAssessment(snapshot, record)
        assertEquals(listOf(record.toString()), engine.assessments(ID, false))

        val entries = ZipInputStream(engine.exportPackage(ID, false).inputStream()).use { zip ->
            buildList {
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    add(entry.name)
                }
            }
        }
        assertTrue(entries.contains("SKILL.md"))
        assertFalse(entries.any { it.startsWith("skill-assessments/") })
    }

    @Test
    fun invalidPackageMetadataAndStaleRevisionPreserveTheRealFiles() {
        val engine = SkillEngine(files, com.droidance.skill.RhinoSkillScriptValidator())
        engine.installSkillPackage(archive(1, "Original workflow"))
        val draft = requireNotNull(engine.findExecutionSnapshot(ID, true))
        val before = files.readFile("skill-drafts/$ID/SKILL.md")

        assertThrows(IllegalArgumentException::class.java) {
            engine.saveDraft(markdown(version = 0, body = "bad"), expectedRevision = draft.revision)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.saveDraft(markdown(version = 2, body = "stale"), expectedRevision = "0".repeat(64))
        }
        assertEquals(before, files.readFile("skill-drafts/$ID/SKILL.md"))
        assertEquals(draft.revision, requireNotNull(engine.findExecutionSnapshot(ID, true)).revision)
    }

    private fun markdown(version: Int, body: String): String =
        "---\nname: $ID\nversion: $version\ndescription: \"Reusable test workflow\"\n---\n$body\n"

    private fun archive(version: Int, body: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write(markdown(version, body).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("references/guide.md"))
            zip.write("Reference material".toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private companion object {
        const val ID = "test-workflow"
    }
}
