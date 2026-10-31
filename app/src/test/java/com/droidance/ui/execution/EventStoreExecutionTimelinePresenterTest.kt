// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStoreExecutionTimelinePresenterTest {
    @Test fun rounds_bind_all_their_calls_once_even_when_only_the_first_call_has_public_text() {
        val calls = listOf(tool("two", 4), tool("one", 3).copy(publicProgress = "Copied text"),
            tool("other", 6).copy(roundId = "round-2"))
        val updates = listOf(progress("first", 2, "round-1"), progress("second", 5, "round-2"))
        val nodes = EventStoreExecutionTimelinePresenter.present(activity(calls, updates))
        assertEquals(listOf("first", "second"), nodes.map { it.text })
        assertEquals(listOf(listOf("one", "two"), listOf("other")), nodes.map { it.calls.map { call -> call.callId } })
    }

    @Test fun unnarrated_rounds_keep_receipts_without_inventing_public_progress() {
        val calls = (1L..5L).map { tool("call-$it", it * 10).copy(roundId = "round-$it") }
        val nodes = EventStoreExecutionTimelinePresenter.present(activity(calls, listOf(progress("middle", 29, "round-3"))))
        assertEquals("middle", nodes.single { it.text.isNotBlank() }.text)
        assertEquals(listOf("call-3"), nodes.single { it.text.isNotBlank() }.calls.map { it.callId })
        assertEquals(calls.map { it.callId }, nodes.flatMap { it.calls }.map { it.callId })
        assertEquals(5, EventStoreExecutionTimelinePresenter.present(activity(calls)).size)
        assertTrue(EventStoreExecutionTimelinePresenter.present(activity(calls)).all { it.text.isBlank() })
    }

    @Test fun live_narration_stays_below_history_and_archives_once() {
        val updates = listOf(progress("first", 0, "round-1"), progress("latest", 2, "round-2"))
        val calls = listOf(tool("first", 1), tool("latest", 3).copy(roundId = "round-2"))
        val activity = activity(calls, updates)
        val live = EventStoreExecutionTimelinePresenter.present(activity, updates.dropLast(1))
        assertEquals("first", live.first().text)
        assertEquals(listOf("first", "latest"), live.flatMap { it.calls }.map { it.callId })
        val archived = EventStoreExecutionTimelinePresenter.present(activity)
        assertEquals(live.first().id, archived.first().id)
        assertEquals("latest", archived.last().text)
        assertEquals("latest", archived.last().calls.single().callId)
    }

    @Test fun unnarrated_calls_in_one_round_share_one_process_node() {
        val calls = listOf(tool("first", 1), tool("second", 2), tool("other", 3).copy(roundId = "round-2"))
        val nodes = EventStoreExecutionTimelinePresenter.present(activity(calls))
        assertEquals(2, nodes.size)
        assertEquals(listOf("first", "second"), nodes.first().calls.map { it.callId })
        assertEquals(listOf("round-1"), nodes.first().roundIds)
        assertEquals("call:first", nodes.first().id)
    }

    @Test fun duplicate_progress_can_own_multiple_rounds_without_losing_their_receipts() {
        val progress = progress("same", 0, "round-1").copy(roundIds = listOf("round-1", "round-2"))
        val calls = listOf(tool("one", 1), tool("two", 2).copy(roundId = "round-2"))
        val node = EventStoreExecutionTimelinePresenter.present(activity(calls, listOf(progress))).single()
        assertEquals(listOf("one", "two"), node.calls.map { it.callId })
        assertEquals(listOf("round-1", "round-2"), node.roundIds)
    }

    @Test fun invisible_and_other_turn_calls_cannot_leak_into_a_node() {
        val calls = listOf(tool("one", 1), tool("one", 1), tool("hidden", 2, visible = false),
            tool("foreign", 3).copy(turnId = "another-turn"))
        val nodes = EventStoreExecutionTimelinePresenter.present(activity(calls, listOf(progress("update", 0, "round-1"))))
        assertEquals(listOf("one"), nodes.single().calls.map { it.callId })
        assertEquals("update", nodes.single().text)
    }

    @Test fun plain_updates_remain_visible_without_records_and_empty_current_update_does_not_create_a_node() {
        val updates = listOf(progress("first", 1, "one"), progress("latest", 2, "two"))
        val nodes = EventStoreExecutionTimelinePresenter.present(activity(emptyList(), updates), updates.dropLast(1))
        assertEquals("first", nodes.single().text)
        assertTrue(nodes.single().calls.isEmpty())
        assertEquals(listOf("one"), nodes.single().roundIds)
    }

    @Test fun tool_updates_preserve_node_identity_and_never_promote_action_titles_to_narration() {
        val call = tool("one", 1, state = ToolActivityState.PENDING).let {
            it.copy(presentation = it.presentation.copy(actionTitle = "Click the field"))
        }
        val pending = EventStoreExecutionTimelinePresenter.present(activity(listOf(call), listOf(progress("update", 0, "round-1")))).single()
        val finished = EventStoreExecutionTimelinePresenter.present(activity(listOf(call.copy(state = ToolActivityState.FAILED)), listOf(progress("update", 0, "round-1")))).single()
        assertEquals(pending.id, finished.id)
        assertEquals("update", finished.text)
        assertEquals(ToolActivityState.FAILED, finished.calls.single().state)
    }

    @Test fun action_titles_preserve_source_language_and_remove_internal_transport_content() {
        assertEquals("Read USB-C specifications", timelineActionTitle("  Read USB-C specifications  "))
        assertEquals("核对型号 A123", timelineActionTitle("<think>private</think>核对型号 A123"))
        assertNull(timelineActionTitle("<think>private</think>"))
    }

    @Test fun lifecycle_only_activity_does_not_offer_an_empty_disclosure() {
        val paused = activity(listOf(LifecycleActivityReadModel(
            "paused", TURN_ID, 1, kind = LifecycleActivityKind.ATTEMPT_PAUSED_USER,
            attemptId = null, executionId = null, callId = null, diagnosticCode = null
        )))
        assertFalse(EventStoreExecutionTimelinePresenter.hasHistory(paused, emptyList()))
        assertTrue(EventStoreExecutionTimelinePresenter.present(paused).isEmpty())
        assertTrue(EventStoreExecutionTimelinePresenter.hasHistory(activity(listOf(tool("one", 2))), emptyList()))
    }

    @Test fun receipt_lookup_checks_turn_and_round_and_does_not_scan_the_thread() {
        val call = tool("one", 1)
        val node = ExecutionProgressNode("node", 0, "Check", listOf(call), listOf(call.roundId))
        val valid = detail(call)
        assertEquals(valid, executionRecords(node, mapOf("one" to valid)).single().detail)
        assertNull(executionRecords(node, mapOf("one" to valid.copy(turnId = "foreign"))).single().detail)
        assertNull(executionRecords(node, mapOf("one" to valid.copy(roundId = "foreign"))).single().detail)
        assertNull(executionRecords(node, emptyMap()).single().detail)
    }

    @Test fun shared_input_observations_are_hidden_and_result_images_keep_their_actual_call() {
        val calls = listOf(tool("one", 1), tool("two", 2))
        val input = image("input", DetailAttachmentRole.INPUT_OBSERVATION)
        val result = image("result", DetailAttachmentRole.RESULT)
        val records = executionRecords(ExecutionProgressNode("node", 0, "Check", calls, listOf("round-1")), mapOf(
            "one" to detail(calls[0]).copy(attachments = listOf(input)),
            "two" to detail(calls[1]).copy(attachments = listOf(input.copy(attachmentId = "another-id"), result))
        ))
        assertTrue(records[0].detail!!.attachments.isEmpty())
        assertEquals(listOf(result), records[1].detail!!.attachments)
    }

    @Test fun batch_images_hide_before_and_steps_and_keep_after_later_and_diagnostic_views() {
        val before = image("before", DetailAttachmentRole.INPUT_OBSERVATION).copy(sequence = 5)
        val after = image("same-pixels", DetailAttachmentRole.RESULT).copy(attachmentId = "after", sequence = 6)
        val step3 = after.copy(attachmentId = "step3", sequence = 1, batchActionIndex = 3)
        val step1 = image("step1", DetailAttachmentRole.RESULT).copy(sequence = 2, batchActionIndex = 1)
        val later = image("later", DetailAttachmentRole.CURRENT_OBSERVATION).copy(sequence = 7)
        val diagnostic = image("diagnostic", DetailAttachmentRole.DIAGNOSTIC).copy(sequence = 0)
        val nonImage = before.copy(attachmentId = "text", mimeType = "text/plain")
        val call = tool("batch", 1)
        val receipt = detail(call).copy(attachments = listOf(step3, step1, after, later, diagnostic, before, nonImage))
        assertEquals(listOf(after, later, diagnostic), record(call, receipt).detail!!.attachments)
        assertEquals(listOf(step3, step1, after, later, diagnostic, before, nonImage), receipt.attachments)
    }

    @Test fun before_and_step_images_do_not_repeat_the_result_and_duplicate_result_attachments_are_removed() {
        val before = image("same", DetailAttachmentRole.INPUT_OBSERVATION)
        val step1 = before.copy(attachmentId = "step1", role = DetailAttachmentRole.RESULT, batchActionIndex = 1)
        val step2 = step1.copy(attachmentId = "step2", batchActionIndex = 2)
        val after = step1.copy(attachmentId = "after", batchActionIndex = null)
        assertEquals(listOf(after), orderedExecutionImages(
            listOf(step1, before, step2, after, after.copy(attachmentId = "duplicate"))))
    }

    @Test fun partial_batch_failure_retains_completed_prefix_and_identifies_failed_and_unexecuted_steps() {
        val call = tool("batch", 1, state = ToolActivityState.FAILED)
        val receipt = detail(call).copy(argumentsJson = """{"actions":[{},{},{}]}""",
            executionStatus = ToolExecutionStatus.FAILED, diagnosticCode = "PRE_ACTION_OBSERVATION_CHANGED",
            metrics = mapOf("batch_requested_actions" to 3, "batch_completed_actions" to 1, "batch_attempted_actions" to 2))
        val outcome = executionOutcomePresentation(record(call, receipt))!!
        assertEquals(ExecutionBatchProgress(1, 3, 2, 1), outcome.progress)
        assertEquals(com.droidance.R.string.execution_failure_page_changed, outcome.reasonResource)
        assertTrue(outcome.batch)
    }

    @Test fun rejected_batch_reports_zero_executed_steps_and_localized_argument_failure() {
        val call = tool("batch", 1, state = ToolActivityState.REJECTED)
        val receipt = detail(call).copy(argumentsJson = """{"actions":[{},{}]}""",
            executionStatus = ToolExecutionStatus.NOT_EXECUTED, diagnosticCode = "ARGUMENT_SCHEMA_MISMATCH")
        val outcome = executionOutcomePresentation(record(call, receipt))!!
        assertEquals(ExecutionBatchProgress(0, 2, null, 2), outcome.progress)
        assertEquals(com.droidance.R.string.execution_failure_arguments, outcome.reasonResource)
    }

    @Test fun completed_batch_with_final_observation_timeout_does_not_invent_a_failed_action() {
        val call = tool("batch", 1, state = ToolActivityState.UNVERIFIED)
        val receipt = detail(call).copy(technicalName = "screen_act",
            argumentsJson = """{"actions":[{"op":"click","x":100,"y":100},{"op":"click","x":200,"y":200}]}""",
            executionStatus = ToolExecutionStatus.INTERRUPTED, diagnosticCode = "TOOL_EXECUTION_TIMEOUT",
            metrics = mapOf("batch_requested_actions" to 2, "batch_completed_actions" to 2, "batch_attempted_actions" to 2))
        val outcome = executionOutcomePresentation(record(call, receipt))!!
        assertEquals(ExecutionBatchProgress(2, 2, null, 0), outcome.progress)
        assertEquals(com.droidance.R.string.execution_failure_timeout, outcome.reasonResource)
    }

    @Test fun canceled_batch_preserves_known_progress_without_calling_it_a_failure() {
        val call = tool("batch", 1, state = ToolActivityState.UNVERIFIED)
        val receipt = detail(call).copy(argumentsJson = """{"actions":[{"op":"wait","ms":10},{"op":"screenshot"},{"op":"press_key","key":"BACK"}]}""",
            executionStatus = ToolExecutionStatus.CANCELED,
            metrics = mapOf("batch_requested_actions" to 3, "batch_completed_actions" to 1, "batch_attempted_actions" to 2))
        val outcome = executionOutcomePresentation(record(call, receipt))!!
        assertEquals(ToolExecutionStatus.CANCELED, outcome.status)
        assertEquals(ExecutionBatchProgress(1, 3, 2, 1), outcome.progress)
    }

    @Test fun missing_or_inconsistent_batch_receipts_do_not_invent_execution_counts() {
        val call = tool("batch", 1, state = ToolActivityState.FAILED)
        val receipt = detail(call).copy(argumentsJson = """{"actions":[{},{}]}""",
            executionStatus = ToolExecutionStatus.FAILED, diagnosticCode = "UNKNOWN_FAILURE")
        val outcome = executionOutcomePresentation(record(call, receipt))!!
        assertNull(outcome.progress)
        assertNull(outcome.reasonResource)
        assertNull(executionOutcomePresentation(record(call, receipt.copy(metrics = mapOf(
            "batch_requested_actions" to 2, "batch_completed_actions" to 3, "batch_attempted_actions" to 1))))!!.progress)
        assertNull(executionOutcomePresentation(record(call, receipt.copy(executionStatus = ToolExecutionStatus.SUCCEEDED))))
        assertNull(executionOutcomePresentation(record(call.copy(state = ToolActivityState.PENDING),
            receipt.copy(executionStatus = null))))
    }

    @Test fun collection_and_input_failures_have_specific_localized_reasons() {
        assertEquals(com.droidance.R.string.execution_failure_page_changed,
            executionFailureReasonResource("VISIT_RETURN_NOT_CONFIRMED"))
        assertEquals(com.droidance.R.string.execution_failure_input_unavailable,
            executionFailureReasonResource("TEXT_INPUT_BACKEND_NOT_READY"))
        assertEquals(com.droidance.R.string.execution_failure_input_focus,
            executionFailureReasonResource("TEXT_INPUT_TARGET_FOCUS_UNVERIFIED"))
    }

    @Test fun program_and_workspace_scripts_are_mapped_to_execution_commands() {
        val programCall = tool("prog-1", 1)
        val programDetail = detail(programCall).copy(
            technicalName = "program",
            commandSource = "console.log('hello world');",
            outputText = """{"status":"exited","stdout":{"text":"hello world\n"},"error":""}""",
            executionStatus = ToolExecutionStatus.SUCCEEDED
        )
        val workspaceCall = tool("ws-1", 2)
        val workspaceDetail = detail(workspaceCall).copy(
            technicalName = "workspace",
            commandSource = "# write scripts/build.sh\necho building",
            outputText = "File written successfully",
            executionStatus = ToolExecutionStatus.SUCCEEDED
        )
        val node = ExecutionProgressNode("node-scripts", 0, "Execute scripts",
            listOf(programCall, workspaceCall), listOf("round-1"))
        val records = executionRecords(node, mapOf("prog-1" to programDetail, "ws-1" to workspaceDetail))

        assertEquals(2, records.size)
        val progCommand = records[0].commands.single()
        assertEquals("console.log('hello world');", progCommand.command)
        assertEquals("hello world\n", progCommand.stdout)
        assertNull(progCommand.exitCode)
        assertEquals(ToolExecutionStatus.SUCCEEDED, progCommand.status)

        val wsCommand = records[1].commands.single()
        assertEquals("# write scripts/build.sh\necho building", wsCommand.command)
        assertEquals("File written successfully", wsCommand.stdout)
        assertNull(wsCommand.exitCode)
        assertEquals(ToolExecutionStatus.SUCCEEDED, wsCommand.status)
    }

    @Test fun failed_scripts_and_workspace_operations_do_not_invent_process_exit_codes() {
        val calls = listOf(tool("program", 1), tool("workspace", 2))
        val details = mapOf(
            "program" to detail(calls[0]).copy(technicalName = "program", commandSource = "throw new Error('failed');",
                outputText = """{"status":"failed","stdout":{"text":"partial output","has_more":true},"error":"Error: failed"}""",
                executionStatus = ToolExecutionStatus.FAILED),
            "workspace" to detail(calls[1]).copy(technicalName = "workspace", commandSource = "read missing.txt",
                outputText = """{"code":"WORKSPACE_FILE_NOT_FOUND"}""", executionStatus = ToolExecutionStatus.FAILED)
        )
        val records = executionRecords(ExecutionProgressNode("node", 0, "", calls, listOf("round-1")), details)
        records.forEach { record ->
            assertNull(record.commands.single().exitCode)
            assertEquals(ToolExecutionStatus.FAILED, record.commands.single().status)
        }
        assertEquals("partial output", records[0].commands.single().stdout)
        assertEquals("Error: failed", records[0].commands.single().stderr)
        assertTrue(records[0].commands.single().stdoutTruncated)
        assertEquals("""{"code":"WORKSPACE_FILE_NOT_FOUND"}""", records[1].commands.single().stdout)
    }

    @Test fun actual_shell_receipts_keep_exit_codes_and_both_output_streams() {
        val call = tool("shell", 1)
        val command = "printf out; printf err >&2; exit 7"
        val receipt = detail(call).copy(technicalName = "shell", commandSource = command,
            outputText = """{"stdout":"out","stderr":"err","exit_code":7,"stdout_truncated":true}""",
            executionStatus = ToolExecutionStatus.FAILED)
        val record = executionRecords(ExecutionProgressNode("node", 0, "", listOf(call), listOf("round-1")),
            mapOf(call.callId to receipt)).single().commands.single()
        assertEquals(command, record.command)
        assertEquals("out", record.stdout)
        assertEquals("err", record.stderr)
        assertEquals(7, record.exitCode)
        assertTrue(record.stdoutTruncated)

        val unknown = executionCommandRecord("unknown", command, """{"exit_code":-1}""", ToolExecutionStatus.INTERRUPTED, null)
        assertNull(unknown.exitCode)
    }

    @Test fun script_child_commands_keep_their_own_receipts_without_a_parent_exit_code() {
        val call = tool("program", 1)
        val receipt = detail(call).copy(technicalName = "program", commandSource = "android.shell('echo child');",
            outputText = """{"stdout":{"text":"parent output"},"error":""}""")
        val child = ProgramShellCallReadModel("child", call.callId, 2, "echo child", 10, 15,
            ToolExecutionResult("""{"stdout":"child output","stderr":"","exit_code":0}""",
                ToolExecutionStatus.SUCCEEDED, ToolEvidenceStatus.NOT_REQUIRED))
        val unrelated = child.copy(childId = "foreign", parentCallId = "other-program")
        val commands = executionRecords(ExecutionProgressNode("node", 0, "", listOf(call), listOf("round-1")),
            mapOf(call.callId to receipt), listOf(child, unrelated)).single().commands
        assertEquals(listOf(call.callId, child.childId), commands.map { it.id })
        assertNull(commands[0].exitCode)
        assertEquals(0, commands[1].exitCode)
        assertEquals("child output", commands[1].stdout)
    }

    @Test fun shell_rejections_keep_the_returned_error_instead_of_showing_an_empty_output() {
        val call = tool("shell-rejected", 1, state = ToolActivityState.REJECTED)
        val output = """{"code":"TOOL_EXECUTION_BUDGET_EXHAUSTED","retryable":false}"""
        val receipt = detail(call).copy(technicalName = "shell", commandSource = "echo example",
            outputText = output, executionStatus = ToolExecutionStatus.NOT_EXECUTED)
        val command = record(call, receipt).commands.single()
        assertEquals("echo example", command.command)
        assertEquals(output, command.stdout)
        assertNull(command.exitCode)
    }

    @Test fun native_reads_and_content_mutations_keep_the_actual_invocation_and_full_payload() {
        val operations = listOf("android_read" to "device", "android_read" to "network",
            "android_read" to "system", "android_read" to "capabilities", "android_read" to "content",
            "android_act" to "content", "android_read" to "crop")
        operations.forEach { (name, operation) ->
            val call = tool("$name-$operation", 1)
            val arguments = if (operation == "crop") {
                """{"op":"crop","source_ref":"retained-observation","left":100,"top":200,"right":700,"bottom":800}"""
            } else {
                """{"op":"$operation","uri":"content://example/rows","limit":3}"""
            }
            val output = if (operation == "crop") {
                """{"source_ref":"retained-observation","crop_ref":"retained-crop","source_region_px":{"x":100,"y":200,"width":600,"height":600}}"""
            } else {
                "[ANDROID_RESULT]\n" +
                    """{"source_kind":"android_platform_api","rows":[{"value":7}],"scope":"device","captured_at_ms":1234}"""
            }
            val receipt = detail(call).copy(technicalName = name, argumentsJson = arguments, outputText = output)
            val record = record(call, receipt)
            assertEquals("$name($arguments)", record.invocation?.call)
            assertEquals(output, record.invocation?.output)
            assertTrue(record.commands.isEmpty())
        }
    }

    @Test fun permission_failures_preserve_the_actual_permission_list_and_platform_status() {
        val call = tool("permission", 1, state = ToolActivityState.REJECTED)
        val arguments = """{"op":"content","uri":"content://example/rows"}"""
        val output = """{"code":"PERMISSION_DENIED","details":{"permissions":["android.permission.READ_CONTACTS"],"platform_status":{"android.permission.READ_CONTACTS":"denied"}}}"""
        val receipt = detail(call).copy(technicalName = "android_read", argumentsJson = arguments,
            outputText = output, executionStatus = ToolExecutionStatus.NOT_EXECUTED)
        val record = record(call, receipt)
        assertEquals("android_read($arguments)", record.invocation?.call)
        assertEquals(output, record.invocation?.output)
    }

    @Test fun gui_navigation_and_observations_keep_their_existing_presentation() {
        listOf(
            "screen_act" to """{"actions":[{"op":"click","x":500,"y":500}]}""",
            "screen_act" to """{"actions":[{"op":"click","x":500,"y":500},{"op":"screenshot"}]}""",
            "screen_act" to """{"actions":[{"op":"wait","ms":250}]}""",
            "screen_act" to """{"actions":[{"op":"type_text","text":"example","clear":false}]}""",
            "android_read" to """{"op":"screen"}""",
            "android_act" to """{"op":"open","package_name":"example.app"}"""
        ).forEachIndexed { index, (name, arguments) ->
            val call = tool("$name-$index", 1)
            val receipt = detail(call).copy(technicalName = name, argumentsJson = arguments)
            val record = record(call, receipt)
            assertNull(record.invocation)
            assertTrue(record.commands.isEmpty())
        }
    }

    @Test fun pending_native_calls_do_not_invent_an_output() {
        val call = tool("pending", 1, state = ToolActivityState.PENDING)
        val receipt = detail(call).copy(technicalName = "android_read", argumentsJson = """{"op":"device"}""",
            outputText = null, executionStatus = null)
        val record = record(call, receipt)
        assertEquals("android_read({\"op\":\"device\"})", record.invocation?.call)
        assertNull(record.invocation?.output)
    }

    @Test fun native_arguments_and_json_outputs_keep_existing_secret_redaction() {
        val call = tool("native", 1)
        val receipt = detail(call).copy(technicalName = "android_read",
            argumentsJson = """{"op":"content","selection_args":["<redacted>"],"password":"argument-secret"}""",
            outputText = "[ANDROID_CONTENT_RESULT]\n" +
                """{"api_key":"result-secret","rows":[{"password":"nested-secret","value":7}]}""")
        val invocation = record(call, receipt).invocation!!
        assertTrue(invocation.call.contains("<redacted>"))
        assertTrue(invocation.call.contains("[REDACTED]"))
        assertTrue(invocation.output!!.contains("[REDACTED]"))
        assertTrue(invocation.output!!.contains("\"value\":7"))
        listOf("argument-secret", "result-secret", "nested-secret").forEach { secret ->
            assertFalse(invocation.call.contains(secret))
            assertFalse(invocation.output!!.contains(secret))
        }
    }

    private fun record(call: ToolActivityReadModel, receipt: ToolDetailReadModel) = executionRecords(
        ExecutionProgressNode("node", 0, "", listOf(call), listOf(call.roundId)), mapOf(call.callId to receipt)
    ).single()

    private fun image(ref: String, role: DetailAttachmentRole) = DetailAttachmentReadModel(
        ref, ref, 1, role, ref, ref, 1, "image/png", 1, 1, null
    )

    private fun detail(call: ToolActivityReadModel) = ToolDetailReadModel(
        threadId = "thread", turnId = call.turnId, roundId = call.roundId, callId = call.callId,
        providerCallId = call.callId, providerOrder = 0, technicalName = "screen_act",
        argumentsJson = "{}", source = call.source, binding = ToolBinding.TURN_LOCAL,
        effect = ToolEffect.READ_ONLY, presentation = call.presentation, roundDetailRef = "detail",
        attemptId = null, executionId = null, backendSessionId = null, displayId = null,
        outputText = "receipt", executionStatus = ToolExecutionStatus.SUCCEEDED,
        evidenceStatus = ToolEvidenceStatus.UNKNOWN, actionEvidence = null, observationId = null,
        diagnosticCode = null, metrics = emptyMap(), publicProgressBlocks = emptyList(),
        startedAt = 1, finishedAt = 2, exclusiveDurationMillis = 1, attachments = emptyList()
    )

    private fun progress(id: String, sequence: Long, round: String) = PublicProgressReadModel(id, sequence, id, listOf(round))

    private fun activity(entries: List<ActivityEntryReadModel>, updates: List<PublicProgressReadModel> = emptyList()) = TurnActivityReadModel(
        turnId = TURN_ID, state = TurnReadModelState.RUNNING, entries = entries,
        operationCount = entries.size, currentLabelResourceKey = null, publicProgressHistory = updates,
        startedAt = 100L, completedAt = null, totalDurationMillis = null
    )

    private fun tool(
        callId: String,
        sequence: Long,
        source: ToolCallSource = ToolCallSource.MODEL_REQUESTED,
        visible: Boolean = true,
        state: ToolActivityState = ToolActivityState.VERIFIED,
        diagnosticCode: String? = null
    ) = ToolActivityReadModel(
        itemId = callId,
        turnId = TURN_ID,
        sequence = sequence,
        visible = visible,
        roundId = "round-1",
        callId = callId,
        attemptId = "attempt-1",
        executionId = "execution-1",
        source = source,
        presentation = ToolPresentationSnapshot(
            labelResourceKey = "tool.observe",
            category = ToolPresentationCategory.PRIMARY,
            resultImageRole = ResultImageRole.NONE
        ),
        state = state,
        diagnosticCode = diagnosticCode,
        publicProgress = null,
        operationCounted = true,
        startedAt = 100L,
        finishedAt = 200L
    )

    private companion object {
        const val TURN_ID = "turn-1"
    }
}
