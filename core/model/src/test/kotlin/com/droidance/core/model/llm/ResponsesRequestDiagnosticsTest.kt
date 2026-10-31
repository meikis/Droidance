// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResponsesRequestDiagnosticsTest {
    @Test
    fun nonStringExtensionsRemainDiagnosticDataWithoutThrowing() {
        val request = Json.parseToJsonElement("""{
            "instructions":{},"input":[null,{"content":[null,
              {"text":{},"image_url":[]}
            ]}]
        }""").jsonObject
        val original = request.toString()
        val summary = Json.parseToJsonElement(ResponsesRequestDiagnostics.summarize(request)).jsonObject
        assertEquals(1, summary.getValue("input").jsonArray.size)
        assertEquals(original, request.toString())
    }

    @Test
    fun finalRequestShapeIsVisibleWithoutPromptImageOrCredentialValues() {
        val request = Json.parseToJsonElement("""{
            "instructions":"private instructions",
            "previous_response_id":"private response id",
            "api_key":"private credential",
            "input":[{"role":"user","content":[
                {"type":"input_text","text":"private task"},
                {"type":"input_image","image_url":"private image"}
            ]}]
        }""").jsonObject
        val summary = ResponsesRequestDiagnostics.summarize(request)
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("api_key"))
        val shape = Json.parseToJsonElement(summary).jsonObject
        assertEquals(true, shape.getValue("continuation").jsonPrimitive.boolean)
        assertEquals(1, shape.getValue("input").jsonArray.size)
        val changed = JsonObject(request + ("instructions" to JsonPrimitive("new instructions")))
        assertNotEquals(summary, ResponsesRequestDiagnostics.summarize(changed))
        assertEquals(summary, ResponsesRequestDiagnostics.summarize(request))
    }
}
