// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolResponseRequirementTest {
    @Test fun activatedLoopRequiresAToolWithoutChangingModelReasoningOrPayload() {
        val request = Json.parseToJsonElement("""{"model":"same-model","reasoning":{"effort":"low"},"tools":[{"type":"function"}],"tool_choice":"auto","input":[]}""").jsonObject
        val required = ToolResponseRequirement.apply(request, true)
        assertEquals(JsonPrimitive("required"), required["tool_choice"])
        assertEquals(request - "tool_choice", required - "tool_choice")
        assertSame(request, ToolResponseRequirement.apply(request, false))
    }

    @Test fun chatAndStoppedRepliesWithoutToolsRemainValid() {
        for (request in listOf(buildJsonObject {}, buildJsonObject { put("tools", JsonArray(emptyList())) })) {
            assertSame(request, ToolResponseRequirement.apply(request, true))
        }
    }
}
