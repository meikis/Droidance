// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import com.droidance.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionActionPresentationTest {
    @Test fun aSingleElementBatchUsesItsActualActionPresentation() {
        val cases = listOf(
            """{"actions":[{"op":"click","x":240,"y":600}]}""" to
                R.string.execution_action_click,
            """{"actions":[{"op":"wait","ms":120}]}""" to
                R.string.execution_action_wait,
            """{"actions":[{"op":"screenshot"}]}""" to
                R.string.execution_action_observe_screen,
            """{"actions":[{"op":"type_text","text":"query","clear":true}]}""" to
                R.string.execution_action_type_text,
            """{"actions":[{"op":"press_key","key":"ENTER"}]}""" to
                R.string.execution_action_press_key
        )

        cases.forEach { (arguments, expectedResource) ->
            assertEquals(expectedResource, executionActionResourceOrNull("screen_act", arguments))
        }
    }

    @Test fun multipleBatchActionsRemainPresentedAsABatch() {
        assertEquals(R.string.execution_action_batch, executionActionResourceOrNull(
            "screen_act",
            """{"actions":[{"op":"click","x":240,"y":600},{"op":"wait","ms":120}]}"""
        ))
    }
    @Test fun friendly_parameters_keep_coordinates_paths_and_keys_but_never_echo_typed_text() {
        assertEquals("240, 600", executionActionParameterSummary("screen_act", """{"actions":[{"op":"click","x":240,"y":600}]}"""))
        assertEquals("500, 800 → 500, 300", executionActionParameterSummary("screen_act", """{"actions":[{"op":"swipe","startX":500,"startY":800,"endX":500,"endY":300}]}"""))
        assertEquals("BACK", executionActionParameterSummary("screen_act", """{"actions":[{"op":"press_key","key":"BACK"}]}"""))
        assertEquals(null, executionActionParameterSummary("screen_act", """{"actions":[{"op":"type_text","text":"private content"}]}"""))
        assertEquals(null, executionActionParameterSummary("screen_act", """{"actions":[{"op":"click","x":{"redacted":true},"y":20}]}"""))
    }

    @Test fun batch_details_show_each_actual_action_without_losing_its_parameters() {
        val args = executionActionDisplayArguments("screen_act", """{"actions":[{"op":"click","x":120,"y":300},{"op":"press_key","key":"ENTER"}]}""")
        assertEquals(2, args.size)
        assertEquals("120, 300", executionActionParameterSummary("screen_act", args[0]))
        assertEquals("ENTER", executionActionParameterSummary("screen_act", args[1]))
    }

    @Test fun non_gui_tool_parameters_show_commands_paths_and_operations() {
        assertEquals("ls -la /sdcard", executionActionParameterSummary("shell", """{"command":"ls -la /sdcard"}"""))
        assertEquals("scripts/worker.js", executionActionParameterSummary("program", """{"op":"run","path":"scripts/worker.js"}"""))
        assertEquals("write scripts/test.sh", executionActionParameterSummary("workspace", """{"op":"write","path":"scripts/test.sh","text":"echo hello"}"""))
        assertEquals("content://contacts/people", executionActionParameterSummary("android_read", """{"op":"content","uri":"content://contacts/people"}"""))
        assertEquals("battery", executionActionParameterSummary("android_read", """{"op":"system","service":"battery"}"""))
        assertEquals("com.tencent.mm", executionActionParameterSummary("read_notifications", """{"package_name":"com.tencent.mm"}"""))
        assertEquals("order_coffee", executionActionParameterSummary("run_skill", """{"name":"order_coffee"}"""))
    }
}
