// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.device.agent.device.DeviceBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KeyInputService(
    private val deviceBackend: DeviceBackend
) {
    suspend fun pressKey(arguments: String): ToolExecutionResult {
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val rawKey = input["key"]?.jsonPrimitive?.content
            ?: return ToolProtocolResult.missingArgument("key")
        val keyCode = KeyEventPolicy.resolveKeyCode(rawKey)
            ?: return ToolProtocolResult.invalidArgument("key", "unsupported_value", rawKey)
        val result = deviceBackend.pressKey(keyCode)
        return if (result.success) {
            ToolProtocolResult.accepted("Key sent: $rawKey").copy(evidenceStatus = com.droidance.core.agent.event.ToolEvidenceStatus.NOT_REQUIRED)
        } else {
            ToolProtocolResult.failed(
                code = result.diagnosticCode ?: "KEYPRESS_FAILED",
                retryable = true,
                details = buildJsonObject {
                    put("message", JsonPrimitive(result.message.take(300)))
                }
            )
        }
    }
}
