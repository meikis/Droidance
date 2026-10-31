// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKnowledgeToolSurfaceTest {
    @Test
    fun localKnowledgeProfileIncludesSkillLifecycleWithoutDirectDeviceTools() {
        val expected = setOf(
            "session_search",
            "session_read",
            "recall_skill",
            "save_skill",
            "read_skill_file", "skill_source", "verify_skill", "run_skill", "task_memo", "report_result",
            "update_memory", "experience", "workspace", "program", "automation",
            "done"
        )

        val actual = ToolCatalog.operatorToolNamesFor(
            mode = null,
            executionSpace = null,
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE
        )

        assertEquals(expected, actual)
        assertFalse(actual.contains(DeviceToolNames.ANDROID_READ))
        assertFalse(actual.contains(DeviceToolNames.ANDROID_ACT))
        assertFalse(actual.contains(DeviceToolNames.SHELL))
        assertFalse(actual.contains(DeviceToolNames.SCREEN_ACT))
        assertTrue(actual.contains("run_skill"))
        assertTrue(actual.contains("verify_skill"))
        assertTrue(actual.contains("program"))
    }

    @Test
    fun nonLocalProfilesRejectAMissingExecutionSpace() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolCatalog.operatorToolNamesFor(
                mode = RuntimeMode.ULTRA,
                executionSpace = null,
                profile = OperatorToolProfile.DEFAULT
            )
        }
    }
}
