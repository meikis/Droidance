// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StoppedTaskReplySourceTest {
    @Test fun closingRequestCannotExecuteToolsAndPublishesOnlyAfterCommit() {
        val source = File("../core/agent/src/main/kotlin/com/droidance/core/agent/TaskTurnRecovery.kt").readText()
            .substringAfter("internal suspend fun TaskTurnSession.explainStoppedTask")
            .substringBefore("suspend fun execute(): TurnResult")
        assertTrue(source.contains("!stoppedReplyContextAvailable || !policy.eligible(result)"))
        assertTrue(source.contains("tools = emptyList()"))
        assertTrue(source.contains("enableComputerTool = false"))
        assertTrue(source.contains("isolatedContext = true"))
        assertTrue(source.contains("timeoutMs = policy.timeoutMs"))
        assertTrue(source.contains("capture.toolCalls.isNotEmpty() || reply.isBlank() || !publicationValid"))
        assertTrue(source.contains("validatePublicText(reply)"))
        assertTrue(source.contains("!WorkspaceDeliverable.isLink(link)"))
        assertTrue(source.contains("retained_task_contract"))
        assertTrue(source.contains("onContent = {}"))
        assertTrue(source.indexOf("roundSession.commit(") < source.indexOf("result.withStoppedTaskReply(reply)"))
        assertTrue(source.contains("error is kotlinx.coroutines.CancellationException"))
        assertFalse(source.contains("toolExecutor.execute("))
    }

    private fun projectFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path")
    }
}
