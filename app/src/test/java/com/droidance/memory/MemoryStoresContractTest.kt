// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStoresContractTest {
    @Test
    fun memoryResponsibilitiesHaveUniqueStoresWithoutManagerFacade() {
        val memoryDir = projectFile("app/src/main/java/com/droidance/memory")
        val knowledgeDir = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/memory")
        val profile = knowledgeDir.resolve("ProfileStore.kt").readText()
        val longTerm = knowledgeDir.resolve("LongTermMemoryStore.kt").readText()
        val durable = knowledgeDir.resolve("DurableMemoryStore.kt").readText()
        val device = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/agent/device/DeviceProfileStore.kt").readText()

        assertFalse(memoryDir.resolve("MemoryManager.kt").exists())
        assertFalse(memoryDir.resolve("CuratedMemoryStore.kt").exists())
        assertTrue(profile.contains("const val PATH = \"global/USER.md\""))
        assertTrue(profile.contains("private fun readDocument()"))
        assertTrue(longTerm.contains("fun read(agentName:"))
        assertTrue(durable.contains("fun applyUpdates("))
        assertTrue(durable.contains("fun contextSnapshot()"))
        assertTrue(device.contains("const val PATH = \"agents/default/DEVICE.md\""))
    }

    @Test
    fun durableStoresReadCanonicalDocumentsWithoutMtimeOnlyCaches() {
        val fileManager = projectFile("app/src/main/java/com/droidance/memory/MarkdownFileManager.kt").readText()
        val profile = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/memory/ProfileStore.kt").readText()
        val longTerm = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/memory/LongTermMemoryStore.kt").readText()

        assertTrue(fileManager.contains("fun lastModified"))
        assertFalse(profile.contains("CacheEntry"))
        assertTrue(profile.contains("fileManager.readFile(PATH)"))
        assertFalse(longTerm.contains("CacheEntry"))
        assertTrue(longTerm.contains("fileManager.readFile(path(agentName))"))
    }

    @Test
    fun userProfileWritesPublishLiveUpdates() {
        val profile = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/memory/ProfileStore.kt").readText()
        val writeBlock = profile.substringAfter("private fun writeLocked(content: String)")
            .substringBefore("companion object")

        assertTrue(profile.contains("val updates: SharedFlow<String>"))
        assertTrue(writeBlock.contains("mutableUpdates"))
        assertTrue(writeBlock.contains("tryEmit(content)"))
    }

    @Test
    fun semanticEditorSaveIsAtomicAndRejectsAStaleSnapshot() {
        val profile = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/memory/ProfileStore.kt").readText()
        val saveBlock = profile.substringAfter("fun saveDisplayEdit(")
            .substringBefore("internal fun applyGroundedUpdates(")

        assertTrue(saveBlock.contains("lock.withLock"))
        assertTrue(saveBlock.contains("current != sourceDocument"))
        assertTrue(saveBlock.contains("MemoryDisplaySaveResult.STALE"))
        assertTrue(saveBlock.contains("DurableMemoryDocument.mergeDisplayEdit("))
        assertTrue(saveBlock.contains("writeLocked("))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
