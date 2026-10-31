// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenSourceContractTest {
    private val source = File("src/main/java/com/droidance/ui/screens/HomeScreen.kt").readText()
    private val composerSource = File("src/main/java/com/droidance/ui/screens/home/ChatComposer.kt").takeIf(File::exists)?.readText().orEmpty()
    private val messageSource = File("src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt").takeIf(File::exists)?.readText().orEmpty()
    private val viewModelSource = File("src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
    private val actionIconSource = File("src/main/java/com/droidance/ui/screens/home/ConversationActionIcon.kt").takeIf(File::exists)?.readText().orEmpty()
    private val filePaths = File("src/main/res/xml/file_paths.xml").takeIf(File::exists)?.readText().orEmpty()
    private val zhStrings = File("src/main/res/values-zh/strings.xml").readText()

    @Test
    fun top_bar_uses_new_chat_action_instead_of_share_menu() {
        val actionBody = source.substringAfter("actions = {")
            .substringBefore("\n                },\n                colors =")

        assertTrue(source.contains("NewConversationIcon"))
        assertTrue(source.contains("contentDescription = stringResource(R.string.home_new_conversation)"))
        assertTrue(actionBody.contains("tint = MaterialTheme.colorScheme.onBackground"))
        assertTrue(actionBody.contains("shape = AppShapes.Control"))
        assertTrue(actionBody.contains("color = Color.Transparent"))
        assertFalse(actionBody.contains("onBackground.copy(alpha = 0.065f)"))
        assertFalse(source.contains("R.drawable.ic_conversation_new"))
        assertFalse(actionBody.contains("tint = DroidancePrimary"))
        assertFalse(source.contains("DroidanceIcons.MoreVertical"))
    }

    @Test
    fun empty_home_does_not_repeat_input_guidance_above_the_composer() {
        assertFalse(source.contains("R.string.home_input_guidance"))
        assertFalse(zhStrings.contains("name=\"home_input_guidance\""))
    }

    @Test
    fun composer_input_keeps_natural_gap_to_send_button_without_artificial_negative_offset() {
        assertFalse(composerSource.contains(".offset(x = (-16).dp)"))
        assertFalse(composerSource.contains(".offset(x = (-8).dp)"))
        assertTrue(composerSource.contains(".weight(1f)"))
    }

    @Test
    fun new_chat_navigation_preserves_the_application_owned_task() {
        assertFalse(source.contains("viewModel.startNewConversation()"))
        assertTrue(source.contains("onNewConversation()"))
    }

    @Test
    fun malformedEventStoreThreadIsVisibleAndCannotBeContinued() {
        assertTrue(viewModelSource.contains("eventStore.observeThreadProjectionResult(conversationId)"))
        assertTrue(viewModelSource.contains("AgentThreadProjectionResult.Quarantined"))
        assertTrue(viewModelSource.contains("if (rejectQuarantinedThreadWrite()) return"))
        assertTrue(source.contains("ThreadProjectionFailureNotice("))
        assertTrue(source.contains("R.string.home_thread_quarantined_title"))
        assertTrue(zhStrings.contains("原始事件已保留"))
    }

    @Test
    fun chat_rows_use_minimal_unframed_layout() {
        assertFalse(source.contains("bottomStart = if (isUser) 16.dp else 4.dp"))
        assertFalse(source.contains("modifier = Modifier.widthIn(max = 300.dp)"))
    }

    @Test
    fun input_row_keeps_controls_vertically_centered() {
        assertTrue(composerSource.contains("verticalAlignment = Alignment.CenterVertically"))
    }

    @Test
    fun titleHasNoStatusLampAndConversationActionsShareOneVisualSize() {
        val titleBody = source.substringAfter("title = {").substringBefore("actions = {")

        assertFalse(titleBody.contains("isTaskRunning"))
        assertFalse(titleBody.contains(".size(8.dp)"))
        assertTrue(composerSource.contains("modifier = Modifier.size(ComposerIconButtonSize)"))
        assertTrue(composerSource.contains(".size(ComposerActionControlSize)"))
        assertTrue(actionIconSource.contains("ConversationActionGlyphSize = 18.dp"))
    }

    @Test
    fun legacy_process_timeline_is_absent() {
        assertFalse(messageSource.contains("private fun TaskCardBubble("))
        assertFalse(messageSource.contains("executionEvents"))
    }

    @Test
    fun regular_chat_messages_use_simple_bubble_surfaces_again() {
        assertTrue(messageSource.contains("private fun SimpleMessageBubble("))
        assertTrue(messageSource.contains("SimpleMessageBubble(isUser = true,"))
        assertTrue(messageSource.contains("SimpleMessageBubble(isUser = false)"))
    }

    @Test
    fun eventStoreLocalTurnDoesNotFallBackToTheLegacyExecutionCard() {
        val bubble = messageSource.substringAfter("internal fun ChatBubble(")
            .substringBefore("private fun formatMessageTimestamp(")

        assertTrue(bubble.contains("val durableTurnPresentation ="))
        assertTrue(bubble.contains("val durablePresentation = durableTurnPresentation?.takeIf"))
        assertTrue(bubble.contains("if (durablePresentation != null)"))
        assertTrue(bubble.contains("EventStoreTaskCardBubble("))
        assertFalse(bubble.contains("private fun TaskCardBubble("))
    }

    @Test
    fun composer_uses_a_plain_surface_instead_of_liquid_glass() {
        val body = composerSource.substringAfter("internal fun ChatInputBar(")
            .substringBefore("\n@Composable\nprivate fun SkillPickerRow(")

        assertFalse(body.contains("LiquidGlassSurface("))
        assertTrue(body.contains("private fun ChatComposerSurface("))
    }

    @Test
    fun composerKeepsSteeringAvailableWhileExecutionRuns() {
        val body = composerSource.substringAfter("internal fun ChatInputBar(")
            .substringBefore("\n@Composable\nprivate fun SkillPickerRow(")

        assertTrue(body.contains("focusManager.clearFocus(force = true)"))
        assertTrue(body.contains("keyboardController?.hide()"))
        assertTrue(body.contains("if (!isProcessing)"))
        assertFalse(body.contains("LaunchedEffect(isProcessing)"))
        assertFalse(body.contains("DroidanceIcons.Stop"))
        assertFalse(body.contains("onStop:"))
        assertTrue(source.contains("viewModel.sendMessage(text, submissionMode = mode, requestedSkills = selected)"))
        assertTrue(body.contains("ConversationSubmissionMode.QUEUE"))
        assertFalse(body.contains("enabled = isEnabled && !isProcessing"))
        assertFalse(body.contains("isProcessing && pendingImageUris.isNotEmpty()"))
    }

    @Test
    fun composer_lives_outside_the_home_screen_entry_file() {
        assertFalse(source.contains("private fun ChatInputBar("))
        assertTrue(composerSource.contains("internal fun ChatInputBar("))
        assertTrue(composerSource.contains("private fun AttachmentPanel("))
    }

    @Test
    fun composer_empty_state_keeps_a_frozen_send_button_without_a_plus_button() {
        val body = composerSource.substringAfter("internal fun ChatInputBar(")
            .substringBefore("\n/** Fixed thumbnail geometry")

        assertFalse("输入框右侧空态不应再显示 + 号", body.contains("DroidanceIcons.Add"))
        assertFalse("输入框右侧空态不应再显示更多入口", body.contains("stringResource(R.string.composer_more)"))
        assertTrue(body.contains("val canSubmit ="))
        assertTrue(body.contains("enabled = canSubmit"))
        assertTrue(body.contains("R.drawable.ic_conversation_send"))
        assertTrue(body.contains("onSurface.copy(alpha = 0.035f)"))
    }

    @Test
    fun composer_keeps_the_empty_input_clear_without_recovery_shortcuts() {
        val body = composerSource.substringAfter("internal fun ChatInputBar(")
            .substringBefore("\n/** Fixed thumbnail geometry")

        assertFalse(body.contains("stringResource(R.string.composer_placeholder)"))
        assertFalse(body.contains("composer_steer_placeholder"))
        assertFalse(body.contains("recoveryAction"))
        assertFalse(body.contains("onRecoveryAction"))
        assertFalse(body.contains("modelSuggestions"))
        assertFalse(body.contains("composer_ai_suggestions"))
        assertFalse(zhStrings.contains("智能体会调整当前任务"))
        assertFalse(zhStrings.contains("继续尝试"))
        assertFalse(zhStrings.contains("请基于当前上下文继续尝试"))
    }

    @Test
    fun final_result_uses_plain_markdown_without_a_card_or_generated_bullets() {
        val body = messageSource.substringAfter("private fun StructuredResultCard(")
            .substringBefore("\n@Composable\ninternal fun AppTakeoverLogCard(")

        assertTrue(body.contains("FlatMarkdown("))
        assertTrue(body.contains("SelectionContainer"))
        assertFalse(body.contains("Surface("))
        assertFalse(body.contains("BorderStroke("))
        assertFalse(body.contains("""append("- ")"""))
    }

    @Test
    fun waiting_has_wave_dots_without_execution_controls() {
        val body = messageSource.substringAfter("isThinking && text.isEmpty() ->")
            .substringBefore("\n                else ->")

        assertTrue(body.contains("WaitingResponseBubble(onCancel ="))
        assertTrue(body.contains("onCancel = onStop.takeIf { isQueued }"))
        assertFalse(body.contains("onStop = onStop"))
        assertFalse(messageSource.contains("ModelResponseWaitingStatus"))
        assertFalse(body.contains("PendingTurnProcessBubble("))
        assertFalse(body.contains("RunningProcessStatusRow("))
        val waiting = messageSource.substringAfter("private fun WaitingResponseBubble(")
            .substringBefore("private fun ResponseWaveDots(")
        assertTrue(waiting.contains("ResponseWaveDots("))
        assertFalse(waiting.contains("R.string.chat_elapsed"))
        assertFalse(waiting.contains("R.string.chat_status_preparing"))
        assertFalse(body.contains("\"...\""))
    }

    @Test
    fun latest_virtual_execution_keeps_its_viewer_entry_after_streaming_finishes() {
        val call = source.substringAfter("ChatBubble(")
            .substringBefore("onExecutionLayoutChange = onExecutionLayoutChange")

        assertTrue(source.contains("RetainedVirtualExecutionOwnerPolicy.owns("))
        assertTrue(source.contains("retainedVirtualExecution?.threadId, viewModel.conversationId"))
        assertTrue(call.contains("canOpenVirtualSpace = ownsRetainedVirtualExecution"))
        assertFalse(call.contains("canOpenVirtualSpace = isLastMessage"))
        assertFalse(call.contains("canOpenVirtualSpace = isStreaming &&"))
    }

    @Test
    fun completed_process_rows_render_the_retained_viewer_entry_without_a_stop_button() {
        val completed = messageSource.substringAfter("private fun CompletedProcessStatusRow(")
            .substringBefore("\ninternal fun computeProcessDurationMs(")

        assertTrue(
            Regex("canOpenVirtualSpace = canOpenVirtualSpace").findAll(messageSource).count() >= 4
        )
        assertTrue(completed.contains("canOpenVirtualSpace: Boolean"))
        assertTrue(completed.contains("VirtualSpaceOpenControl("))
        assertFalse(completed.contains("ExecutionLogControls("))
        assertFalse(completed.contains("onStop"))
    }

    @Test
    fun localInterventionCannotOpenAnExecutionProcessCard() {
        val eventStoreCard = messageSource.substringAfter("private fun EventStoreTaskCardBubble(")
            .substringBefore("private fun StructuredResultCard(")

        assertTrue(eventStoreCard.contains("presentation.showExecutionLog()"))
        assertTrue(messageSource.contains("it.hasDurablePresentation"))
    }

    @Test
    fun chat_message_list_keeps_compact_vertical_spacing() {
        assertTrue(source.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"))
    }

    @Test
    fun execution_history_keeps_public_narration_and_receipts_in_one_disclosure() {
        val timeline = File("src/main/java/com/droidance/ui/execution/EventStoreProcessTimeline.kt").readText()
        assertTrue(timeline.contains("ExecutionDetailSheet("))
        assertFalse(timeline.contains("publicProgressBlocks"))
        assertFalse(timeline.contains("chat_view_operation_details"))
        assertFalse(timeline.contains("detail.outputText"))
        assertFalse(timeline.contains("execution_detail_parameters"))
        assertFalse(timeline.contains("execution_operation_records"))
        val gallery = File("src/main/java/com/droidance/ui/execution/ExecutionImageGallery.kt").readText()
        assertTrue(gallery.contains("contentScale = ContentScale.Fit"))
        assertTrue(gallery.contains("loadAttachment(attachment)"))
    }

    @Test
    fun historical_timeline_images_are_loaded_lazily_from_secondary_details() {
        assertTrue(source.contains("loadTimelineAttachment = viewModel::loadTimelineAttachment"))
        assertTrue(messageSource.contains("loadAttachment = loadTimelineAttachment"))
        assertTrue(viewModelSource.contains("suspend fun loadTimelineAttachment("))
        assertFalse(viewModelSource.contains("latestTurnId"))
        assertFalse(viewModelSource.contains("attachmentImages"))
    }

    @Test
    fun attachment_panel_uses_camera_capture_instead_of_screenshot() {
        assertTrue(composerSource.contains("ActivityResultContracts.TakePicture()"))
        assertTrue(composerSource.contains("ActivityResultContracts.GetMultipleContents()"))
        assertTrue(composerSource.contains("createComposerCameraUri(context)"))
        assertTrue(composerSource.contains("pendingImageUris = pendingImageUris + uri"))
        assertTrue(composerSource.contains("stringResource(R.string.composer_camera)"))
        assertTrue(filePaths.contains("""<cache-path name="camera" path="camera/" />"""))
        assertFalse(composerSource.contains("onScreenshot"))
        assertFalse(composerSource.contains("composer_screenshot"))
    }

    @Test
    fun image_messages_support_multiple_images_without_default_prompt_text() {
        val viewModel = File("src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt").readText()
        val messageModel = File("../core/model/src/main/kotlin/com/droidance/core/model/llm/models/ChatMessage.kt").readText()
        val router = File("../core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").readText()

        assertTrue(composerSource.contains("onSendImages: (String, List<Uri>, ConversationSubmissionMode, List<com.droidance.core.agent.RequestedSkill>)"))
        assertTrue(viewModel.contains("fun sendMessageWithImages(text: String, imageUris: List<Uri>,"))
        assertTrue(viewModel.contains("images = base64List"))
        val imageSubmission = viewModel.substringAfter("fun sendMessageWithImages(").substringBefore("fun sendMessage(")
        assertTrue(imageSubmission.indexOf("captureComposerOptions()") < imageSubmission.indexOf("submitMessage("))
        assertTrue(imageSubmission.contains("request.copy(images = base64List)"))
        assertTrue(imageSubmission.contains("modelPlan = options.modelPlan"))
        assertTrue(imageSubmission.contains("executionSpace = options.space"))
        assertTrue(messageModel.contains("val imageBase64List: List<String> = emptyList()"))
        assertTrue(router.contains("images.forEach { imageBase64 ->"))
        assertFalse(viewModel.contains("请描述这张图片"))
        assertFalse(router.contains("Analyze the screenshot."))
    }

    @Test
    fun execution_process_defaults_to_collapsed_during_running() {
        assertTrue(messageSource.contains("var expanded by remember(presentation.messageId) { mutableStateOf(false) }"))
        assertFalse(messageSource.contains("mutableStateOf(isStreaming)"))
        assertFalse(messageSource.contains("mutableStateOf(card.process.isNotBlank())"))
    }

    @Test
    fun running_stop_button_uses_neutral_icon_color() {
        assertFalse(source.contains("tint = DroidanceError"))
        assertFalse(source.contains("color = DroidanceError"))
        assertFalse(composerSource.contains("tint = DroidanceError"))
        val controls = messageSource.substringAfter("private fun ExecutionLogControls(")
            .substringBefore("private fun CompletedProcessStatusRow(")
        assertTrue(controls.contains("R.drawable.ic_conversation_stop"))
        assertTrue(controls.contains("contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)"))
    }

    @Test
    fun composer_attachment_add_is_full_contrast_and_send_keeps_a_circular_container() {
        val attachmentBody = composerSource.substringAfter("R.drawable.ic_conversation_add,")
            .substringBefore("val canSubmit =")
        val sendBody = composerSource.substringAfter("val canSubmit =")
            .substringBefore("AnimatedVisibility(")

        assertTrue(attachmentBody.contains("tint = MaterialTheme.colorScheme.onSurface"))
        assertFalse(attachmentBody.contains("onSurface.copy(alpha"))
        assertTrue(sendBody.contains("modifier = Modifier.size(ComposerIconButtonSize)"))
        assertTrue(sendBody.contains(".size(ComposerActionControlSize)"))
        assertTrue(sendBody.contains("shape = CircleShape"))
        assertTrue(sendBody.contains("BorderStroke("))
        assertTrue(sendBody.contains("enabled = canSubmit"))
    }

    @Test
    fun composer_send_button_uses_theme_primary_fill_and_glyph_when_submittable() {
        val sendBody = composerSource.substringAfter("val canSubmit =")
            .substringBefore("AnimatedVisibility(")

        // Dark primary/onPrimary are the near-white fill and near-black glyph; state changes cross-fade.
        assertTrue(sendBody.contains("if (canSubmit) MaterialTheme.colorScheme.primary"))
        assertTrue(sendBody.contains("if (canSubmit) MaterialTheme.colorScheme.onPrimary"))
        assertTrue(sendBody.contains("animateColorAsState("))
        assertFalse(sendBody.contains("Color.White"))
    }

    @Test
    fun composer_surrounding_surface_can_focus_the_editor() {
        assertTrue(composerSource.contains("focusRequester.requestFocus()"))
        assertTrue(composerSource.contains("keyboardController?.show()"))
        assertTrue(composerSource.contains(".focusRequester(focusRequester)"))
        assertTrue(source.contains("focusManager.clearFocus()"))
    }

    @Test
    fun conversation_actions_use_one_local_line_icon_system() {
        val resourceNames = listOf("add", "copy", "camera", "image", "send", "open", "check")
        resourceNames.forEach { name ->
            val vector = File("src/main/res/drawable/ic_conversation_${name}.xml").readText()
            assertTrue("$name must use the shared 1.5 line weight", vector.contains("android:strokeWidth=\"1.5\""))
            assertTrue("$name must remain an outline icon", vector.contains("android:fillColor=\"@android:color/transparent\""))
            assertTrue("$name must use round caps", vector.contains("android:strokeLineCap=\"round\""))
            assertTrue("$name must use round joins", vector.contains("android:strokeLineJoin=\"round\""))
        }
        val stop = File("src/main/res/drawable/ic_conversation_stop.xml").readText()
        assertTrue(stop.contains("android:fillColor=\"#FFFFFFFF\""))
        assertFalse(stop.contains("android:strokeWidth"))

        assertTrue(actionIconSource.contains("ConversationActionGlyphSize = 18.dp"))
        assertTrue(actionIconSource.contains("ConversationActionControlSize = 36.dp"))
        assertTrue(actionIconSource.contains("NewConversationIcon"))
        assertTrue(actionIconSource.contains("ImageVector.Builder"))
        assertTrue(source.contains("ConversationActionGlyph("))
        assertTrue(source.contains("imageVector = NewConversationIcon"))
        assertFalse(File("src/main/res/drawable/ic_conversation_new.xml").exists())
        assertTrue(composerSource.contains("R.drawable.ic_conversation_add"))
        assertTrue(composerSource.contains("R.drawable.ic_conversation_camera"))
        assertTrue(composerSource.contains("R.drawable.ic_conversation_image"))
        assertTrue(composerSource.contains("R.drawable.ic_conversation_send"))
        assertTrue(messageSource.contains("R.drawable.ic_conversation_copy"))
        assertTrue(messageSource.contains("R.drawable.ic_conversation_open"))
        assertTrue(messageSource.contains("R.drawable.ic_conversation_stop"))
    }

    @Test
    fun runtime_status_is_rendered_inside_conversation_logs_only() {
        assertFalse(source.contains("RuntimeStatusIsland("))
        assertTrue(source.contains("AppTakeoverLogCard("))
        assertTrue(source.contains("onStop = viewModel::stopTask"))
        assertTrue(source.contains("onStop = viewModel::stopAppTakeoverStatus"))
        assertTrue(messageSource.contains("private fun ExecutionLogControls("))
    }

    @Test
    fun execution_expand_control_has_no_adjacent_status_lamp() {
        val runningRow = messageSource.substringAfter("private fun RunningProcessStatusRow(")
            .substringBefore("private fun ExecutionLogControls(")
        val takeoverRow = messageSource.substringAfter("internal fun AppTakeoverLogCard(")
            .substringBefore("private fun TakeoverActionBlock(")
        val completedRow = messageSource.substringAfter("private fun CompletedProcessStatusRow(")
            .substringBefore("internal fun computeProcessDurationMs(")

        assertTrue(runningRow.indexOf("ExecutionExpandControl(") < runningRow.indexOf("ExecutionLogControls("))
        assertFalse(runningRow.contains(".background(statusColor"))
        assertFalse(takeoverRow.contains(".background(DroidanceSuccess"))
        assertFalse(completedRow.contains(".background(statusColor"))
    }

    @Test
    fun active_assistant_output_follows_the_bottom_without_erasing_conversation_history() {
        assertTrue(source.contains(".nestedScroll(scrollController)"))
        assertTrue(source.contains("if (visibleMessages.isEmpty() || userScrolledUp) return@LaunchedEffect"))
        assertTrue(source.contains("followLatestItemToBottom("))
        assertFalse(source.contains("keepLatestItemPinnedToBottom("))
        assertFalse(source.substringAfter("internal suspend fun LazyListState.followLatestItemToBottom(").contains("animateScrollToItem("))
        assertTrue(source.contains("onExecutionLayoutChange"))
        assertFalse(source.contains("scrollOffset = 0"))
        assertFalse(source.contains("conversationViewportHeightPx"))
        assertFalse(source.contains("item(key = \"active_output_reserve\")"))
    }

    @Test
    fun message_rendering_lives_outside_the_home_screen_entry_file() {
        assertFalse(source.contains("private fun ChatBubble("))
        assertFalse(source.contains("private fun TaskCardBubble("))
        assertTrue(messageSource.contains("internal fun ChatBubble("))
        assertFalse(messageSource.contains("private fun TaskCardBubble("))
    }

    @Test
    fun running_process_summary_wraps_statistics_without_subtext() {
        val runningRow = messageSource.substringAfter("private fun RunningProcessStatusRow(")
            .substringBefore("private fun ExecutionLogHeader(")

        assertFalse("Running row must not contain a subtext summary column", runningRow.contains("summary: String"))
        assertFalse("Running row must not have nested summary Text", runningRow.contains("if (summary.isNotBlank())"))
        assertTrue(runningRow.contains("ExecutionProcessSummary(processText, recordSummary)"))
        val summary = messageSource.substringAfter("private fun ExecutionProcessSummary(")
            .substringBefore("internal fun computeProcessDurationMs(")
        assertFalse("Narrow screens and large fonts must not truncate statistics", summary.contains("maxLines"))
        assertFalse(summary.contains("TextOverflow.Ellipsis"))
        assertFalse(summary.contains("statusText"))
    }

    @Test
    fun status_text_stays_readable_and_only_reported_results_stay() {
        val progressBody = messageSource.substringAfter("private fun TaskCurrentProgress(")
            .substringBefore("private fun TakeoverActionBlock(")
        assertTrue("Status must share ordinary reply Markdown layout", progressBody.contains("FlatMarkdown("))
        assertFalse("Status must not truncate lines", progressBody.contains("maxLines"))
        assertTrue("Live bottom status keeps its flowing highlight", progressBody.contains("ShimmeringRunningLogText("))
        assertTrue("The highlight respects system motion and lifecycle", progressBody.contains("LocalAppMotionEnabled.current"))
        assertTrue("Live status uses the original opacity mask", progressBody.contains("BlendMode.DstIn"))

        val bubbleBody = messageSource.substringAfter("private fun EventStoreTaskCardBubble(")
            .substringBefore("private fun StructuredResultCard(")
        assertTrue(bubbleBody.contains("ReportedResults(presentation.reportedResults)"))
        assertTrue(bubbleBody.contains("TaskCurrentProgress(liveStatus)"))
        val takeoverBody = messageSource.substringAfter("internal fun AppTakeoverLogCard(")
            .substringBefore("private fun TakeoverActionBlock(")
        assertTrue(takeoverBody.contains("ReportedResults(turn.reportedResults)"))
        assertTrue(takeoverBody.contains("TaskCurrentProgress(currentTurn.liveStatus(active))"))
    }

    @Test
    fun process_status_rows_have_no_lifecycle_status_prefixes() {
        val runningRow = messageSource.substringAfter("private fun RunningProcessStatusRow(")
            .substringBefore("private fun ExecutionLogControls(")
        val completedRow = messageSource.substringAfter("private fun CompletedProcessStatusRow(")
            .substringBefore("internal fun computeProcessDurationMs(")

        assertFalse("Running row must not prepend statusText", runningRow.contains("listOfNotNull(statusText"))
        assertFalse("Running row must not prepend statusSummary", runningRow.contains("listOfNotNull(statusSummary"))
        assertTrue("Running row must use only duration and interaction count", runningRow.contains("ExecutionProcessSummary(processText, recordSummary)"))

        assertFalse("Completed row must not prepend statusText", completedRow.contains("listOfNotNull(statusText"))
        assertTrue("Completed row must use only duration and interaction count", completedRow.contains("ExecutionProcessSummary(processText, recordSummary)"))
    }

    @Test
    fun execution_log_toggle_disables_click_indication() {
        val header = messageSource.substringAfter("private fun ExecutionLogHeader(")
            .substringBefore("private fun ExecutionLogDivider(")
        assertTrue("Execution log toggle should disable click indication", header.contains("indication = null"))
        assertFalse("Log layout must not depend on reported results", header.contains("resultLabel"))
        assertFalse("Controls must stay beside the statistics", header.contains("stackControls"))
    }
}
