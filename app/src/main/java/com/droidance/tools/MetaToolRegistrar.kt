// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MetaToolRegistrar(
    private val registerTool: (Tool) -> Unit
) {
    fun registerAll() {
        registerWait()
    }

    private fun registerWait() {
        registerTool(object : Tool {
            override val name = "wait"
            override val tier = ToolTier.CORE
            override val category = ToolCategory.SYSTEM
            override val definition = ToolDefinition(
                name,
                "Wait for a specified time in milliseconds.",
                mapOf("ms" to ToolParameter("integer", "Milliseconds to wait. Default: 1000, max: 30000", false))
            )

            override suspend fun execute(arguments: String): ToolExecutionResult {
                val ms = runCatching {
                    Json.parseToJsonElement(arguments).jsonObject["ms"]?.jsonPrimitive?.intOrNull
                }.getOrNull() ?: 1000
                val clamped = ms.coerceIn(100, 30000)
                delay(clamped.toLong())
                return ToolProtocolResult.success("Waited ${clamped}ms")
            }
        })
    }
}
