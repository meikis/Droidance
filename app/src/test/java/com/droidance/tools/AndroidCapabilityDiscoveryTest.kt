// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.config.DeepLinkKnowledgeStore
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AndroidCapabilityDiscoveryTest {
    private fun route(id: String, pkg: String) = DeepLinkKnowledgeStore.DeepLinkRoute(
        id = id, category = "fixture", appName = pkg, packageName = pkg,
        action = "search", uriTemplate = "$pkg://search?q={keyword}")

    @Test fun firstRoundContainsInstalledActionsAndRequiredParametersWithoutAnotherToolCall() {
        val catalog = listOf(route("a", "example.a"), route("b", "example.b"))
        val context = Json.parseToJsonElement(AndroidCapabilityDiscovery.projectEntryContext(
            catalog, setOf("example.a"), 2048)).jsonObject
        assertEquals("installed_apps", context.getValue("index_scope").jsonPrimitive.content)
        val row = context.getValue("app_index").jsonArray.single().jsonArray
        assertEquals("example.a", row[1].jsonPrimitive.content)
        val action = row[2].jsonArray.single().jsonArray
        assertEquals("search", action[0].jsonPrimitive.content)
        assertEquals(listOf("keyword"), action[1].jsonArray.map { it.jsonPrimitive.content })
        assertFalse(context.toString().contains("example.b"))
        assertFalse(context.toString().contains("://"))
    }

    @Test fun initialIndexIsBoundedAndDoesNotTreatUnlistedRoutesAsInstalled() {
        val catalog = (1..60).map { route("$it", "example.app$it") }
        val context = AndroidCapabilityDiscovery.projectEntryContext(catalog,
            catalog.map { it.packageName }.toSet(), 1024)
        assertTrue(context.length <= 1024)
        assertTrue(Json.parseToJsonElement(context).jsonObject.getValue("index_has_more").jsonPrimitive.boolean)
        val empty = Json.parseToJsonElement(AndroidCapabilityDiscovery.projectEntryContext(
            catalog, emptySet(), 1024)).jsonObject
        assertTrue(empty.getValue("app_index").jsonArray.isEmpty())
    }

    @Test fun knownTargetDoesNotCarryOtherAppsAndUnknownTargetDoesNotExpandTheCatalog() {
        val catalog = listOf(route("a", "example.a"), route("b", "example.b"))
        val selected = Json.parseToJsonElement(AndroidCapabilityDiscovery.projectContext(
            catalog, emptyMap(), "example.a", null, 8000)).jsonObject
        assertEquals("target_app", selected["index_scope"]?.jsonPrimitive?.content)
        assertEquals(1, selected["app_index"]?.jsonArray?.size)
        assertEquals("example.a", selected["target_routes"]?.jsonArray?.single()?.jsonObject
            ?.get("package_name")?.jsonPrimitive?.content)
        val missing = Json.parseToJsonElement(AndroidCapabilityDiscovery.projectContext(
            catalog, emptyMap(), "example.missing", null, 8000)).jsonObject
        assertTrue(missing.getValue("app_index").jsonArray.isEmpty())
        assertTrue(missing.getValue("target_routes").jsonArray.isEmpty())
    }

    @Test fun routeFailureRetainsItsBackendAndAppScopeWithoutClaimingCurrentAvailability() {
        val receipt = DeepLinkKnowledgeStore.DeepLinkVerificationEvent("fixture", "a", "A", "search",
            "example.a", "example.a://search?q=fixture", false, "launch_failed",
            appLastUpdateTime = 42, backendSessionId = "session-1", failureKind = "TARGET_NOT_EXPORTED")
        val context = Json.parseToJsonElement(AndroidCapabilityDiscovery.projectContext(
            listOf(route("a", "example.a")), mapOf("a" to receipt), "example.a", null, 8000)).jsonObject
        val last = context.getValue("target_routes").jsonArray.single().jsonObject.getValue("last_launch").jsonObject
        assertEquals("session-1", last["backend_session_id"]?.jsonPrimitive?.content)
        assertEquals(42L, last["app_last_update_time"]?.jsonPrimitive?.long)
        assertEquals("TARGET_NOT_EXPORTED", last["failure_kind"]?.jsonPrimitive?.content)
        assertTrue(last.getValue("historical_receipt_not_current_state").jsonPrimitive.boolean)
    }

    @Test fun disabledCatalogProjectsEmptyRoutes() {
        val selected = Json.parseToJsonElement(AndroidCapabilityDiscovery.projectContext(
            emptyList(), emptyMap(), "example.a", null, 8000)).jsonObject
        assertTrue(selected.getValue("app_index").jsonArray.isEmpty())
        assertTrue(selected.getValue("target_routes").jsonArray.isEmpty())
    }
}
