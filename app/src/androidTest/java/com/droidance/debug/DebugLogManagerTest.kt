// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.memory.MarkdownFileManager
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugLogManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val temporaryFolder = TemporaryFolder(context.cacheDir)

    private fun files(): MarkdownFileManager = MarkdownFileManager(
        object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = temporaryFolder.root
        }
    )

    @Test
    fun successfulDiagnosticsRemainReadable() {
        val files = files()
        val log = DebugLogManager(files)

        log.appendEvent("latest diagnostic")
        log.appendPersistentEvent("connection", "persistent diagnostic")

        assertTrue(files.readFile("debug/conversation-latest.md")!!.contains("latest diagnostic"))
        assertTrue(files.readFile("debug/connection-events.md")!!.contains("persistent diagnostic"))
    }

    @Test
    fun unwritableDiagnosticDirectoryDoesNotInterruptCaller() {
        val files = files()
        files.writeFile("debug", "This file blocks the diagnostic directory.")
        val log = DebugLogManager(files)

        log.appendEvent("memory update failed")
        log.appendPersistentEvent("connection", "connection diagnostic")

        assertTrue(File(temporaryFolder.root, "droidance-data/debug").isFile)
    }

    @Test
    fun invalidStreamIsStillRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DebugLogManager(files()).appendPersistentEvent("../outside", "diagnostic")
        }
    }

    @Test
    fun underlyingMemoryWriteStillPropagatesStorageFailure() {
        val files = files()
        files.writeFile("global", "This file blocks the memory directory.")

        assertThrows(IOException::class.java) {
            files.writeFile("global/USER.md", "profile")
        }
    }

    @Test
    fun cancellationIsNotSilencedAsADiagnosticFailure() {
        val cancelled = CancellationException("test cancellation")
        val files = MarkdownFileManager(object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = throw cancelled
        })

        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            DebugLogManager(files).appendEvent("diagnostic")
        })
    }
}
