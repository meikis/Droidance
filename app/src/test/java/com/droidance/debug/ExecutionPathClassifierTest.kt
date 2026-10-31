// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionPathClassifierTest {
    @Test
    fun classifiesNativeAndGuiPathsFromTheExecutedTool() {
        assertEquals(
            "deeplink",
            ExecutionPathClassifier.classify("open_app", """{"uri":"scheme://entry"}""")
        )
        assertEquals(
            "shell",
            ExecutionPathClassifier.classify("run_shell", """{"command":"content query --uri content://sms"}""")
        )
        assertEquals(
            "shell",
            ExecutionPathClassifier.classify(
                "run_shell",
                """{"command":"am start -a android.intent.action.SET_ALARM --ei hour 9"}"""
            )
        )
        assertEquals(
            "shell",
            ExecutionPathClassifier.classify("run_shell", """{"command":"cmd role get-role-holders android.app.role.SMS"}""")
        )
        assertEquals(
            "android_api",
            ExecutionPathClassifier.classify("query_android_content", """{"uri":"content://sms"}""")
        )
        assertEquals(
            "deeplink",
            ExecutionPathClassifier.classify("open_app", """{"app_name":"京东","route_action":"搜索"}""")
        )
        assertEquals("gui", ExecutionPathClassifier.classify("observe_screen", "{}"))
        assertEquals("compiled_skill", ExecutionPathClassifier.classify("run_skill", "{}"))
        assertNull(ExecutionPathClassifier.classify("done", "{}"))
    }
}
