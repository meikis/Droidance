// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.knowledge.tools.*

import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.knowledge.memory.DurableMemoryApplyResult
import com.droidance.core.knowledge.memory.DurableMemoryOperation
import com.droidance.core.knowledge.memory.DurableMemoryScope
import com.droidance.core.knowledge.memory.DurableMemoryUpdate
import com.droidance.core.knowledge.memory.MemoryItemResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolRegistrarPureTest {
    @Test
    fun appliesUserGroundedUpdatesThroughPort() = runBlocking {
        var recordedUpdates: List<DurableMemoryUpdate>? = null
        var registeredTool: Tool? = null

        MemoryToolRegistrar(
            registerTool = { registeredTool = it },
            applyUpdates = { updates ->
                recordedUpdates = updates
                Result.success(DurableMemoryApplyResult(requested = 1, grounded = 1, changed = 1))
            }
        ).registerAll()

        val tool = checkNotNull(registeredTool)
        assertEquals("update_memory", tool.name)

        val args = """
            {
              "updates": [
                {
                  "scope": "user_profile",
                  "operation": "upsert",
                  "key": "preferred_language",
                  "content": "Kotlin",
                  "expected_revision": "absent",
                  "source_quote": "I prefer Kotlin"
                }
              ]
            }
        """.trimIndent()

        val result = tool.execute(args)

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(true, result.stateChanged)
        assertTrue(result.outputText.contains("MEMORY_UPDATED"))
        assertEquals(1, recordedUpdates?.size)
        val update = recordedUpdates?.first()
        assertEquals(DurableMemoryScope.USER_PROFILE, update?.scope)
        assertEquals(DurableMemoryOperation.UPSERT, update?.operation)
        assertEquals("preferred_language", update?.key)
        assertEquals("Kotlin", update?.content)
        assertEquals("absent", update?.expectedRevision)
        assertEquals("I prefer Kotlin", update?.sourceQuote)
    }

    @Test
    fun aNewKeyMustDeclareTheAbsentRevisionToken() = runBlocking {
        var storageCalled = false
        var registeredTool: Tool? = null
        MemoryToolRegistrar(
            registerTool = { registeredTool = it },
            applyUpdates = {
                storageCalled = true
                Result.success(DurableMemoryApplyResult(1, 1, 1))
            }
        ).registerAll()

        val result = checkNotNull(registeredTool).execute(
            """{"updates":[{"scope":"user_profile","operation":"upsert","key":"name","content":"Alex","source_quote":"call me Alex"}]}"""
        )

        assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
        assertTrue(result.outputText.contains("ARGUMENT_SCHEMA_MISMATCH"))
        assertNull(result.outputText.takeIf { storageCalled })
    }

    @Test
    fun rejectsInvalidArgumentsGracefully() = runBlocking {
        var registeredTool: Tool? = null
        MemoryToolRegistrar(
            registerTool = { registeredTool = it },
            applyUpdates = { Result.success(DurableMemoryApplyResult(0, 0, 0)) }
        ).registerAll()

        val tool = checkNotNull(registeredTool)
        val result = tool.execute("{}")

        assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
        assertTrue(result.outputText.contains("ARGUMENT_SCHEMA_MISMATCH"))
    }

    @Test
    fun failedBatchNeverClaimsNoChangeAndReportsKnownPartialCommits() = runBlocking {
        for (committed in listOf(0, 1)) {
            var tool: Tool? = null
            MemoryToolRegistrar({ tool = it }, {
                Result.success(DurableMemoryApplyResult(2, 2, committed, listOf(
                    MemoryItemResult("first", if (committed == 1) "committed" else "failed", null),
                    MemoryItemResult("second", "failed", null, "storage failed")
                )))
            }).registerAll()
            val result = checkNotNull(tool).execute(
                """{"updates":[{"scope":"long_term","operation":"upsert","key":"first","content":"value","expected_revision":"absent","source_quote":"value"},{"scope":"long_term","operation":"upsert","key":"second","content":"value","expected_revision":"absent","source_quote":"value"}]}""")
            assertEquals(ToolExecutionStatus.FAILED, result.executionStatus)
            assertEquals(if (committed == 1) true else null, result.stateChanged)
        }
    }
}
