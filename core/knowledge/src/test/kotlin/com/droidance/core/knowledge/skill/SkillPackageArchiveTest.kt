// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPackageArchiveTest {
    @Test
    fun decodesAndReencodesOnlyTheUnifiedSkillPackageFiles() {
        val files = mapOf(
            "SKILL.md" to "---\nname: demo\nversion: 1\ndescription: Demo\n---\nInstructions",
            "scripts/workflow.js" to "print('ready');",
            "references/guide.md" to "Use current evidence."
        )

        val decoded = SkillPackageArchive.decode(SkillPackageArchive.encode(files))

        assertEquals(files, decoded)
        assertTrue(SkillPackageArchive.encode(decoded).isNotEmpty())
    }

    @Test
    fun acceptsRawSkillMarkdownAsAOneFilePackage() {
        val markdown = "---\nname: raw\nversion: 1\ndescription: Raw\n---\nBody"

        assertEquals(mapOf("SKILL.md" to markdown), SkillPackageArchive.decode(markdown.toByteArray()))
        assertEquals(
            mapOf("SKILL.md" to markdown),
            SkillPackageArchive.decode(ByteArrayInputStream(markdown.toByteArray()))
        )
    }

    @Test
    fun rejectsLegacyFilesUnknownFilesAndNestedRootDirectories() {
        listOf(
            "SKILL.yaml" to "legacy",
            "SCRIPT.json" to "legacy",
            "README.md" to "unsupported",
            "folder/SKILL.md" to "nested"
        ).forEach { unsupported ->
            assertThrows(IllegalArgumentException::class.java) {
                SkillPackageArchive.decode(zip("SKILL.md" to validMarkdown(), unsupported))
            }
        }
        assertEquals(
            mapOf("SKILL.md" to validMarkdown()),
            SkillPackageArchive.decode(
                zip(
                    "SKILL.md" to validMarkdown(),
                    "scripts/" to ""
                )
            )
        )
    }

    @Test
    fun rejectsTraversalAbsoluteAndWrongExtensionPaths() {
        listOf(
            "../SKILL.md",
            "/SKILL.md",
            "scripts/../escape.js",
            "scripts/workflow.txt",
            "references/guide.js",
            "scripts//workflow.js",
            "SKILL.md/child"
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                SkillPackageArchive.validatePath(path)
            }
        }
        assertFalse(runCatching { SkillPackageArchive.validatePath("scripts\\workflow.js") }.isSuccess)
    }

    @Test
    fun rejectsZipEntriesBeyondThePortableDirectoryDepthBoundary() {
        val deepestAllowed = "scripts/a/b/c/d/e/f/g/h.js"
        val allowed = SkillPackageArchive.decode(
            zip("SKILL.md" to validMarkdown(), deepestAllowed to "within the boundary")
        )
        assertEquals("within the boundary", allowed[deepestAllowed])

        val tooDeep = "scripts/a/b/c/d/e/f/g/h/i.js"
        val error = assertThrows(IllegalArgumentException::class.java) {
            SkillPackageArchive.decode(zip("SKILL.md" to validMarkdown(), tooDeep to "too deep"))
        }
        assertEquals("SKILL_PATH_DEPTH", error.message)
    }

    @Test
    fun enforcesPerFileTotalPackageAndFileCountLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageArchive.validate(
                mapOf("SKILL.md" to validMarkdown(), "scripts/large.js" to "x".repeat(SkillPackageArchive.MAX_FILE_BYTES + 1))
            )
        }

        val tooManyFiles = buildMap {
            put("SKILL.md", validMarkdown())
            repeat(SkillPackageArchive.MAX_FILES) { index -> put("scripts/$index.js", "x") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageArchive.validate(tooManyFiles)
        }

        val perFile = "x".repeat(SkillPackageArchive.MAX_FILE_BYTES)
        val totalBytes = buildMap {
            put("SKILL.md", validMarkdown())
            repeat(8) { index -> put("scripts/$index.js", perFile) }
        }
        assertTrue(totalBytes.values.sumOf { it.toByteArray().size } > SkillPackageArchive.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageArchive.validate(totalBytes)
        }

        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageArchive.decode(ByteArray(SkillPackageArchive.MAX_BYTES + 1))
        }
    }

    @Test
    fun rejectsAnEntryThatExpandsBeyondThePerFileLimit() {
        val oversized = "x".repeat(SkillPackageArchive.MAX_FILE_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageArchive.decode(zip("SKILL.md" to oversized))
        }
    }

    @Test
    fun rejectsMalformedUtf8InZipEntries() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write(byteArrayOf(0xC3.toByte(), 0x28))
            zip.closeEntry()
        }

        assertThrows(CharacterCodingException::class.java) {
            SkillPackageArchive.decode(output.toByteArray())
        }
    }

    private fun validMarkdown(name: String = "demo"): String =
        "---\nname: $name\nversion: 1\ndescription: Demo\n---\nInstructions"

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
