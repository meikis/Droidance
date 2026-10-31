// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.model.llm.ModelDiagnosticScope
import com.droidance.core.model.llm.ProviderHttpException
import com.droidance.core.model.llm.TransportDiagnosticRedactor
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderExchangeRecorderTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun requestImagesAndResponseAreDurableAndCorrelatedWithoutCredentials() {
        val bytes = byteArrayOf(1, 5, 9, 20)
        val body = buildJsonObject {
            put("instructions", "Exact prompt test-secret")
            put("api_key", "test-secret")
            put("image_url", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
        }
        val scope = ModelDiagnosticScope("thread", "turn", "round")
        val trace = ProviderExchangeRecorder(directory.root).open(scope, "provider", "model", TransportDiagnosticRedactor("test-secret").json(body).jsonObject)
        trace.response(429, "provider-request", "12")
        trace.line("{\"error\":{\"code\":\"rate_limit\"}}")
        trace.finish(ProviderHttpException.fromResponse(429, "provider", "{\"error\":{\"code\":\"rate_limit\"}}"))
        val requestFile = directory.root.walkTopDown().single { it.name == "request.json" }
        val request = Json.parseToJsonElement(requestFile.readText()).jsonObject
        assertEquals("round", request["round_id"]!!.jsonPrimitive.content)
        assertFalse(requestFile.readText().contains("test-secret"))
        assertTrue(requestFile.readText().contains("Exact prompt"))
        assertArrayEquals(bytes, directory.root.walkTopDown().single { it.extension == "png" }.readBytes())
        val completion = Json.parseToJsonElement(File(requestFile.parentFile, "completion.json").readText()).jsonObject
        assertEquals("failed", completion["status"]!!.jsonPrimitive.content)
        assertEquals("RATE_LIMIT", completion["error_category"]!!.jsonPrimitive.content)
        assertEquals(429, completion["http_status"]!!.jsonPrimitive.int)
        assertTrue(File(requestFile.parentFile, "response.jsonl").readText().contains("rate_limit"))
    }

    @Test fun openRequestCanBeExportedBeforeAnyResponseArrives() {
        ProviderExchangeRecorder(directory.root).open(ModelDiagnosticScope("t", "u", "r"), "p", "m", buildJsonObject { put("input", "unfinished") })
        assertEquals(1, directory.root.walkTopDown().count { it.name == "request.json" })
        assertFalse(directory.root.walkTopDown().any { it.name == "completion.json" })
    }

    @Test fun captureStorageFailureDoesNotThrowIntoModelExecution() {
        val blocked = directory.newFile("not-a-directory")
        val trace = ProviderExchangeRecorder(blocked).open(null, "p", "m", buildJsonObject {})
        trace.line("response")
        trace.finish(null)
    }

    @Test fun completeStreamEventsCanBeExportedBeforeFinishAndFinalPartialLineIsFlushed() {
        val trace = ProviderExchangeRecorder(directory.root).open(
            ModelDiagnosticScope("t", "u", "r"), "p", "m", buildJsonObject {})
        trace.line("event: response.created")
        trace.line("data: {\"type\":\"response.created\"}")
        trace.line("")
        val response = directory.root.walkTopDown().single { it.name == "response.jsonl" }
        assertEquals(listOf("event: response.created", "data: {\"type\":\"response.created\"}", ""),
            response.readLines().map { Json.parseToJsonElement(it).jsonObject.getValue("line").jsonPrimitive.content })
        trace.line("last unterminated event")
        trace.finish(null)
        assertEquals("last unterminated event", Json.parseToJsonElement(response.readLines().last())
            .jsonObject.getValue("line").jsonPrimitive.content)
        val once = response.readText()
        trace.finish(null)
        assertEquals(once, response.readText())
    }
}
