// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.device.ExecutionSpace
import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalOperatorToolCatalogTest {
    @Test
    fun standardVirtualSpaceExposesTheMinimalContract() {
        val expected = linkedSetOf(
            "program", "workspace", "automation", "manage_app_takeover",
            "android_read",
            "android_act",
            "shell",
            "screen_act",
            "ask_human",
            "session_search",
            "session_read",
            "update_memory", "experience", "task_memo", "report_result",
            "recall_skill",
            "save_skill",
            "verify_skill",
            "run_skill",
            "read_skill_file",
            "skill_source",
            "present_result_card",
            "done"
        )

        assertEquals(ToolCatalog.localKnowledgeToolNames, ToolCatalog.operatorToolNamesFor(RuntimeMode.STANDARD, ExecutionSpace.MAIN_SCREEN))
        assertEquals(ToolCatalog.localKnowledgeToolNames, ToolCatalog.operatorToolNamesFor(RuntimeMode.ULTRA, ExecutionSpace.MAIN_SCREEN))
        assertEquals(
            expected,
            ToolCatalog.operatorToolNamesFor(RuntimeMode.STANDARD, ExecutionSpace.VIRTUAL_SPACE)
        )
        assertEquals(
            ToolCatalog.localKnowledgeToolNames,
            ToolCatalog.operatorToolNamesFor(RuntimeMode.ULTRA, ExecutionSpace.VIRTUAL_SPACE)
        )
        assertEquals(
            expected,
            ToolCatalog.operatorToolNamesFor(null, ExecutionSpace.VIRTUAL_SPACE)
        )
        val backgroundExpected = (expected - "present_result_card") + setOf(
            "read_notifications",
            "click_notification",
            "dismiss_notification",
            "manage_app_takeover"
        )
        assertEquals(
            backgroundExpected,
            ToolCatalog.operatorToolNamesFor(
                RuntimeMode.STANDARD,
                ExecutionSpace.VIRTUAL_SPACE,
                OperatorToolProfile.BACKGROUND_AUTOMATION
            )
        )
    }
}
