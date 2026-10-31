// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.harness.ResultCardToolItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessUtilityToolRegistrarTest {
    @Test
    fun askHumanAndResultCardStoreOnlyCurrentTaskStructure() = runBlocking {
        val requests = mutableListOf<HumanAssistanceRequest>()
        val tools = mutableMapOf<String, Tool>()
        val state = HarnessUtilityState()
        HarnessUtilityToolRegistrar(
            registerTool = { tool -> tools[tool.name] = tool },
            stateProvider = { state },
            requestHuman = { reason, instruction ->
                requests += HumanAssistanceRequest("configured_boundary", reason, instruction)
            },
            allowedHumanBoundaryTypes = listOf("configured_boundary"),
            recordCompletion = { ToolProtocolResult.success("recorded") }
        ).registerAll()

        val item = tools.getValue("present_result_card").definition.parameters.getValue("items").items!!
        assertEquals(setOf("text", "evidence_refs", "deeplink"), item.properties!!.keys)
        assertFalse(item.properties!!.getValue("evidence_refs").required)
        assertFalse(item.properties!!.getValue("deeplink").required)

        tools.getValue("ask_human").execute(
            """{"boundary_type":"configured_boundary","reason":"需要用户处理","instruction":"处理后继续"}"""
        )
        tools.getValue("present_result_card").execute(
            """{"title":"结果","items":[{"text":"已整理","deeplink":"","evidence_refs":["source"]},{"text":"count: 2","deeplink":"droidance://count/2","evidence_refs":["source"]}]}"""
        )

        assertEquals(
            HumanAssistanceRequest("configured_boundary", "需要用户处理", "处理后继续"),
            state.humanAssistanceRequest
        )
        assertEquals(state.humanAssistanceRequest, requests.single())
        assertEquals(
            ResultCard(
                "结果",
                listOf(
                    ResultCardToolItem("已整理", ""),
                    ResultCardToolItem("count: 2", "droidance://count/2")
                )
            ),
            state.resultCard
        )
        assertEquals(
            "**结果**\n\n1. 已整理\n\n2. [count: 2](droidance://count/2)",
            state.resultCard?.toUserFacingText()
        )
    }

    @Test
    fun resultCardRecordsExplicitGoalCompletionAndRejectsConflictingContinuationBeforePublication() = runBlocking {
        val state = HarnessUtilityState()
        val completion = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        HarnessUtilityToolRegistrar({ tools[it.name] = it }, { state }, { _, _ -> }, emptyList()) { candidate ->
            if (completion.record(candidate)) ToolProtocolResult.success("recorded")
            else ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        val card = tools.getValue("present_result_card")
        for (extra in listOf("\"goal_complete\":true,\"next_check_seconds\":1", "\"goal_complete\":\"true\"",
                "\"next_check_seconds\":-1", "\"checkpoint\":\"${"x".repeat(16_001)}\"")) {
            assertFalse(card.execute("""{"title":"Result","items":[{"text":"Observed result"}],$extra}""").isVerifiedSuccess)
            assertEquals(null, state.resultCard)
            assertEquals(null, completion.result)
        }
        assertTrue(card.execute("""{"title":"Result","items":[{"text":"Observed result"}],"checkpoint":"A: settled; B: pending optional reply", "goal_complete":true, "notify_user":false}""").isVerifiedSuccess)
        assertTrue(completion.result!!.goalComplete)
        assertEquals("A: settled; B: pending optional reply", completion.result!!.checkpoint)
        assertEquals(false, completion.result!!.notifyUser)
        assertEquals(state.resultCard!!.toUserFacingText(), completion.result!!.summary)
        val original = state.resultCard
        assertFalse(card.execute("""{"title":"Replacement","items":[{"text":"Do not publish"}]}""").isVerifiedSuccess)
        assertEquals(original, state.resultCard)
    }

    @Test
    fun resultCardCanYieldWithoutImplicitlyCompletingTheGoal() = runBlocking {
        val state = HarnessUtilityState()
        val completion = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        HarnessUtilityToolRegistrar({ tools[it.name] = it }, { state }, { _, _ -> }, emptyList()) { candidate ->
            completion.record(candidate)
            ToolProtocolResult.success("recorded")
        }.registerAll()
        val card = tools.getValue("present_result_card")
        assertTrue(card.execute("""{"title":"Progress","items":[{"text":"External change pending"}],"checkpoint":"A: waiting", "next_check_seconds":30}""").isVerifiedSuccess)
        assertFalse(completion.result!!.goalComplete)
        assertEquals(30L, completion.result!!.nextCheckSeconds)
        assertEquals("A: waiting", completion.result!!.checkpoint)
        assertEquals(tools.getValue("present_result_card").definition.parameters.keys,
            setOf("title", "items", "checkpoint", "next_check_seconds", "goal_complete", "notify_user"))
    }

    @Test
    fun singleMarkdownTableItemIsPreservedWithoutAListPrefix() {
        val card = ResultCard(
            title = "处理器清单",
            items = listOf(ResultCardToolItem("| 型号 | 价格 |\n| --- | --- |\n| 9850X3D | ¥3357.88 |", ""))
        )
        assertEquals(
            "**处理器清单**\n\n| 型号 | 价格 |\n| --- | --- |\n| 9850X3D | ¥3357.88 |",
            card.toUserFacingText()
        )
    }

    @Test
    fun multilineResultItemKeepsItsDeeplink() {
        val card = ResultCard(
            title = "结果",
            items = listOf(ResultCardToolItem("商品 A\n价格：¥100", "demo://item-a"))
        )
        assertEquals("**结果**\n\n[商品 A](demo://item-a)\n价格：¥100", card.toUserFacingText())
    }

    @Test
    fun numberedMarkdownItemsDoNotReceiveASecondNumber() {
        val card = ResultCard(
            title = "整理结果",
            items = listOf(
                ResultCardToolItem("1. **第一项**\n\n- 价格：¥100", ""),
                ResultCardToolItem("2. 第二项", "")
            )
        )
        val markdown = card.toUserFacingText()
        assertTrue(markdown.contains("1. **第一项**"))
        assertTrue(markdown.contains("2. 第二项"))
        assertFalse(markdown.contains("1. 1."))
        assertFalse(markdown.contains("2. 2."))
    }

    @Test
    fun askHumanRejectsUnconfiguredBoundaryWithoutPausing() = runBlocking {
        var requestCount = 0
        val tools = mutableMapOf<String, Tool>()
        val state = HarnessUtilityState()
        HarnessUtilityToolRegistrar(
            registerTool = { tool -> tools[tool.name] = tool },
            stateProvider = { state },
            requestHuman = { _, _ -> requestCount += 1 },
            allowedHumanBoundaryTypes = listOf("configured_boundary"),
            recordCompletion = { ToolProtocolResult.success("recorded") }
        ).registerAll()

        val result = tools.getValue("ask_human").execute(
            """{"boundary_type":"ordinary_navigation","reason":"navigation is difficult"}"""
        )

        assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
        assertEquals("HUMAN_BOUNDARY_REJECTED", result.diagnosticCode)
        assertTrue(result.outputText.contains("HUMAN_BOUNDARY_REJECTED"))
        assertEquals(0, requestCount)
        assertEquals(null, state.humanAssistanceRequest)
    }

    @Test
    fun configuredIrreversibleBoundariesAlwaysPauseForHumanTakeover() = runBlocking {
        val allowed = listOf("live_verification", "secret_credential", "payment_authorization", "irreversible_confirmation")
        val requests = mutableListOf<HumanAssistanceRequest>()

        listOf(
            "live_verification" to "Enter the OTP",
            "payment_authorization" to "Authorize payment",
            "irreversible_confirmation" to "Confirm the irreversible action"
        ).forEach { (boundary, reason) ->
            val state = HarnessUtilityState()
            val scopedTools = mutableMapOf<String, Tool>()
            HarnessUtilityToolRegistrar(
                registerTool = { tool -> scopedTools[tool.name] = tool },
                stateProvider = { state },
                requestHuman = { _, _ -> },
                allowedHumanBoundaryTypes = allowed,
                recordCompletion = { ToolProtocolResult.success("recorded") }
            ).registerAll()
            scopedTools.getValue("ask_human").execute(
                """{"boundary_type":"$boundary","reason":"$reason"}"""
            )
            requests += requireNotNull(state.humanAssistanceRequest)
        }

        assertEquals(
            listOf("live_verification", "payment_authorization", "irreversible_confirmation"),
            requests.map(HumanAssistanceRequest::boundaryType)
        )
    }
}
