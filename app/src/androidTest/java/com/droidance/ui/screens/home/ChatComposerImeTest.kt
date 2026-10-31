// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import android.app.Dialog
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.droidance.core.device.debug.InputProbeActivity
import com.droidance.R
import com.droidance.settings.AppThemeMode
import com.droidance.ui.theme.DroidanceTheme
import com.droidance.ui.viewmodel.ComposerPreferences
import com.droidance.ui.viewmodel.ComposerModelOption
import com.droidance.core.agent.device.ExecutionSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the actual composer and system IME without starting a model task. */
class ChatComposerImeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadingAndProgressUpdatesDoNotOpenImeWithoutATap() {
        val processing = mutableStateOf(false)
        val preferences = mutableStateOf(ComposerPreferences())
        setComposer(isProcessing = { processing.value }, preferences = { preferences.value })
        awaitIme(false)
        repeat(6) {
            compose.runOnIdle {
                processing.value = !processing.value
                preferences.value = preferences.value.copy(model = ComposerModelOption("provider", "Progress", "model-$it"))
            }
            awaitIme(false)
            editor().assertIsNotFocused()
        }
    }

    @Test fun dismissingImmediatelyAfterATapDoesNotRetryTheShow() {
        setComposer()
        editor().performTouchInput { click() }
        awaitIme(true)
        hideIme()
        assertImeStaysHidden()
        editor().performTouchInput { click() }
        awaitIme(true)
    }

    @Test fun returningFromAnotherWindowRequiresANewTap() {
        setComposer()
        editor().performTouchInput { click() }
        awaitIme(true)
        lateinit var dialog: Dialog
        compose.runOnIdle {
            dialog = Dialog(compose.activity).apply {
                setContentView(TextView(context).apply { text = "Window focus fixture" })
                window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                show()
            }
        }
        compose.waitUntil(5_000) { !compose.activity.window.decorView.hasWindowFocus() }
        compose.runOnIdle { dialog.dismiss() }
        compose.waitUntil(5_000) { compose.activity.window.decorView.hasWindowFocus() }
        awaitIme(false)
        assertImeStaysHidden()
        editor().performTouchInput { click() }
        awaitIme(true)
    }

    @Test fun selectingModelKeepsTheDraftUntilSend() {
        val preferences = mutableStateOf(ComposerPreferences())
        val option = ComposerModelOption("provider", "Configured provider", "namespace/a-long-configured-model-name")
        var sent = ""
        setComposer(
            preferences = { preferences.value },
            onPreferencesChanged = { preferences.value = it },
            modelOptions = listOf(option),
            onSend = { sent = it }
        )
        capture("composer-default")
        editor().performClick().performTextInput("draft task")
        awaitIme(true)
        capture("composer-keyboard")
        compose.onNodeWithContentDescription(label(R.string.composer_model_description, option.modelId)).performClick()
        awaitIme(false)
        capture("composer-model-picker")
        compose.onNodeWithText(option.modelId).performClick()
        compose.onNodeWithContentDescription(label(R.string.composer_picker_close)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.composer_model_description, option.modelId)).assertExists()
        editor().assertTextContains("draft task")
        compose.runOnIdle { assertEquals("", sent) }
        compose.onNodeWithContentDescription(label(R.string.composer_send)).performClick()
        compose.runOnIdle {
            assertEquals("draft task", sent)
            assertEquals(option, preferences.value.model)
            assertEquals(ExecutionSpace.VIRTUAL_SPACE, preferences.value.executionSpace)
        }
    }

    @Test fun aRejectedSubmissionPreservesTheDraft() {
        setComposer(acceptSend = false)
        editor().performClick().performTextInput("keep this draft")
        compose.onNodeWithContentDescription(label(R.string.composer_send)).performClick()
        editor().assertTextContains("keep this draft")
    }

    @Test fun runningTaskKeepsNextMessageSelectionsAndEditorAvailable() {
        setComposer(isProcessing = { true })
        editor().assertIsEnabled()
        compose.onNodeWithContentDescription(label(R.string.composer_attachment)).assertIsEnabled()
        editor().performClick().performTextInput("steer")
        compose.onNodeWithContentDescription(label(R.string.composer_model_description, label(R.string.composer_model_auto))).assertIsEnabled()
        compose.onNodeWithContentDescription(label(R.string.composer_send)).performClick()
        editor().assertIsFocused()
    }

    @Test fun hidingImeKeepsFocusAndBothEditorAndPaddingCanReopenIt() {
        setComposer()
        editor().performClick()
        awaitIme(true)

        hideIme()
        editor().assertIsFocused().performClick()
        awaitIme(true)

        hideIme()
        compose.onNode(hasClickAction() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true)
            .performTouchInput { click(Offset(width / 2f, height - 2f)) }
        awaitIme(true)
        editor().assertIsFocused().performTextInput("after-dismiss")
    }

    @Test fun sendingDismissesImeAndTheNextTapStartsEditingAgain() {
        var sent = ""
        setComposer(onSend = { sent = it })
        editor().performClick().performTextInput("first message")
        awaitIme(true)
        compose.onNodeWithContentDescription(label(R.string.composer_send)).performClick()
        awaitIme(false)
        editor().assertIsNotFocused()
        compose.runOnIdle { assertEquals("first message", sent) }

        editor().performClick()
        awaitIme(true)
        editor().assertIsFocused().performTextInput("next message")
    }

    @Test fun closingAttachmentsRestoresTheAbilityToOpenIme() {
        setComposer()
        editor().performClick()
        awaitIme(true)
        compose.onNodeWithContentDescription(label(R.string.composer_attachment)).performClick()
        awaitIme(false)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()

        editor().performClick()
        awaitIme(true)
        editor().assertIsFocused().performTextInput("after-attachments")
    }

    @Test fun returningWindowFocusAndSteeringDuringProcessingKeepInputUsable() {
        var sent = ""
        val processing = mutableStateOf(false)
        setComposer(isProcessing = { processing.value }, onSend = { sent = it })
        editor().performClick()
        awaitIme(true)
        hideIme()
        lateinit var dialog: Dialog
        compose.runOnIdle {
            dialog = Dialog(compose.activity).apply {
                setContentView(TextView(context).apply { text = "Window focus fixture" })
                show()
            }
        }
        compose.waitUntil(5_000) { !compose.activity.window.decorView.hasWindowFocus() }
        compose.runOnIdle { dialog.dismiss(); processing.value = true }
        compose.waitUntil(5_000) { compose.activity.window.decorView.hasWindowFocus() }

        editor().performClick().performTextInput("steering message")
        awaitIme(true)
        compose.onNodeWithContentDescription(label(R.string.composer_send)).performClick()
        compose.runOnIdle { assertEquals("steering message", sent) }
        editor().assertIsFocused()
        awaitIme(true)
    }

    @Test fun startingATaskAndDismissingImeDuringProcessingAllowRepeatedEditorTaps() {
        val processing = mutableStateOf(false)
        setComposer(isProcessing = { processing.value }, onSend = { processing.value = true })
        editor().performTouchInput { click() }
        awaitIme(true)
        editor().performTextInput("start task")
        compose.onNodeWithContentDescription(label(R.string.composer_send)).performClick()
        awaitIme(false)
        editor().assertIsNotFocused()

        editor().performTouchInput { click() }
        awaitIme(true)
        repeat(3) {
            hideIme()
            editor().assertIsFocused().performTouchInput { click() }
            awaitIme(true)
        }
        editor().performTextInput("draft while waiting for the reply")
        capture("composer-processing-ime-reopened")
    }

    @Test fun aPaddingTapWaitsForWindowAndEditorFocusBeforeShowingIme() {
        setComposer(isProcessing = { true })
        lateinit var dialog: Dialog
        compose.runOnIdle {
            dialog = Dialog(compose.activity).apply {
                setContentView(TextView(context).apply { text = "Window focus fixture" })
                show()
            }
        }
        try {
            compose.waitUntil(5_000) { !compose.activity.window.decorView.hasWindowFocus() }
            // Invoke the real padding action while its window cannot yet serve the IME.
            composerSurface().performClick()
            compose.runOnIdle { dialog.dismiss() }
            awaitIme(true)
            editor().assertIsFocused()
        } finally {
            compose.runOnIdle { dialog.dismiss() }
        }
    }

    @Test fun aUserTapReclaimsComposerFocusAfterBackgroundDisplayInput() {
        setComposer(isProcessing = { true })
        editor().performTouchInput { click() }
        awaitIme(true)
        hideIme()
        BackgroundImeProbe().use { background ->
            repeat(3) { iteration ->
                background.focusEditor()
                editor().performTouchInput { click() }
                awaitIme(true)
                compose.runOnIdle {
                    assertTrue("A user tap must restore the main window focus", compose.activity.window.decorView.hasWindowFocus())
                }
                if (iteration == 2) capture("composer-background-input-ime-reopened")
                hideIme()
            }
            editor().performTouchInput { click() }
            awaitIme(true)
            editor().performTextInput("main_display_draft")
            editor().assertTextContains("main_display_draft")
        }
    }

    @Test fun nativeEditorShowsImeBeforeReturningToTheComposer() {
        setComposer()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = android.content.Intent(context, InputProbeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        ActivityScenario.launch<InputProbeActivity>(intent).use { scenario ->
            lateinit var nativeDecor: android.view.View
            scenario.onActivity { nativeDecor = it.window.decorView }
            awaitIme(true, nativeDecor)
            capture("composer-native-ime-control")
        }
        editor().performTouchInput { click() }
        awaitIme(true)
    }

    private fun setComposer(
        isProcessing: () -> Boolean = { false },
        onSend: (String) -> Unit = {},
        acceptSend: Boolean = true,
        preferences: () -> ComposerPreferences = { ComposerPreferences() },
        onPreferencesChanged: (ComposerPreferences) -> Unit = {},
        modelOptions: List<ComposerModelOption> = emptyList()
    ) {
        compose.setContent {
            DroidanceTheme(AppThemeMode.SYSTEM) {
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    ChatInputBar(
                        availableSkills = emptyList(),
                        preferences = preferences(),
                        modelOptions = modelOptions,
                        onModelSelected = { onPreferencesChanged(preferences().copy(model = it)) },
                        isProcessing = isProcessing(),
                        onSendMessage = { text, _, _ -> onSend(text); acceptSend }
                    )
                }
            }
        }
    }

    private fun editor() = compose.onNode(hasSetTextAction())

    private fun composerSurface() = compose.onNode(
        hasClickAction() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true
    )

    private fun label(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun capture(name: String) {
        compose.waitForIdle()
        val rendered = java.util.concurrent.CountDownLatch(1)
        compose.activity.runOnUiThread {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { rendered.countDown() }
            }
        }
        check(rendered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "composer-preview").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { screenshot.recycle() }
    }

    private fun hideIme() {
        compose.runOnIdle {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        awaitIme(false)
    }

    private fun assertImeStaysHidden() {
        val until = android.os.SystemClock.uptimeMillis() + 3_000L
        while (android.os.SystemClock.uptimeMillis() < until) {
            compose.runOnIdle {
                assertTrue("Dismissed IME reopened without a tap",
                    ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == false)
            }
            android.os.SystemClock.sleep(50)
        }
    }

    private fun awaitIme(visible: Boolean, decorView: android.view.View = compose.activity.window.decorView) {
        var stableSince = 0L
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                val insets = ViewCompat.getRootWindowInsets(decorView)
                val matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    (!visible || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom >
                        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + 300)
                if (!matches) stableSince = 0L
                else if (stableSince == 0L) stableSince = android.os.SystemClock.uptimeMillis()
                // Insets can arrive before the keyboard animation and editor layout settle.
                matches && android.os.SystemClock.uptimeMillis() - stableSince >= 300L
            }
        }
        compose.waitForIdle()
    }
}
