// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.config.DeepLinkKnowledgeStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolCoreExposureTest {
    @Test
    fun operatorToolsDoNotExposeMockSemanticActions() {
        listOf(
            "set_query",
            "ensure_search_results",
            "read_result_cards",
            "tap_result_card"
        ).forEach { toolName ->
            assertFalse("$toolName is a mock semantic tool and must not be sent to the operator", toolName in ToolCatalog.operatorToolNames)
        }
    }

    @Test
    fun operatorToolSetUsesExactDirectHarnessTools() {
        val source = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()
        val operatorToolsBlock = Regex("""operatorToolNames: Set<String> = linkedSetOf\(([\s\S]*?)\)\s*\n""")
            .find(source)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val executorSource = com.droidance.agent.executorSources()
        val operatorSource = com.droidance.agent.coordinationSources()

        assertTrue("Operator should have a dedicated slim tool set", operatorToolsBlock.isNotBlank())
        listOf(
            "android_read", "android_act", "shell", "screen_act",
            "ask_human", "session_search",
            "recall_skill", "save_skill", "verify_skill",
            "run_skill", "present_result_card", "done"
        ).forEach {
            assertTrue("operatorToolNames should include $it", it in ToolCatalog.operatorToolNames)
        }
        listOf("wait", "observe_screen", "click", "swipe", "type_text", "press_key", "open_app", "query_android_content", "run_shell", "screen_actions", "crop_zoom", "mobile_actions", "load_tools", "todo_write", "task_done", "som_tap", "scroll", "ui_input", "submit_search", "list_deeplink_candidates", "launch_deeplink", "shell_exec", "find_element", "find_and_click", "verify_deep_link", "update_user_profile").forEach {
            assertFalse("operatorToolNames should not include low-level/default-noisy tool $it", it in ToolCatalog.operatorToolNames)
        }
        assertTrue(executorSource.contains("fun getOperatorToolDefinitions("))
        assertTrue(executorSource.contains("ToolCatalog.operatorToolNamesFor(mode, executionSpace, profile)"))
        assertTrue(operatorSource.contains("toolExecutor.getOperatorToolDefinitions(mode, executionSpace, toolProfile)"))
    }

    @Test
    fun removedAnnotationToolsCannotBeReactivated() {
        val executor = com.droidance.agent.executorSources()

        assertFalse(executor.contains("SOM_ANNOTATION_TOOLS"))
        assertFalse(executor.contains("som_tap"))
        assertFalse(executor.contains("activatedTools"))
        assertFalse(executor.contains("loadToolCategory"))
        assertFalse(executor.contains("MobileActionRouter("))
        assertFalse(executor.contains("MobileActionToolRegistrar("))
    }

    @Test
    fun onlyStandardVirtualSpaceExposesDeviceTools() {
        val tools = ToolCatalog.operatorToolNamesFor(
            com.droidance.core.agent.device.RuntimeMode.STANDARD,
            com.droidance.core.agent.device.ExecutionSpace.VIRTUAL_SPACE
        )
        assertTrue("screen_act" in tools)
        assertTrue("shell" in tools)
        assertFalse("computer" in tools)
        assertFalse("perceive" in tools)
        assertFalse("shell_exec" in tools)
        for (mode in com.droidance.core.agent.device.RuntimeMode.entries) {
            for (space in com.droidance.core.agent.device.ExecutionSpace.entries) {
                if (mode == com.droidance.core.agent.device.RuntimeMode.STANDARD &&
                    space == com.droidance.core.agent.device.ExecutionSpace.VIRTUAL_SPACE) continue
                val retired = ToolCatalog.operatorToolNamesFor(mode, space)
                assertFalse("screen_act" in retired)
                assertFalse("shell" in retired)
                assertFalse("android_act" in retired)
            }
        }
    }

    @Test
    fun shellBackedIntentToolsFailExplicitlyWhenNoShellChannelIsAvailable() {
        val catalog = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/tools/ToolCatalog.kt").readText()
        val executor = com.droidance.agent.executorSources()

        assertTrue(catalog.contains("shellBackedIntentToolNames"))
        assertTrue(executor.contains("private fun isShellBackedToolAvailable(toolName: String): Boolean"))
        // 统一走 DeviceShellCommandChannel（Binder 优先、root 兜底），MAIN 与 VIRTUAL 都认可常驻 Binder。
        assertTrue(executor.contains("deviceShellCommandChannel.isAvailable()"))
        assertTrue(executor.contains("isShellBackedToolAvailable(toolCall.name)"))
        assertFalse(executor.contains("getActiveToolDefinitions"))
    }

    @Test
    fun launchedUnverifiedDeepLinkDoesNotCountAsRouteSuccess() {
        val source = com.droidance.agent.executorSources()
        val launchedUnverifiedRecord = Regex(
            """recordLaunchResult\([\s\S]*?success\s*=\s*true[\s\S]*?resultLabel\s*=\s*"launched_unverified""""
        )

        assertFalse(
            "launched_unverified only proves foreground package launch, not semantic success",
            launchedUnverifiedRecord.containsMatchIn(source)
        )
        assertFalse(
            "an arbitrary succeeding screen action cannot promote a pending route to success",
            source.contains("grounded screen action followed the launched route")
        )
    }

    @Test
    fun launchedUnverifiedDeepLinkDoesNotUpdateReliabilityStats() {
        assertFalse(DeepLinkKnowledgeStore.shouldUpdateRouteStatsForResult("launched_unverified", success = false))
        assertFalse(DeepLinkKnowledgeStore.shouldUpdateRouteStatsForResult("launched_unverified", success = true))
        assertFalse(DeepLinkKnowledgeStore.shouldUpdateRouteStatsForResult("launched_pending", success = false))
        assertFalse(DeepLinkKnowledgeStore.shouldUpdateRouteStatsForResult("launched_pending", success = true))
        assertTrue(DeepLinkKnowledgeStore.shouldUpdateRouteStatsForResult("success", success = true))
        assertTrue(DeepLinkKnowledgeStore.shouldUpdateRouteStatsForResult("route_failed", success = false))
    }

    @Test
    fun tapDoesNotTreatPartialPixelOnlyValidationAsConfirmedSuccess() {
        val source = com.droidance.agent.executorSources()

        assertFalse(
            "PARTIAL means only weak local evidence and must not be reported as a confirmed tap success",
            source.contains("ProgressState.PARTIAL -> true")
        )
    }

    @Test
    fun pointCoordinateToolsUseBoundsCheckedMappingBeforeInjection() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/tools/TouchInputService.kt").readText()

        assertTrue(source.contains("private fun mapPointOrBoundsFail("))
        assertTrue(source.contains("private fun mapGestureOrBoundsFail("))
        assertTrue(source.substringAfter("suspend fun click(").substringBefore("suspend fun swipe(").contains("mapPointOrBoundsFail"))
        assertTrue(source.substringAfter("suspend fun swipe(").substringBefore("suspend fun scroll(").contains("mapGestureOrBoundsFail"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
