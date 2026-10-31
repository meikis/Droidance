// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.model.llm.models.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundExecutionPlannerTest {
    @Test fun textAfterOpeningIsProgressAndALegacyStageHintChangesNothing() {
        fun round(vararg names: String, text: String = "First source findings") = NormalizedModelRound.create(
            publicBlocks = listOf(text), toolCalls = names.mapIndexed { i, name -> ProviderToolCall("p-$i", name, "{}") },
            finishMetadata = mapOf("public_output_kind" to "stage_result"))
        fun plan(round: NormalizedModelRound, started: Boolean = true) = planner().plan(round, started, RepairPurpose.NONE)
        val stage = plan(round("read_memory"))
        assertEquals(PublicMessageKind.PROGRESS, stage.publicMessage!!.kind)
        assertEquals(listOf("read_memory"), stage.selectedCalls.map { it.contract.name })
        assertEquals(RoundFollowUp.NONE, stage.followUp)
        assertEquals(PublicMessageKind.START, plan(round("read_memory"), false).publicMessage!!.kind)
        assertEquals(PublicMessageKind.USER_INTERVENTION, plan(round("ask_human")).publicMessage!!.kind)
        assertEquals(RoundFollowUp.WAIT_FOR_USER, plan(round("ask_human")).followUp)
        assertEquals(PublicMessageKind.FINAL, plan(round("done")).publicMessage!!.kind)
        assertEquals(RoundFollowUp.COMPLETE_AFTER_RESULTS, plan(round()).followUp)
        assertNull(plan(NormalizedModelRound.create(emptyList(), round("read_memory").toolCalls,
            mapOf("public_output_kind" to "stage_result"))).publicMessage)
    }

    private val registrations = listOf(
        RegisteredToolContract(
            "observe", "Observe screen", ToolBinding.DEVICE_BOUND, ToolEffect.READ_ONLY, emptyMap()
        ),
        RegisteredToolContract(
            name = "observe_for_action",
            description = "Observe screen before an action",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.READ_ONLY,
            parameters = emptyMap(),
            requiresResultBeforeAction = true
        ),
        RegisteredToolContract(
            name = "read_for_action",
            description = "Read local evidence before an action",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY,
            parameters = emptyMap(),
            requiresResultBeforeAction = true
        ),
        RegisteredToolContract(
            "click", "Click coordinate", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING, emptyMap()
        ),
        RegisteredToolContract(
            "screen_act", "Screen action", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING, emptyMap()
        ),
        RegisteredToolContract(
            "read_memory", "Read memory", ToolBinding.TURN_LOCAL, ToolEffect.READ_ONLY, emptyMap()
        ),
        RegisteredToolContract(
            "save_memory", "Save memory", ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING, emptyMap()
        ),
        RegisteredToolContract(
            "ask_human", "Ask user", ToolBinding.TURN_LOCAL, ToolEffect.USER_INTERVENTION, emptyMap()
        ),
        RegisteredToolContract(
            "done", "Complete turn", ToolBinding.TURN_LOCAL, ToolEffect.COMPLETION, emptyMap()
        ),
        RegisteredToolContract(
            "present_result_card", "Present result", ToolBinding.TURN_LOCAL, ToolEffect.COMPLETION, emptyMap()
        )
    )

    private fun planner(
        preflight: (PlannedToolCall) -> String? = { null }
    ) = RoundExecutionPlanner(
        registrations = registrations,
        callIdFactory = { index, _ -> "call-$index" },
        preflight = preflight
    )

    @Test fun malformedHumanRequestRejectsAllActionsWithoutRequestingTakeover() {
        val contracts = registrations.map { if (it.name == "ask_human") RegisteredToolContract(it.name, it.description, it.binding, it.effect,
            parameters = mapOf("reason" to ToolParameter("string", "Reason", true))
        ) else it }
        val plan = RoundExecutionPlanner(contracts).plan(
            NormalizedModelRound.create(publicBlocks = listOf("Please take over"), toolCalls = listOf(
                ProviderToolCall("human", "ask_human", """{"reason":false}"""),
                ProviderToolCall("move", "click", "{}")
            )), startCommitted = true, repairPurpose = RepairPurpose.NONE
        )
        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(RoundFollowUp.NONE, plan.followUp)
        assertNull(plan.publicMessage)
        assertEquals(setOf(ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
            RoundDiagnosticCode.INTERVENTION_EXCLUSIVE), plan.rejectedCalls.map { it.diagnosticCode }.toSet())
    }

    @Test
    fun normalized_round_keeps_only_explicit_non_blank_public_blocks() {
        val round = NormalizedModelRound.create(
            publicBlocks = listOf("  first\r\nline  ", "  ", "second"),
            toolCalls = emptyList()
        )

        assertEquals(listOf("  first\nline  ", "second"), round.publicBlocks)
        assertEquals("  first\nline  \nsecond", round.publicText)
    }

    @Test
    fun normalized_round_owns_immutable_snapshots_of_provider_collections() {
        val calls = mutableListOf(ProviderToolCall("provider-1", "observe", "{}"))
        val metadata = mutableMapOf("finishReason" to "tool_calls")
        val round = NormalizedModelRound.create(
            publicBlocks = listOf("公开回复"),
            toolCalls = calls,
            finishMetadata = metadata
        )

        calls.clear()
        metadata["finishReason"] = "stop"

        assertEquals(listOf("observe"), round.toolCalls.map { it.name })
        assertEquals("tool_calls", round.finishMetadata["finishReason"])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (round.publicBlocks as MutableList<String>).add("unexpected")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (round.toolCalls as MutableList<ProviderToolCall>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (round.finishMetadata as MutableMap<String, String>)["finishReason"] = "changed"
        }
        assertFalse(NormalizedModelRound::class.java.declaredMethods.any { it.name == "copy" })
    }

    @Test
    fun result_before_action_is_read_only_only_and_part_of_contract_identity() {
        val ordinaryRead = RegisteredToolContract(
            name = "query",
            description = "Query",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY,
            parameters = emptyMap()
        )
        val resultBeforeActionRead = RegisteredToolContract(
            name = "query",
            description = "Query",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY,
            parameters = emptyMap(),
            requiresResultBeforeAction = true
        )

        assertNotEquals(ordinaryRead, resultBeforeActionRead)
        assertNotEquals(ordinaryRead.contractHash, resultBeforeActionRead.contractHash)
        assertThrows(IllegalArgumentException::class.java) {
            RegisteredToolContract(
                name = "invalid_query",
                description = "Invalid query contract",
                binding = ToolBinding.DEVICE_BOUND,
                effect = ToolEffect.STATE_CHANGING,
                parameters = emptyMap(),
                requiresResultBeforeAction = true
            )
        }
    }

    @Test
    fun device_calls_and_local_writes_preserve_order_without_a_shared_slot_limit() {
        val round = round(
            public = "我先检查页面",
            calls = listOf("observe", "read_memory", "click", "save_memory")
        )

        val plan = planner().plan(round, startCommitted = false, repairPurpose = RepairPurpose.NONE)

        assertEquals(PublicMessageKind.START, plan.publicMessage?.kind)
        assertEquals(listOf("observe", "read_memory", "click", "save_memory"), plan.selectedCalls.map { it.providerCall.name })
        assertTrue(plan.rejectedCalls.isEmpty())
        assertEquals(RoundExecutionMode.SERIAL, plan.executionMode)
        assertEquals(setOf("call-0", "call-1", "call-2", "call-3"), plan.allCallIds)
    }

    @Test
    fun declared_device_query_blocks_only_actions_after_its_result_boundary() {
        listOf(
            listOf("observe_for_action", "click"),
            listOf("click", "observe_for_action")
        ).forEach { providerOrder ->
            val plan = planner().plan(
                round("先取得依据再操作", providerOrder),
                startCommitted = false,
                repairPurpose = RepairPurpose.NONE
            )

            val queryFirst = providerOrder.first() == "observe_for_action"
            assertEquals(if (queryFirst) listOf("observe_for_action") else providerOrder, plan.selectedCalls.map { it.providerCall.name })
            assertEquals(if (queryFirst) listOf("click") else emptyList<String>(), plan.rejectedCalls.map { it.call.providerCall.name })
            assertEquals(
                if (queryFirst) listOf(RoundDiagnosticCode.QUERY_RESULT_REQUIRED) else emptyList<String>(),
                plan.rejectedCalls.map(RejectedToolCall::diagnosticCode)
            )
            assertEquals(if (queryFirst) listOf(listOf("provider-0")) else emptyList<List<String>>(),
                plan.rejectedCalls.map(RejectedToolCall::prerequisiteProviderCallIds))
            assertEquals(RoundExecutionMode.SERIAL, plan.executionMode)
            assertEquals(setOf("call-0", "call-1"), plan.allCallIds)
        }
    }

    @Test fun queryBarrierUsesModelVisibleProviderIdsAndTheExistingCanonicalFallback() {
        val plan = planner().plan(NormalizedModelRound.create(emptyList(), listOf(
            ProviderToolCall("wire-query", "read_for_action", "{}"),
            ProviderToolCall(null, "observe_for_action", "{}"),
            ProviderToolCall("wire-action", "click", "{}")
        )), startCommitted = true, repairPurpose = RepairPurpose.NONE)
        assertEquals(listOf("call-0", "call-1"), plan.selectedCalls.map { it.callId })
        assertEquals(listOf("wire-query", "call-1"), plan.rejectedCalls.single().prerequisiteProviderCallIds)
        assertEquals("call-2", plan.rejectedCalls.single().call.callId)
        assertEquals("wire-action", plan.rejectedCalls.single().call.providerCall.providerCallId)
    }

    @Test
    fun all_device_actions_are_closed_by_a_declared_query_barrier() {
        val plan = planner().plan(
            round(
                "先读取依据",
                listOf("click", "unknown", "observe_for_action", "click")
            ),
            startCommitted = true,
            repairPurpose = RepairPurpose.NONE
        )

        assertEquals(listOf("click"), plan.selectedCalls.map { it.providerCall.name })
        assertEquals(
            listOf("unknown", "observe_for_action", "click"),
            plan.rejectedCalls.map { it.call.providerCall.name }
        )
        assertEquals(
            listOf(
                RoundDiagnosticCode.UNKNOWN_TOOL,
                RoundDiagnosticCode.PRIOR_CALL_REJECTED,
                RoundDiagnosticCode.QUERY_RESULT_REQUIRED
            ),
            plan.rejectedCalls.map(RejectedToolCall::diagnosticCode)
        )
        assertEquals(4, plan.allCallIds.size)
        assertEquals(4, plan.selectedCalls.size + plan.rejectedCalls.size)
    }

    @Test
    fun invalid_declared_query_keeps_schema_diagnostic_and_still_blocks_action() {
        val query = RegisteredToolContract(
            name = "invalid_query",
            description = "Query with a required argument",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.READ_ONLY,
            parameters = mapOf(
                "scope" to ToolParameter("string", "Query scope", required = true)
            ),
            requiresResultBeforeAction = true
        )
        var preflightCalls = 0
        val schemaPlanner = RoundExecutionPlanner(
            registrations = listOf(query, registrations.first { it.name == "click" }),
            callIdFactory = { index, _ -> "call-$index" },
            preflight = {
                preflightCalls += 1
                null
            }
        )

        val plan = schemaPlanner.plan(
            NormalizedModelRound.create(
                publicBlocks = listOf("先读取依据"),
                toolCalls = listOf(
                    ProviderToolCall("provider-0", "invalid_query", "{\"scope\":7}"),
                    ProviderToolCall("provider-1", "click", "{}")
                )
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(
            listOf(
                ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
                RoundDiagnosticCode.QUERY_RESULT_REQUIRED
            ),
            plan.rejectedCalls.map(RejectedToolCall::diagnosticCode)
        )
        assertEquals(0, preflightCalls)
        assertEquals(setOf("call-0", "call-1"), plan.allCallIds)
    }

    @Test
    fun failed_declared_query_keeps_its_preflight_diagnostic_and_still_blocks_action() {
        var preflightCalls = 0
        val plan = planner { call ->
            preflightCalls += 1
            "QUERY_FAILED".takeIf { call.providerCall.name == "read_for_action" }
        }.plan(
            round("先读取依据", listOf("read_for_action", "click")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(
            listOf("QUERY_FAILED", "QUERY_RESULT_REQUIRED"),
            plan.rejectedCalls.map(RejectedToolCall::diagnosticCode)
        )
        assertEquals(listOf("read_for_action", "click"), plan.rejectedCalls.map { it.call.providerCall.name })
        assertEquals(1, preflightCalls)
        assertEquals(setOf("call-0", "call-1"), plan.allCallIds)
    }

    @Test
    fun declared_local_query_can_parallelize_with_local_reads_while_blocking_device_action() {
        val plan = planner().plan(
            round(
                "先读取本地依据",
                listOf("read_memory", "read_for_action", "click")
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertEquals(
            listOf("read_memory", "read_for_action"),
            plan.selectedCalls.map { it.providerCall.name }
        )
        assertEquals(listOf("click"), plan.rejectedCalls.map { it.call.providerCall.name })
        assertEquals(
            listOf(RoundDiagnosticCode.QUERY_RESULT_REQUIRED),
            plan.rejectedCalls.map(RejectedToolCall::diagnosticCode)
        )
        assertEquals(RoundExecutionMode.PARALLEL, plan.executionMode)
    }

    @Test
    fun pure_turn_local_reads_are_the_only_parallel_batch() {
        val plan = planner().plan(
            round("准备读取本地资料", listOf("read_memory", "read_memory")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertEquals(2, plan.selectedCalls.size)
        assertTrue(plan.rejectedCalls.isEmpty())
        assertEquals(RoundExecutionMode.PARALLEL, plan.executionMode)
        assertEquals(PublicMessageKind.START, plan.publicMessage?.kind)
    }

    @Test
    fun first_local_summary_is_the_start_and_device_work_is_later_progress() {
        val local = planner().plan(
            round("先读取本地资料", listOf("read_memory")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )
        val device = planner().plan(
            round("现在查看设备页面", listOf("observe")),
            startCommitted = true,
            repairPurpose = RepairPurpose.NONE
        )

        assertEquals(PublicMessageKind.START, local.publicMessage?.kind)
        assertEquals(PublicMessageKind.PROGRESS, device.publicMessage?.kind)
    }

    @Test
    fun hidden_turn_local_support_is_allowed_before_any_device_start() {
        val plan = planner().plan(
            round(null, listOf("read_memory")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertEquals(listOf("read_memory"), plan.selectedCalls.map { it.providerCall.name })
        assertNull(plan.publicMessage)
        assertEquals(RoundFollowUp.NONE, plan.followUp)
    }

    @Test
    fun intervention_is_exclusive_and_requires_public_model_text() {
        val withText = planner().plan(
            round("请接管并完成验证", listOf("click", "ask_human", "done", "ask_human")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )
        assertEquals(PublicMessageKind.USER_INTERVENTION, withText.publicMessage?.kind)
        assertEquals(listOf("ask_human"), withText.selectedCalls.map { it.providerCall.name })
        assertEquals(3, withText.rejectedCalls.size)
        assertTrue(withText.rejectedCalls.all {
            it.diagnosticCode == RoundDiagnosticCode.INTERVENTION_EXCLUSIVE
        })
        assertEquals(RoundFollowUp.WAIT_FOR_USER, withText.followUp)

        val withoutText = planner().plan(
            round(null, listOf("ask_human", "click")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )
        assertTrue(withoutText.selectedCalls.isEmpty())
        assertEquals(2, withoutText.rejectedCalls.size)
        assertEquals(RoundFollowUp.TERMINATE_PROTOCOL, withoutText.followUp)
        assertEquals(RoundDiagnosticCode.MISSING_INTERVENTION_REPLY, withoutText.terminalDiagnosticCode)
    }

    @Test
    fun rejected_intervention_returns_to_agent_without_public_handoff() {
        val plan = planner { call ->
            "INTERVENTION_PREFLIGHT_REJECTED"
                .takeIf { call.contract.effect == ToolEffect.USER_INTERVENTION }
        }.plan(
            round("请接管并完成验证", listOf("ask_human")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertNull(plan.publicMessage)
        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(RoundExecutionMode.NONE, plan.executionMode)
        assertEquals(RoundFollowUp.NONE, plan.followUp)
        assertEquals(
            "INTERVENTION_PREFLIGHT_REJECTED",
            plan.rejectedCalls.single().diagnosticCode
        )
    }

    @Test
    fun device_action_without_start_text_executes_immediately() {
        val plan = planner().plan(
            round(null, listOf("click", "done")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )
        assertEquals(listOf("click"), plan.selectedCalls.map { it.providerCall.name })
        assertEquals(RoundDiagnosticCode.COMPLETION_WITH_ACTION, plan.rejectedCalls.single().diagnosticCode)
        assertNull(plan.publicMessage)
        assertEquals(RoundFollowUp.NONE, plan.followUp)
        assertNull(plan.terminalDiagnosticCode)
    }

    @Test
    fun action_wins_over_completion_and_completion_only_can_finalize() {
        val mixed = planner().plan(
            round("继续处理", listOf("click", "done")),
            startCommitted = true,
            repairPurpose = RepairPurpose.NONE
        )
        assertEquals(PublicMessageKind.PROGRESS, mixed.publicMessage?.kind)
        assertEquals(listOf("click"), mixed.selectedCalls.map { it.providerCall.name })
        assertEquals(RoundDiagnosticCode.COMPLETION_WITH_ACTION, mixed.rejectedCalls.single().diagnosticCode)
        assertEquals(RoundFollowUp.NONE, mixed.followUp)

        val completed = planner().plan(
            round("已经完成", listOf("done", "done")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )
        assertEquals(PublicMessageKind.FINAL, completed.publicMessage?.kind)
        assertEquals(1, completed.selectedCalls.size)
        assertEquals(RoundDiagnosticCode.ONE_COMPLETION_PER_ROUND, completed.rejectedCalls.single().diagnosticCode)
        assertEquals(RoundFollowUp.COMPLETE_AFTER_RESULTS, completed.followUp)
    }

    @Test
    fun preflight_and_unknown_tool_are_not_executed() {
        val plan = planner { call ->
            "NO_CAPABILITY".takeIf { call.providerCall.name == "click" }
        }.plan(
            round("我先处理", listOf("click", "unknown")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(listOf("NO_CAPABILITY", RoundDiagnosticCode.UNKNOWN_TOOL), plan.rejectedCalls.map { it.diagnosticCode })
    }

    @Test
    fun invalid_arguments_are_rejected_before_capability_preflight() {
        var capabilityPreflightCalls = 0
        val schemaPlanner = RoundExecutionPlanner(
            registrations = listOf(
                RegisteredToolContract(
                    name = "click",
                    description = "Click coordinate",
                    binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING,
                    parameters = mapOf(
                        "x" to ToolParameter("integer", "X coordinate", required = true),
                        "y" to ToolParameter("integer", "Y coordinate", required = true)
                    )
                )
            ),
            callIdFactory = { index, _ -> "call-$index" },
            preflight = {
                capabilityPreflightCalls += 1
                null
            }
        )

        val plan = schemaPlanner.plan(
            NormalizedModelRound.create(
                publicBlocks = listOf("我先处理"),
                toolCalls = listOf(
                    ProviderToolCall("provider-0", "click", "{\"x\":\"10\",\"y\":20}")
                )
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(
            listOf(ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH),
            plan.rejectedCalls.map { it.diagnosticCode }
        )
        assertEquals(0, capabilityPreflightCalls)
    }

    @Test
    fun invalid_first_action_does_not_promote_a_later_action_into_the_serial_slot() {
        val clickContract = RegisteredToolContract(
            name = "click",
            description = "Click coordinate",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            parameters = mapOf(
                "x" to ToolParameter("integer", "X coordinate", required = true),
                "y" to ToolParameter("integer", "Y coordinate", required = true)
            )
        )
        val schemaPlanner = RoundExecutionPlanner(
            registrations = listOf(clickContract),
            callIdFactory = { index, _ -> "call-$index" }
        )

        val plan = schemaPlanner.plan(
            NormalizedModelRound.create(
                publicBlocks = listOf("我先处理"),
                toolCalls = listOf(
                    ProviderToolCall("provider-0", "click", "{\"x\":\"invalid\",\"y\":20}"),
                    ProviderToolCall("provider-1", "click", "{\"x\":10,\"y\":20}")
                )
            ),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(
            listOf(
                ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
                RoundDiagnosticCode.PRIOR_CALL_REJECTED
            ),
            plan.rejectedCalls.map { it.diagnosticCode }
        )
    }

    @Test
    fun unknown_prefix_prevents_a_later_action_from_running_without_its_prerequisite() {
        val plan = planner().plan(
            round("我先处理", listOf("unknown", "click")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(listOf(RoundDiagnosticCode.UNKNOWN_TOOL, RoundDiagnosticCode.PRIOR_CALL_REJECTED), plan.rejectedCalls.map { it.diagnosticCode })
        assertEquals(RoundExecutionMode.NONE, plan.executionMode)
    }

    @Test
    fun execution_plan_owns_immutable_call_collections_and_rejects_duplicate_registrations() {
        val plan = planner().plan(
            round("读取资料", listOf("read_memory")),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.selectedCalls as MutableList<PlannedToolCall>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoundExecutionPlanner(
                registrations = listOf(
                    RegisteredToolContract(
                        "duplicate", "First", ToolBinding.TURN_LOCAL, ToolEffect.READ_ONLY, emptyMap()
                    ),
                    RegisteredToolContract(
                        "duplicate", "Second", ToolBinding.DEVICE_BOUND, ToolEffect.READ_ONLY, emptyMap()
                    )
                )
            )
        }
    }

    @Test
    fun requested_local_frame_wait_blocks_later_device_mutation_until_the_result_round() {
        val plan = planner().plan(
            round(
                "等待画面稳定后再继续",
                listOf("screen_act", "click")
            ).let { round ->
                NormalizedModelRound.create(
                    publicBlocks = round.publicBlocks,
                    toolCalls = listOf(
                        ProviderToolCall(
                            "frame-wait",
                            "screen_act",
                            """{"actions":[{"op":"click","x":10,"y":20}],"wait_for_frame_ms":300}"""
                        ),
                        ProviderToolCall("click-after-wait", "click", "{}")
                    )
                )
            },
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertEquals(listOf("screen_act"), plan.selectedCalls.map { it.providerCall.name })
        assertEquals(listOf("click"), plan.rejectedCalls.map { it.call.providerCall.name })
        assertEquals(
            listOf(RoundDiagnosticCode.QUERY_RESULT_REQUIRED),
            plan.rejectedCalls.map(RejectedToolCall::diagnosticCode)
        )
    }

    @Test
    fun harness_call_ids_do_not_reuse_provider_ids() {
        val providerCalls = listOf(
            ProviderToolCall("same-provider-id", "read_memory", "{}"),
            ProviderToolCall("same-provider-id", "read_memory", "{}")
        )
        val plan = planner().plan(
            NormalizedModelRound.create(listOf("读取资料"), providerCalls),
            startCommitted = false,
            repairPurpose = RepairPurpose.NONE
        )

        assertEquals(setOf("call-0", "call-1"), plan.allCallIds)
        assertEquals(setOf("same-provider-id"), plan.selectedCalls.mapNotNull { it.providerCall.providerCallId }.toSet())
    }

    @Test
    fun structured_tool_result_never_infers_status_from_output_text() {
        val result = ToolExecutionResult(
            outputText = "包含失败二字也不改变结构化状态",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.CAPTURED
        )
        assertTrue(result.isVerifiedSuccess)

        val invalid = ToolExecutionResult(
            outputText = "看起来成功",
            executionStatus = ToolExecutionStatus.FAILED,
            evidenceStatus = ToolEvidenceStatus.UNKNOWN
        )
        assertFalse(invalid.isVerifiedSuccess)
        assertThrows(IllegalArgumentException::class.java) {
            ToolExecutionResult(
                outputText = "",
                executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
            )
        }
    }

    private fun round(public: String?, calls: List<String>): NormalizedModelRound =
        NormalizedModelRound.create(
            publicBlocks = listOfNotNull(public),
            toolCalls = calls.mapIndexed { index, name ->
                ProviderToolCall("provider-$index", name, "{}")
            }
        )

    @Test
    fun localProgramAllowedWhenMutationsRestricted() {
        val programContract = RegisteredToolContract(
            name = "program",
            description = "Programmable environment",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.STATE_CHANGING,
            parameters = emptyMap()
        )
        val contracts = registrations + programContract
        val planner = RoundExecutionPlanner(
            registrations = contracts,
            presentedToolNames = setOf("program", "task_memo"),
            mutationsRestricted = true
        )
        val plan = planner.plan(
            NormalizedModelRound.create(
                publicBlocks = emptyList(),
                toolCalls = listOf(
                    ProviderToolCall("p1", "program", """{"code":"var x=1+1;","device_access":false}""")
                )
            ),
            startCommitted = true,
            repairPurpose = RepairPurpose.NONE
        )
        assertEquals(1, plan.selectedCalls.size)
        assertEquals("program", plan.selectedCalls[0].providerCall.name)
        assertEquals(ToolBinding.TURN_LOCAL, plan.selectedCalls[0].contract.binding)
        assertTrue(plan.rejectedCalls.isEmpty())
    }

    @Test
    fun mutatingProgramRejectedWhenMutationsRestricted() {
        val programContract = RegisteredToolContract(
            name = "program",
            description = "Programmable environment",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.STATE_CHANGING,
            parameters = emptyMap()
        )
        val contracts = registrations + programContract
        val planner = RoundExecutionPlanner(
            registrations = contracts,
            presentedToolNames = setOf("program", "task_memo"),
            mutationsRestricted = true
        )
        val plan = planner.plan(
            NormalizedModelRound.create(
                publicBlocks = emptyList(),
                toolCalls = listOf(
                    ProviderToolCall("p1", "program", """{"code":"android.call('android_act', {});","device_access":true}""")
                )
            ),
            startCommitted = true,
            repairPurpose = RepairPurpose.NONE
        )
        assertTrue(plan.selectedCalls.isEmpty())
        assertEquals(1, plan.rejectedCalls.size)
        assertEquals(RoundDiagnosticCode.TASK_EXECUTION_BUDGET_EXHAUSTED, plan.rejectedCalls[0].diagnosticCode)
    }
}
