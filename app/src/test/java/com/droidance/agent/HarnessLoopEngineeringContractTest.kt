// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessLoopEngineeringContractTest {
    private fun projectFile(path: String) = File(File("").absoluteFile.parentFile, path)
        .takeIf { it.exists() }
        ?: File(path)

    private val operator by lazy {
        com.droidance.agent.coordinationSources()
    }
    private val harness by lazy {
        projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
    }
    private val prompts by lazy {
        projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText()
    }
    private val agentCore by lazy {
        projectFile("app/src/main/assets/rules/agent_core.md").readText()
    }

    @Test
    fun stableToolSchemaUsesHarnessGuardsInsteadOfDynamicBusinessMasking() {
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()

        assertTrue(operator.contains("ActionControlProtocol.addControlParameters("))
        assertTrue(operator.contains("val presentedToolNames = toolsForRound.mapTo("))
        assertTrue(operator.contains("presentedToolNames = presentedToolNames"))
        assertTrue(operator.contains("blindActionBlockResult"))
        assertTrue(catalog.contains("screenDependentActionToolNames"))
        assertFalse(operator.contains("actionMaskActive"))
        assertFalse(operator.contains("deepLinkVerificationToolNames"))
        assertTrue(operator.contains("ToolProtocolResult.notExecuted("))
        assertFalse(prompts.contains("blind_action_blocked:"))
        assertFalse(prompts.contains("exact_action_repeated:"))
        assertFalse(prompts.contains("input_focus_next_action:"))
        assertFalse(prompts.contains("input_focus_unbound:"))
        assertFalse(operator.contains("inputFocusDirective"))
    }

    @Test
    fun ordinaryActionsCarryNoHarnessPlanningEssay() {
        val protocol = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/ActionControlProtocol.kt"
        ).readText()

        listOf(
            "task_step",
            "action_intent",
            "action_target",
            "previous_action_verdict",
            "previous_action_evidence"
        ).forEach { assertFalse(protocol.contains(it)) }
        assertFalse(protocol.contains("expected_effect"))
        assertFalse(operator.contains("TaskExperienceRecorder"))
        assertFalse(operator.contains("type = \"local_action_outcome\""))
    }

    @Test
    fun routeFrameAndCoordinateValidationRemainDeterministicHarnessWork() {
        val appLaunch = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()
        val mapper = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ModelCoordinateMapper.kt").readText()

        assertFalse(appLaunch.contains("executionEntryLedger.forgetEntry("))
        assertFalse(appLaunch.contains("DEEPLINK_ROUTE_RETRIED"))
        assertTrue(observe.contains("resetFrameChannel()"))
        assertTrue(harness.contains("frame_recovery_repeats:"))
        assertFalse(prompts.contains("deeplink_route_retry_failed:"))
        assertTrue(mapper.contains("COORDINATE_OUT_OF_RANGE"))
        assertFalse(mapper.contains("[COORDINATE_CLAMPED]"))
    }

    @Test
    fun semanticRouteChoiceIsNotOverriddenByAHarnessRepeatRule() {
        val operator = com.droidance.agent.coordinationSources()
        val prompts = projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText()

        assertFalse(operator.contains("directRouteRepeatGuard"))
        assertFalse(prompts.contains("DEEPLINK_ROUTE_REPEATED"))
        assertFalse(operator.contains("ordered_collection_scope_revisit"))
    }

    @Test
    fun harnessConfigurationIsGenericAndStrictlyTyped() {
        val configuration = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/config/RuleConfiguration.kt"
        ).readText()
        val manager = projectFile(
            "app/src/main/java/com/droidance/config/RuleConfigManager.kt"
        ).readText()

        assertTrue(configuration.contains("data class HarnessPolicy("))
        assertTrue(configuration.contains("data class RuntimeThresholds("))
        assertTrue(manager.contains("ignoreUnknownKeys = false"))
        assertTrue(manager.contains(".setAllowDuplicateKeys(false)"))
        listOf("商品", "咖啡", "外卖", "订单").forEach {
            assertFalse(configuration.contains(it))
            assertFalse(harness.contains(it))
        }
    }
}
