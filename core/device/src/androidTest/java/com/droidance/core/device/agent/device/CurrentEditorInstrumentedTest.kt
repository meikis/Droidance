// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device



import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.device.debug.InputProbeActivity
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentEditorInstrumentedTest {
    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    @Test fun activeEditorIsDisplayBoundAndReceivesKeyboardInput() = verifyEditor(0)

    @Test fun virtualDisplayEditorReceivesOnlyDisplayScopedInput() {
        val display = InstrumentationRegistry.getArguments().getString("probe_display_id")?.toIntOrNull()
        org.junit.Assume.assumeNotNull(display)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        try { verifyEditor(display!!) } finally { automation.dropShellPermissionIdentity() }
    }

    private fun verifyEditor(display: Int) {
        val intent = android.content.Intent(InstrumentationRegistry.getInstrumentation().targetContext, InputProbeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
        ActivityScenario.launch<InputProbeActivity>(intent, options).use { scenario ->
            scenario.onActivity { assertEquals(display, it.windowManager.defaultDisplay.displayId) }
            val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
            var focus: String? = null
            do {
                val dump = shell("dumpsys input")
                focus = InputWindowFocusParser.parse(dump, display).target
                assertNull(InputWindowFocusParser.parse(dump, 9999).target)
                if (focus != null) break
                Thread.sleep(100)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            assertNotNull("Current editor must be visible in the platform's active-user state", focus)
            // Alphabetic key events can be composed into Chinese by the user's IME.
            // This checks display-bound key delivery; production Unicode replacement is
            // covered separately by StandardVirtualSessionInstrumentedTest.
            val expected = "31415926"
            shell("input -d $display text $expected")
            val deliveryDeadline = android.os.SystemClock.elapsedRealtime() + 3_000
            var actual = ""
            do {
                scenario.onActivity { actual = it.editor.text.toString() }
                if (actual == expected) break
                Thread.sleep(25)
            } while (android.os.SystemClock.elapsedRealtime() < deliveryDeadline)
            assertEquals(expected, actual)
        }
    }
}
