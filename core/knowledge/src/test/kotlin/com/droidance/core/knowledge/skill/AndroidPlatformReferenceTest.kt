// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlatformReferenceTest {
    @Test
    fun builtinInstallationPreservesUserOwnedPackage() {
        val builtin = requireNotNull(javaClass.classLoader.getResourceAsStream(
            "android-platform-reference/SKILL.md")).bufferedReader().use { it.readText() }
        TempSkillDocumentStorage().use { storage ->
            val engine = SkillEngine(storage, RecordingSkillScriptValidator())
            engine.saveDraft(builtin.replace("source: system", "source: user"))
            val draft = requireNotNull(engine.findExecutionSnapshot("android-platform-reference", true))
            engine.publishDraft(draft.skill.id, draft.revision)
            assertFalse(engine.installBuiltinPackage(mapOf("SKILL.md" to builtin)))
            assertFalse(requireNotNull(engine.loadSkill("android-platform-reference")).isReadOnly)
        }
    }

    @Test
    fun referencePackageIsReadableWithoutExposingItsBodyInTheCatalog() {
        val markdown = requireNotNull(javaClass.classLoader.getResourceAsStream(
            "android-platform-reference/SKILL.md")).bufferedReader().use { it.readText() }
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("SKILL.md"))
                zip.write(markdown.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()

        assertEquals(mapOf("SKILL.md" to markdown), SkillPackageArchive.decode(archive))

        TempSkillDocumentStorage().use { storage ->
            val engine = SkillEngine(storage, RecordingSkillScriptValidator())
            assertTrue(engine.installBuiltinPackage(mapOf("SKILL.md" to markdown)))
            val installed = requireNotNull(engine.loadSkill("android-platform-reference"))
            assertTrue(installed.isReadOnly)
            assertEquals(1, installed.version)
            assertTrue(installed.enabled)

            val catalog = engine.catalogContext(setOf("android_act", "android_read"), maxChars = 1000)
            assertTrue(catalog.contains(installed.name))
            assertTrue(catalog.contains(installed.description))
            assertFalse(catalog.contains("android.intent.action.SET_TIMER"))
            assertFalse(
                engine.catalogContext(emptySet(), maxChars = 1000).contains(installed.name)
            )
        }
    }
}
