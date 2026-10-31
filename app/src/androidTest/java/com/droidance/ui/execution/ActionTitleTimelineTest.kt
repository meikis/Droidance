// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolated rendering fixtures: no Provider request, business-success assertion or device action. */
class ActionTitleTimelineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val progress = "已找到尺寸说明，正在核对材质。"
    private val taskStart = System.currentTimeMillis() - 60_000L
    private val roundStart = taskStart + 10_000L
    private val calls = listOf("打开筛选选项", "继续查看页面下方").mapIndexed { index, title ->
        ToolActivityReadModel("call-$index", "fixture", index + 1L, true, "round-0", "call-$index", null, null,
            ToolCallSource.MODEL_REQUESTED,
            ToolPresentationSnapshot("tool.screen_act", ToolPresentationCategory.PRIMARY, ResultImageRole.NONE, actionTitle = title),
            ToolActivityState.UNVERIFIED, null, null, true, roundStart + 10_000L * (index + 1),
            roundStart + 10_000L * (index + 1) + 1400L)
    }
    private val activity = TurnActivityReadModel("fixture", TurnReadModelState.RUNNING, calls, 2, "tool.screen_act",
        listOf(PublicProgressReadModel("progress", 0, progress, listOf("round-0")),
            PublicProgressReadModel("plain", 3, "正在整理资料。", listOf("no-calls"))), taskStart, null, null,
        executionRoundTimings = mapOf("round-0" to ExecutionRoundTimingReadModel("round-0", roundStart, roundStart + 21_400L)))
    private val displayedActivity = mutableStateOf(activity)
    private var originalConfiguration: Configuration? = null
    private val details = mutableStateOf(calls.associate { call -> call.callId to detail(call) })
    private val loaded = mutableListOf<String>()
    private val before = attachment("before", DetailAttachmentRole.INPUT_OBSERVATION)
    private val after = attachment("after", DetailAttachmentRole.RESULT)

    @Test fun progress_opens_a_secondary_sheet_with_readable_parameters_and_returns_to_unchanged_history() {
        showFixture()
        val original = compose.onNodeWithTag("execution-process-history").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("execution-progress-row:plain").assertHasNoClickAction()
        compose.onNodeWithText("打开筛选选项").assertDoesNotExist()
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithTag("execution-detail-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("execution-process-timing").assertCountEquals(1)
        assertCombinedTime(21_400L)
        compose.onNodeWithTag("execution-task-start-time").assertDoesNotExist()
        calls.forEach { compose.onNodeWithTag("execution-call-timing:${it.callId}").assertDoesNotExist() }
        compose.onNodeWithText("打开筛选选项").assertIsDisplayed()
        compose.onNodeWithText("100, 200", substring = true).assertIsDisplayed()
        compose.onNodeWithText("500, 800 → 500, 300", substring = true).assertIsDisplayed()
        compose.onNodeWithText("internal tool receipt").assertDoesNotExist()
        details.value.values.forEach { compose.onNodeWithText(it.argumentsJson).assertDoesNotExist() }
        saveScreenshot("execution-process-dialog.png")
        closeDetails()
        compose.onNodeWithTag("execution-detail-sheet").assertDoesNotExist()
        compose.onNodeWithText("打开筛选选项").assertDoesNotExist()
        assertEquals(original, compose.onNodeWithTag("execution-process-history").fetchSemanticsNode().boundsInRoot)
        compose.onAllNodesWithText(progress).assertCountEquals(1)
        saveScreenshot("execution-process-history.png")
    }

    @Test fun live_updates_and_repeated_close_or_back_never_leave_a_window_over_the_history() {
        showFixture()
        repeat(5) { index ->
            compose.onNodeWithText(progress).performClick()
            compose.onNodeWithTag("execution-detail-sheet").assertIsDisplayed()
            compose.waitUntil(5_000) { !compose.activity.hasWindowFocus() }
            compose.runOnIdle {
                val call = calls[0]
                details.value = details.value + (call.callId to detail(call).copy(outputText = "Receipt $index"))
            }
            if (index % 2 == 0) closeDetails() else {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
            compose.onNodeWithTag("execution-detail-sheet").assertDoesNotExist()
            compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
            compose.onNodeWithText(progress).assertIsDisplayed()
        }
    }

    @Test fun both_thumbnails_open_their_fullscreen_page_and_swipes_return_to_the_same_details() {
        withImages()
        showFixture()
        val surface = compose.onNodeWithTag("fixture-surface").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertTrue(loaded.isEmpty()) }
        compose.onNodeWithText(progress).performClick()
        compose.waitUntil { loaded.containsAll(listOf("after", "later")) }
        compose.onNodeWithTag("execution-image:before").assertDoesNotExist()
        compose.runOnIdle { assertTrue("before" !in loaded) }
        saveScreenshot("execution-process-images.png")
        compose.onNodeWithTag("execution-image:after").performClick()
        assertPage(1, 2)
        saveScreenshot("execution-gallery-after.png")
        val fullScreen = compose.onNodeWithTag("execution-image-gallery").fetchSemanticsNode().boundsInRoot
        assertTrue("Gallery width ${fullScreen.width}, surface width ${surface.width}", fullScreen.width >= surface.width - 1)
        assertTrue("Gallery height ${fullScreen.height}, surface height ${surface.height}", fullScreen.height >= surface.height - 1)
        compose.onNodeWithTag("execution-image-pager").performTouchInput { swipeLeft() }
        assertPage(2, 2)
        saveScreenshot("execution-gallery-later.png")
        compose.onNodeWithTag("execution-image-pager").performTouchInput { swipeRight() }
        assertPage(1, 2)
        // Incoming frames do not replace the image being inspected or move the current page.
        compose.runOnIdle {
            val first = details.value.getValue("call-0")
            details.value = details.value + (first.callId to first.copy(attachments = first.attachments +
                attachment("newer", DetailAttachmentRole.CURRENT_OBSERVATION)))
        }
        assertPage(1, 2)
        closeGallery()
        compose.onNodeWithTag("execution-detail-sheet").assertIsDisplayed()
        compose.onNodeWithTag("execution-image:later").performClick()
        assertPage(2, 3)
        closeGallery()
        closeDetails()
        compose.onNodeWithText(progress).assertIsDisplayed()
    }

    @Test fun an_unavailable_frame_does_not_block_swiping_to_another_frame() {
        withImages()
        showFixture(missingAfter = true)
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithTag("execution-image:after").performClick()
        assertPage(1, 2)
        compose.onNode(hasText(context.getString(R.string.execution_detail_screen_unavailable)) and
            hasAnyAncestor(hasTestTag("execution-image-gallery"))).assertIsDisplayed()
        compose.onNodeWithTag("execution-image-pager").performTouchInput { swipeLeft() }
        assertPage(2, 2)
        compose.onNodeWithTag("execution-gallery-page:later").assertIsDisplayed()
        closeGallery()
        closeDetails()
    }

    @Test fun batch_thumbnails_and_gallery_only_show_result_frames_without_before_or_step_screenshots() {
        val call = calls[0]
        val step3 = after.copy(attachmentId = "step3", sequence = 1, batchActionIndex = 3)
        val step1 = after.copy(attachmentId = "step1", sequence = 2, batchActionIndex = 1)
        val later = attachment("later", DetailAttachmentRole.CURRENT_OBSERVATION)
        displayedActivity.value = activity.copy(entries = listOf(call))
        details.value = mapOf(call.callId to detail(call).copy(
            attachments = listOf(later, step3, step1, before.copy(sequence = 3), after.copy(sequence = 4))))
        setFixtureLanguage(Locale.SIMPLIFIED_CHINESE)
        showFixture()
        compose.onNodeWithText(progress).performClick()
        listOf("before", "step1", "step3").forEach {
            compose.onNodeWithTag("execution-image:$it").assertDoesNotExist()
        }
        compose.waitUntil { loaded.containsAll(listOf("after", "later")) }
        compose.runOnIdle { assertTrue(loaded.none { it in listOf("before", "step1", "step3") }) }
        val afterBounds = compose.onNodeWithTag("execution-image:after").fetchSemanticsNode().boundsInRoot
        val laterBounds = compose.onNodeWithTag("execution-image:later").fetchSemanticsNode().boundsInRoot
        assertTrue(afterBounds.left < laterBounds.left)
        assertTrue(kotlin.math.abs(afterBounds.top - laterBounds.top) < 1f)
        saveScreenshot("execution-batch-result-images.png")
        compose.onNodeWithTag("execution-image:after").performClick()
        val ids = listOf("after", "later")
        val captions = listOf(context.getString(R.string.execution_image_after),
            context.getString(R.string.execution_image_current))
        ids.forEachIndexed { index, id ->
            assertPage(index + 1, ids.size)
            compose.onNodeWithTag("execution-gallery-page:$id").assertIsDisplayed()
            compose.onNode(hasText(captions[index]) and hasAnyAncestor(hasTestTag("execution-image-gallery")))
                .assertIsDisplayed()
            if (index < ids.lastIndex) compose.onNodeWithTag("execution-image-pager").performTouchInput { swipeLeft() }
        }
        saveScreenshot("execution-batch-gallery-after.png")
        closeGallery()
        closeDetails()
    }

    @Test fun partial_batch_failure_displays_progress_failed_step_and_retained_screenshot_in_both_languages() {
        val call = calls[0].copy(state = ToolActivityState.FAILED)
        displayedActivity.value = activity.copy(entries = listOf(call))
        details.value = mapOf(call.callId to detail(call).copy(
            argumentsJson = """{"actions":[{"op":"scroll","direction":"down"},{"op":"screenshot"}]}""",
            executionStatus = ToolExecutionStatus.FAILED, diagnosticCode = "BATCH_CAPTURE_FAILED",
            metrics = mapOf("batch_requested_actions" to 2, "batch_completed_actions" to 1, "batch_attempted_actions" to 2),
            attachments = listOf(after.copy(attachmentId = "step2", batchActionIndex = 2), before, after)))
        setFixtureLanguage(Locale.SIMPLIFIED_CHINESE)
        showFixture()
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.US).forEach { language ->
            setFixtureLanguage(language)
            compose.onNodeWithText(progress).performClick()
            compose.onNodeWithTag("execution-outcome:${call.callId}").assertTextEquals(listOf(
                context.resources.getQuantityString(R.plurals.execution_batch_progress, 2, 1, 2),
                context.getString(R.string.execution_batch_failed_step, 2),
                context.getString(R.string.execution_failure_observation_unavailable)).joinToString(" "))
            compose.onNodeWithTag("execution-image:step2").assertDoesNotExist()
            compose.onNodeWithTag("execution-image:before").assertDoesNotExist()
            compose.onNodeWithTag("execution-image:after").assertIsDisplayed()
            compose.onNodeWithText("internal tool receipt").assertDoesNotExist()
            saveScreenshot("execution-batch-partial-${language.language}.png")
            closeDetails()
        }
    }

    @Test fun rejected_batch_and_post_action_timeout_distinguish_no_execution_from_unconfirmed_result() {
        val call = calls[0].copy(state = ToolActivityState.REJECTED)
        displayedActivity.value = activity.copy(entries = listOf(call))
        val receipt = detail(call).copy(argumentsJson = """{"actions":[{"op":"click","x":100,"y":200}]}""",
            executionStatus = ToolExecutionStatus.NOT_EXECUTED, diagnosticCode = "ARGUMENT_SCHEMA_MISMATCH")
        details.value = mapOf(call.callId to receipt)
        setFixtureLanguage(Locale.US)
        showFixture()
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithTag("execution-outcome:${call.callId}").assertTextContains(
            context.getString(R.string.execution_batch_not_executed), substring = true)
        compose.onNodeWithTag("execution-outcome:${call.callId}").assertTextContains(
            context.getString(R.string.execution_failure_arguments), substring = true)
        closeDetails()
        compose.runOnIdle {
            displayedActivity.value = activity.copy(entries = listOf(call.copy(state = ToolActivityState.UNVERIFIED)))
            details.value = mapOf(call.callId to receipt.copy(executionStatus = ToolExecutionStatus.INTERRUPTED,
                diagnosticCode = "TOOL_EXECUTION_TIMEOUT", metrics = mapOf("batch_requested_actions" to 1,
                    "batch_completed_actions" to 1, "batch_attempted_actions" to 1)))
        }
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithTag("execution-outcome:${call.callId}").assertTextContains(
            context.getString(R.string.execution_result_unconfirmed), substring = true)
        compose.onNodeWithText(context.getString(R.string.execution_batch_failed_step, 1), substring = true).assertDoesNotExist()
        closeDetails()
    }

    @Test fun thinking_and_actions_tick_together_then_keep_one_final_duration() {
        val startedAt = System.currentTimeMillis() - 12_000L
        val running = calls[0].copy(state = ToolActivityState.PENDING, startedAt = startedAt + 10_000L, finishedAt = null)
        displayedActivity.value = activity.copy(entries = listOf(running), executionRoundTimings = mapOf(
            "round-0" to ExecutionRoundTimingReadModel("round-0", startedAt, null)))
        details.value = emptyMap()
        showFixture()
        compose.onNodeWithText(progress).performClick()
        val timing = compose.onNodeWithTag("execution-process-timing")
        val initial = timing.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        compose.waitUntil(timeoutMillis = 4000L) {
            timing.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text != initial
        }
        compose.runOnIdle {
            displayedActivity.value = displayedActivity.value.copy(entries = listOf(
                running.copy(state = ToolActivityState.VERIFIED, finishedAt = startedAt + 11_400L)),
                executionRoundTimings = mapOf("round-0" to ExecutionRoundTimingReadModel("round-0", startedAt, startedAt + 11_400L)))
        }
        timing.assertTextContains(context.getString(R.string.execution_duration_seconds, "11.4"), substring = true)
        saveScreenshot("execution-process-timing-completed.png")
        closeDetails()
    }

    @Test fun missing_round_time_shows_one_explicit_status_without_action_duration() {
        val missing = calls[0].copy(startedAt = null, finishedAt = null)
        val rejected = calls[1].copy(state = ToolActivityState.REJECTED, startedAt = null, finishedAt = null)
        displayedActivity.value = activity.copy(entries = listOf(missing, rejected), executionRoundTimings = emptyMap())
        details.value = emptyMap()
        showFixture()
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithTag("execution-process-timing")
            .assertTextEquals(context.getString(R.string.execution_process_duration_unrecorded))
        compose.onAllNodesWithTag("execution-process-timing").assertCountEquals(1)
        closeDetails()
    }

    @Test fun timing_metadata_follows_chinese_english_and_system_locale_changes() {
        setFixtureLanguage(Locale.US)
        showFixture()
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithTag("execution-process-timing").assertTextContains("Started at", substring = true)
        compose.onNodeWithTag("execution-process-timing").assertTextContains("duration 21.4 s", substring = true)
        saveScreenshot("execution-process-timing-english.png")
        closeDetails()
        setFixtureLanguage(Locale.SIMPLIFIED_CHINESE)
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithTag("execution-process-timing").assertTextContains("开始于", substring = true)
        compose.onNodeWithTag("execution-process-timing").assertTextContains("耗时 21.4秒", substring = true)
        saveScreenshot("execution-process-timing-chinese.png")
        closeDetails()
        restoreFixtureLanguage()
        compose.onNodeWithText(progress).performClick()
        assertCombinedTime(21_400L)
        closeDetails()
    }

    @Test fun a_model_only_process_can_open_and_show_thinking_time() {
        displayedActivity.value = activity.copy(executionRoundTimings = activity.executionRoundTimings +
            ("no-calls" to ExecutionRoundTimingReadModel("no-calls", roundStart + 22_000L, roundStart + 25_000L)))
        showFixture()
        compose.onNodeWithTag("execution-progress-row:plain").performClick()
        compose.onNodeWithTag("execution-detail-sheet").assertIsDisplayed()
        compose.onNodeWithTag("execution-process-timing").assertTextContains(
            context.getString(R.string.execution_duration_seconds, "3"), substring = true)
        compose.onAllNodes(hasTestTag("execution-call:call-0")).assertCountEquals(0)
        closeDetails()
    }

    @Test fun shell_steps_show_commands_and_outputs_directly_without_metadata_or_disclosure() {
        val call = calls[0].copy(presentation = calls[0].presentation.copy(labelResourceKey = "tool.shell",
            actionTitle = "Duplicate command title"))
        val command = "printf 'example output'"
        displayedActivity.value = activity.copy(entries = listOf(call))
        val receipt = detail(call).copy(technicalName = "shell", commandSource = command,
            argumentsJson = """{"command":"<redacted>"}""",
            outputText = """{"stdout":"example output","stderr":"warning output","exit_code":0}""")
        details.value = mapOf(call.callId to receipt)
        setFixtureLanguage(Locale.SIMPLIFIED_CHINESE)
        showFixture(darkMode = true)
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithTag("execution-command-toggle:${call.callId}").assertDoesNotExist()
        compose.onNodeWithText("Duplicate command title").assertDoesNotExist()
        compose.onNodeWithText("<redacted>", substring = true).assertDoesNotExist()
        compose.onNodeWithText(command).assertIsDisplayed()
        compose.onNodeWithText("example output").assertIsDisplayed()
        compose.onNodeWithText("warning output").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.execution_command_stdout)).assertHasNoClickAction()
        assertNoCommandMetadata()
        saveScreenshot("execution-shell-direct.png")
        compose.runOnIdle {
            details.value = mapOf(call.callId to receipt.copy(outputText =
                """{"stdout":"updated output","stderr":"actual error","exit_code":127}""",
                executionStatus = ToolExecutionStatus.FAILED))
        }
        compose.onNodeWithText("updated output").assertIsDisplayed()
        compose.onNodeWithText("actual error").assertIsDisplayed()
        assertNoCommandMetadata()
        closeDetails()
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithText(command).assertIsDisplayed()
        compose.onNodeWithText("updated output").assertIsDisplayed()
        closeDetails()
    }

    @Test fun native_calls_show_the_actual_arguments_and_entire_retained_output_without_disclosure() {
        val call = calls[0].copy(presentation = calls[0].presentation.copy(labelResourceKey = "tool.android_read",
            actionTitle = "读取设备电池信息"))
        val arguments = """{"op":"device"}"""
        val output = "[ANDROID_DEVICE_RESULT]\n" +
            """{"source_kind":"android_platform_api","battery_percent":79,"battery_status":"not_charging","battery_temperature_c":32.4,"battery_voltage_mv":4006}"""
        displayedActivity.value = activity.copy(entries = listOf(call))
        details.value = mapOf(call.callId to detail(call).copy(technicalName = "android_read",
            argumentsJson = arguments, outputText = output))
        setFixtureLanguage(Locale.SIMPLIFIED_CHINESE)
        showFixture(darkMode = true)
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithText("android_read($arguments)").assertIsDisplayed()
        compose.onNodeWithText(output).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.execution_call)).assertHasNoClickAction()
        compose.onNodeWithText(context.getString(R.string.execution_command_stdout)).assertHasNoClickAction()
        compose.onNodeWithTag("execution-command-toggle:${call.callId}").assertDoesNotExist()
        assertNoCommandMetadata()
        saveScreenshot("execution-api-direct.png")
        closeDetails()
    }

    @Test fun a_permission_denial_shows_the_actual_native_call_and_returned_reason() {
        val call = calls[0].copy(state = ToolActivityState.REJECTED,
            presentation = calls[0].presentation.copy(labelResourceKey = "tool.android_act", actionTitle = null))
        val arguments = """{"op":"content","action":"delete","uri":"content://example/rows","selection":"_id=?","selection_args":["12"]}"""
        val output = """{"code":"PERMISSION_DENIED","details":{"permissions":["android.permission.WRITE_CONTACTS"],"platform_status":{"android.permission.WRITE_CONTACTS":"denied"}}}"""
        displayedActivity.value = activity.copy(entries = listOf(call))
        details.value = mapOf(call.callId to detail(call).copy(technicalName = "android_act",
            argumentsJson = arguments, outputText = output, executionStatus = ToolExecutionStatus.NOT_EXECUTED))
        setFixtureLanguage(Locale.US)
        showFixture()
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithText("android_act($arguments)").assertIsDisplayed()
        compose.onNodeWithText(output).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.execution_step_not_completed)).assertDoesNotExist()
        compose.onNodeWithTag("execution-command-toggle:${call.callId}").assertDoesNotExist()
        assertNoCommandMetadata()
        saveScreenshot("execution-permission-direct-english.png")
        closeDetails()
    }

    @Test fun program_and_workspace_steps_use_the_same_single_disclosure() {
        val programCall = calls[0].copy(presentation = calls[0].presentation.copy(labelResourceKey = "tool.program"))
        val workspaceCall = calls[1].copy(presentation = calls[1].presentation.copy(labelResourceKey = "tool.workspace"))
        displayedActivity.value = activity.copy(entries = listOf(programCall, workspaceCall))
        details.value = mapOf(
            programCall.callId to detail(programCall).copy(technicalName = "program", commandSource = "console.log('script output');",
                outputText = """{"status":"exited","stdout":{"text":"script output"},"error":""}"""),
            workspaceCall.callId to detail(workspaceCall).copy(technicalName = "workspace", commandSource = "read notes.txt",
                outputText = "file contents")
        )
        setFixtureLanguage(Locale.US)
        showFixture()
        compose.onNodeWithText(progress).performClick()
        compose.onNodeWithText("script output").assertDoesNotExist()
        compose.onNodeWithText("file contents").assertDoesNotExist()
        compose.onNodeWithTag("execution-command-toggle:${programCall.callId}").performClick()
        compose.onNodeWithText("console.log('script output');").assertIsDisplayed()
        compose.onNodeWithText("script output").assertIsDisplayed()
        compose.onNodeWithTag("execution-command-toggle:${programCall.callId}").performClick()
        compose.onNodeWithTag("execution-command-toggle:${workspaceCall.callId}").performClick()
        compose.onNodeWithText("read notes.txt").assertIsDisplayed()
        compose.onNodeWithText("file contents").assertIsDisplayed()
        assertNoCommandMetadata()
        saveScreenshot("execution-workspace-expanded-english.png")
        closeDetails()
    }

    private fun assertNoCommandMetadata() {
        listOf("退出码", "Exit code", "命令已完成", "Command completed", "已结束", "输出 2 行", "节选").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    private fun assertCombinedTime(durationMs: Long) {
        val locale = compose.activity.resources.configuration.locales[0]
        val start = formatExecutionStartTime(roundStart, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), locale)
        val number = java.text.NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            isGroupingUsed = false
        }.format(durationMs / 1000.0)
        compose.onNodeWithTag("execution-process-timing").assertTextEquals(context.getString(
            R.string.execution_process_timing, start, context.getString(R.string.execution_duration_seconds, number)))
    }

    private fun setFixtureLanguage(language: Locale) {
        compose.activityRule.scenario.onActivity { activity ->
            if (originalConfiguration == null) originalConfiguration = Configuration(activity.resources.configuration)
            applyFixtureConfiguration(Configuration(activity.resources.configuration).apply { setLocale(language) })
        }
    }

    @After fun restoreFixtureLanguage() {
        originalConfiguration?.let { configuration ->
            compose.activityRule.scenario.onActivity { applyFixtureConfiguration(configuration) }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyFixtureConfiguration(configuration: Configuration) {
        compose.activity.resources.updateConfiguration(configuration, compose.activity.resources.displayMetrics)
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
        compose.activity.window.decorView.dispatchConfigurationChanged(configuration)
    }

    private fun showFixture(missingAfter: Boolean = false, darkMode: Boolean = false) {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val bytes = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.LTGRAY)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply { color = Color.DKGRAY; textSize = 15f; isAntiAlias = true }
            canvas.drawText("Screen fixture", 18f, 38f, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(18f, 65f, 162f, 155f, 8f, 8f, paint)
            paint.color = Color.GRAY
            repeat(4) { canvas.drawRect(18f, 180f + it * 22f, 150f, 187f + it * 22f, paint) }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        compose.setContent {
                MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize().testTag("fixture-surface")) {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 64.dp)) {
                            Text("执行过程 · 界面验收样例", style = MaterialTheme.typography.titleMedium)
                            ExecutionProcessHistory(displayedActivity.value, displayedActivity.value.publicProgressHistory, details.value, loadAttachment = {
                                loaded += it.attachmentId
                                if (missingAfter && it.attachmentId == "after") null else bytes
                            })
                        }
                    }
                }
        }
    }

    private fun withImages() {
        val first = details.value.getValue("call-0")
        details.value = details.value + (first.callId to first.copy(attachments = listOf(
            before, after, attachment("later", DetailAttachmentRole.CURRENT_OBSERVATION))))
    }
    private fun closeDetails() = compose.onNodeWithContentDescription(context.getString(R.string.execution_close_details)).performClick()
    private fun closeGallery() = compose.onNodeWithContentDescription(context.getString(R.string.execution_close_screen)).performClick()
    private fun assertPage(page: Int, total: Int) {
        compose.onNodeWithTag("execution-image-position").assertTextEquals(context.getString(R.string.execution_image_position, page, total))
    }
    private fun attachment(id: String, role: DetailAttachmentRole) = DetailAttachmentReadModel(
        id, id, if (id == "before") 1 else 2, role, id, id, 1, "image/png", 180, 320, null)
    private fun detail(call: ToolActivityReadModel) = ToolDetailReadModel(
        threadId = "fixture", turnId = "fixture", roundId = "round-0", callId = call.callId,
        providerCallId = call.callId, providerOrder = 0, technicalName = "screen_act",
        argumentsJson = if (call.callId == "call-0") """{"actions":[{"op":"click","x":100,"y":200}]}"""
            else """{"actions":[{"op":"swipe","startX":500,"startY":800,"endX":500,"endY":300}]}""",
        source = ToolCallSource.MODEL_REQUESTED, binding = ToolBinding.TURN_LOCAL, effect = ToolEffect.STATE_CHANGING,
        presentation = call.presentation, roundDetailRef = "detail", attemptId = null, executionId = null,
        backendSessionId = null, displayId = null, outputText = "internal tool receipt",
        executionStatus = ToolExecutionStatus.SUCCEEDED, evidenceStatus = ToolEvidenceStatus.UNKNOWN,
        actionEvidence = null, observationId = null, diagnosticCode = null, metrics = emptyMap(),
        publicProgressBlocks = emptyList(), startedAt = call.startedAt, finishedAt = call.finishedAt,
        exclusiveDurationMillis = 1400L, attachments = emptyList())
    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
