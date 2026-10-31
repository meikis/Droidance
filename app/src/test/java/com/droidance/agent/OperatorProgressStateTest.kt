// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.model.llm.models.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class OperatorProgressStateTest {
    @Test
    fun ordinaryActionCarriesNoHarnessAuthoredPlanningMetadata() {
        val result = ActionControlProtocol.extract(
            listOf(
                ToolCall(
                    id = "call_1",
                    name = "click",
                    arguments = """
                        {
                          "x": 10,
                          "y": 20
                        }
                    """.trimIndent()
                )
            )
        )

        assertEquals(10, kotlinx.serialization.json.Json.parseToJsonElement(result.toolCalls.single().arguments)
            .let { it as kotlinx.serialization.json.JsonObject }.getValue("x").toString().toInt())
        assertEquals(emptyMap<String, ActionControlProtocol.Control>(), result.controls)
    }
}
