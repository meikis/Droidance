// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.device.debug.DiagnosticsCollector
import com.droidance.memory.MarkdownFileManager
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val temporary = TemporaryFolder(context.cacheDir)
    private fun contextAt(root: File) = object : ContextWrapper(context) {
        override fun getNoBackupFilesDir(): File = root
        override fun getExternalFilesDir(type: String?): File = root
    }

    @Test fun diagnosticsCanBeWrittenAgainAfterTheirDirectoriesAreCleared() {
        val collector = DiagnosticsCollector(contextAt(temporary.root))
        collector.startTaskDiagnostics("task")
        collector.logTypeText("before", "input", true, 1)
        val input = File(temporary.root, "droidance-data/diagnostics/input/type-text.jsonl")
        assertTrue(input.exists())
        collector.clearAll()
        assertFalse(input.exists())
        collector.logTypeText("after", "input", true, 1)
        assertTrue(input.readText().contains("after"))
        assertTrue(input.readText().contains("task"))
        assertFalse(input.readText().contains("before"))
    }

    @Test fun anUnavailableDiagnosticsDirectoryIsAReportedCleanupFailure() {
        val blocked = temporary.newFile("blocked")
        val collector = DiagnosticsCollector(contextAt(blocked))
        assertThrows(IOException::class.java) { collector.clearAll() }
    }

    @Test fun logDeletionIsIdempotentButDoesNotHideFilesystemFailures() {
        val files = MarkdownFileManager(contextAt(temporary.root))
        assertFalse(files.deleteFile("debug/conversation-latest.md"))
        files.writeFile("debug/conversation-latest.md/child", "Cannot delete a nonempty directory as a log")
        assertThrows(IOException::class.java) { files.deleteFile("debug/conversation-latest.md") }
    }
}
