// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.knowledge.memory.DurableMemoryApplyResult
import com.droidance.core.knowledge.memory.DurableMemoryUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MemoryToolRegistrarTest {
    private val arguments = """{"updates":[{"scope":"user_profile","operation":"upsert","key":"address","content":"Captain","expected_revision":"absent","source_quote":"call me Captain"}]}"""

    private fun tool(apply: suspend (List<DurableMemoryUpdate>) -> Result<DurableMemoryApplyResult>): Tool {
        lateinit var tool: Tool
        MemoryToolRegistrar({ tool = it }, apply).registerAll()
        return tool
    }

    @Test
    fun persistenceResultPrecedesCompletionAndNoCompletionStateIsChanged() = runBlocking {
        val state = CompletionState()
        lateinit var done: Tool
        CompletionToolRegistrar({ done = it }, { state }, "Complete public answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) ToolProtocolResult.success("recorded")
            else ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        var persisted = false
        val memory = tool {
            assertNull(state.result)
            assertEquals("call me Captain", it.single().sourceQuote)
            persisted = true
            Result.success(DurableMemoryApplyResult(1, 1, 1))
        }
        val memoryResult = memory.execute(arguments)
        assertEquals(ToolExecutionStatus.SUCCEEDED, memoryResult.executionStatus)
        assertEquals("MEMORY_UPDATED", code(memoryResult))
        assertTrue(persisted)
        assertNull(state.result)
        val doneResult = done.execute("""{"status":"done","results":[{"text":"Memory saved","evidence_refs":["memory-result"]}],"unresolved_requirements":[]}""")
        assertEquals(ToolExecutionStatus.SUCCEEDED, doneResult.executionStatus)
        assertEquals("Memory saved", state.result?.summary)
        assertFalse(done.definition.parameters.containsKey("memory_updates"))
    }

    @Test
    fun rejectionNoChangeAndPartialApplicationAreDistinguished() = runBlocking {
        for ((result, expected) in listOf(
            DurableMemoryApplyResult(1, 0, 0) to "MEMORY_SOURCE_REJECTED",
            DurableMemoryApplyResult(1, 1, 0) to "MEMORY_UNCHANGED",
            DurableMemoryApplyResult(2, 1, 1) to "MEMORY_PARTIALLY_APPLIED"
        )) {
            val output = tool { Result.success(result) }.execute(arguments)
            assertEquals(
                if (result.grounded == 0) ToolExecutionStatus.NOT_EXECUTED else ToolExecutionStatus.SUCCEEDED,
                output.executionStatus
            )
            assertEquals(expected, code(output))
            assertEquals(result.changed.toString(), Json.parseToJsonElement(output.outputText).jsonObject
                .getValue("details").jsonObject.getValue("changed").jsonPrimitive.content)
        }
    }

    @Test
    fun storageFailuresAreReturnedWithoutLeakingExceptionText() = runBlocking {
        val failure = IOException("private path and payload")
        val output = tool { Result.failure(failure) }.execute(arguments)
        assertEquals(ToolExecutionStatus.FAILED, output.executionStatus)
        assertEquals("MEMORY_UPDATE_FAILED", output.diagnosticCode)
        assertEquals("MEMORY_UPDATE_FAILED", code(output))
        assertFalse(output.outputText.contains(failure.message!!))
        assertEquals("true", Json.parseToJsonElement(output.outputText).jsonObject.getValue("details")
            .jsonObject.getValue("partial_writes_possible").jsonPrimitive.content)
        val thrown = tool { throw failure }.execute(arguments)
        assertEquals(ToolExecutionStatus.FAILED, thrown.executionStatus)
        assertEquals("MEMORY_UPDATE_FAILED", thrown.diagnosticCode)
        assertEquals("MEMORY_UPDATE_FAILED", code(thrown))
    }

    @Test
    fun cancellationIsNotConvertedToPersistenceFailure() {
        val cancelled = CancellationException("cancel")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            runBlocking { tool { Result.failure(cancelled) }.execute(arguments) }
        })
    }

    @Test
    fun malformedOrEmptyUpdatesNeverReachStorage() = runBlocking {
        val memory = tool { error("Storage must not run") }
        for (input in listOf("{}", """{"updates":[]}""", """{"updates":[{}]}""",
            arguments.replace("\"address\"", "\"INVALID KEY\""), arguments.replace("\"Captain\"", "\"\""))) {
            val result = memory.execute(input)
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
            assertEquals("ARGUMENT_SCHEMA_MISMATCH", result.diagnosticCode)
            assertEquals("ARGUMENT_SCHEMA_MISMATCH", code(result))
        }
    }

    private fun code(result: ToolExecutionResult): String = Json.parseToJsonElement(result.outputText).jsonObject
        .getValue("code").jsonPrimitive.content
}
