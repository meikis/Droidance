// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProviderRequestFieldsTest {
    private val catalog = ProviderRequestFields(
        """{"gateways":[{"hosts":["gw.example"],"responses":{"caching":{"type":"enabled"}}}]}""")

    @Test fun matchingHostsReceiveFieldsWithoutReplacingRequestValues() {
        val fields = catalog.responses("https://gw.example/api/v3")
        assertEquals(setOf("caching"), fields.keys)
        assertTrue(catalog.responses("https://other.example/v1").isEmpty())
        val request = buildJsonObject { put("model", "m"); putJsonObject("caching") { put("type", "disabled") } }
        assertEquals(request, ProviderRequestFields.apply(request, fields))
        assertEquals(JsonPrimitive("enabled"), (ProviderRequestFields.apply(buildJsonObject { put("model", "m") }, fields)
            .getValue("caching") as kotlinx.serialization.json.JsonObject).getValue("type"))
    }

    @Test fun onlyARequestRejectionNamingAFieldDisablesTheFields() {
        val fields = catalog.responses("https://gw.example/api/v3")
        assertTrue(ProviderRequestFields.rejectedBy(ProviderHttpException.fromResponse(400, "gw",
            """{"error":{"code":"InvalidParameter","message":"caching is not enabled for this account"}}"""), fields))
        assertFalse(ProviderRequestFields.rejectedBy(ProviderHttpException.fromResponse(400, "gw",
            """{"error":{"code":"InvalidParameter","message":"input is invalid"}}"""), fields))
        assertFalse(ProviderRequestFields.rejectedBy(ProviderHttpException.fromResponse(500, "gw", "caching"), fields))
    }

    @Test fun bundledCatalogParses() {
        var directory = File(System.getProperty("user.dir"))
        while (!File(directory, "app/src/main/assets/model_capabilities/request_fields.json").exists())
            directory = directory.parentFile ?: error("Missing request field catalog")
        val bundled = ProviderRequestFields(File(directory, "app/src/main/assets/model_capabilities/request_fields.json").readText())
        assertTrue("caching" in bundled.responses("https://ark.cn-beijing.volces.com/api/v3"))
        // The coding plan rejects caching: "caching is not supported in coding plan / agent plan / codex flow".
        assertTrue(bundled.responses("https://ark.cn-beijing.volces.com/api/coding/v3").isEmpty())
    }
}
