// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Uses the production chat window and a real IME, without sending a model task. */
class HomeScreenImeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainChatReopensImeAfterVirtualNavigationAndKeepsBothEditorsUsable() {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1
        }
        assertEquals(PackageManager.PERMISSION_GRANTED,
            compose.activity.checkSelfPermission(Manifest.permission.REORDER_TASKS))
        val originalDraft = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        compose.onNode(hasSetTextAction()).performTouchInput { click() }
        try { awaitMainIme() }
        catch (error: Throwable) { capture("production-chat-ime-initial-failed"); throw error }
        capture("production-chat-ime-before-virtual-input")
        try {
            BackgroundImeProbe().use { background ->
                repeat(3) { iteration ->
                    background.type("background$iteration")
                    compose.onNode(hasSetTextAction()).performTouchInput { click() }
                    try { awaitMainIme() }
                    catch (error: Throwable) {
                        capture("production-chat-ime-failed")
                        throw error
                    }
                    compose.onNode(hasSetTextAction()).performTextInput("host$iteration")
                    compose.onNode(hasSetTextAction()).assertTextContains("host$iteration")
                    if (iteration == 2) capture("production-chat-ime-after-virtual-input")
                    compose.onNode(hasSetTextAction()).performTextClearance()
                    compose.runOnIdle {
                        WindowCompat.getInsetsController(compose.activity.window,
                            compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
                    }
                    compose.waitUntil(5_000) {
                        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == false
                    }
                }
            }
        } catch (error: Throwable) {
            capture("production-chat-ime-after-cleanup")
            throw error
        } finally {
            compose.onNode(hasSetTextAction()).performTextReplacement(originalDraft)
        }
    }

    private fun awaitMainIme() {
        var stableSince = 0L
        compose.waitUntil(5_000) {
            val view = compose.activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(view)
            val ready = view.hasWindowFocus() && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom >
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + 300 &&
                compose.activity.getSystemService(InputMethodManager::class.java).isAcceptingText
            if (!ready) stableSince = 0L
            else if (stableSince == 0L) stableSince = android.os.SystemClock.uptimeMillis()
            ready && android.os.SystemClock.uptimeMillis() - stableSince >= 500L
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null),
                "composer-preview").apply { mkdirs() }
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
            ).use { File(directory, "$name-input.txt").writeBytes(it.readBytes()) }
            val view = compose.activity.window.decorView
            File(directory, "$name-insets.txt").writeText(
                "focus=${view.hasWindowFocus()} insets=${ViewCompat.getRootWindowInsets(view)}")
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}

/** Matches the shell UID and display geometry used by the production Sidecar. */
internal class BackgroundImeProbe : java.io.Closeable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val metrics = instrumentation.targetContext.resources.displayMetrics
    private val apk = instrumentation.targetContext.applicationInfo.sourceDir
    private val channel = instrumentation.uiAutomation.executeShellCommandRw(
        "env CLASSPATH=$apk app_process / com.droidance.debug.ComposerImeDisplayProbe " +
            "${metrics.widthPixels} ${metrics.heightPixels} ${metrics.densityDpi}")
    private val replies = ParcelFileDescriptor.AutoCloseInputStream(channel[0]).bufferedReader()
    private val commands = ParcelFileDescriptor.AutoCloseOutputStream(channel[1]).bufferedWriter()

    init {
        try { check(reply().startsWith("DISPLAY ")) }
        catch (error: Throwable) { commands.close(); replies.close(); throw error }
    }

    fun focusEditor() { request("focus", "FOCUSED") }
    fun type(text: String) { request("text $text", "TEXT $text") }

    private fun request(command: String, expected: String) {
        commands.write(command); commands.newLine(); commands.flush()
        check(reply() == expected) { "Unexpected display probe reply" }
    }

    private fun reply(): String = checkNotNull(replies.readLine()) { "Display probe exited" }
        .also { check(!it.startsWith("ERROR ")) { it } }

    override fun close() {
        try { request("exit", "CLOSED") }
        finally { commands.close(); replies.close() }
    }
}
