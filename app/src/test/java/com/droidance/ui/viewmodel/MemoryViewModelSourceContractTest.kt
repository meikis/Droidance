// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryViewModelSourceContractTest {
    @Test
    fun memoryScreenModelHasNoUnrenderedDocumentOrLogSubsystem() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/MemoryViewModel.kt").readText()

        assertFalse(source.contains("todayLog"))
        assertFalse(source.contains("historyDates"))
        assertFalse(source.contains("hotWords"))
        assertFalse(source.contains("ManagedMarkdownDocument"))
        assertFalse(source.contains("AppKnowledgeManager"))
        assertFalse(source.contains("MarkdownFileManager"))
    }

    @Test
    fun profileScreenObservesBackgroundCurationAndUsesCanonicalWriter() {
        val source = projectFile("app/src/main/java/com/droidance/ui/viewmodel/MemoryViewModel.kt").readText()
        val initBlock = source.substringAfter("init {").substringBefore("fun load()")
        assertTrue(initBlock.contains("profileStore.updates.collect"))
        assertTrue(source.contains("profileStore.readForDisplay()"))
        assertTrue(source.contains("profileStore.saveDisplayEdit("))
        assertTrue(source.contains("longTermMemoryStore.saveDisplayEdit("))
        assertFalse(source.contains("MemoryManager"))
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
