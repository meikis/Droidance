// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolExecutionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportResultToolTest {
    private val tool = ReportResultTool { section -> "rule:$section" }

    @Test fun schemaTakesOnlyTheResultText() {
        assertEquals("report_result", tool.definition.name)
        assertEquals("rule:report_result_tool", tool.definition.description)
        assertEquals(setOf("text"), tool.definition.parameters.keys)
        assertTrue(tool.definition.parameters.getValue("text").required)
    }

    @Test fun nonBlankTextIsAcceptedAndReadBackForTheConversation() = runBlocking {
        val args = """{"text":"| A | B |\n|---|---|"}"""
        assertTrue(tool.execute(args).isVerifiedSuccess)
        assertEquals("| A | B |\n|---|---|", ReportResultTool.text(args))
    }

    @Test fun missingOrBlankTextIsNotExecuted() = runBlocking {
        for (args in listOf("{}", """{"text":"  "}""", """{"text":3}""", "not json")) {
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, tool.execute(args).executionStatus)
            assertNull(ReportResultTool.text(args))
        }
    }
}
