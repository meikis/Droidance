// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class DeepLinkKnowledgeStoreTest {
    @Test fun technicalRouteRejectionsExpireWhenTheAppOrBackendChanges() {
        val policy = DeepLinkKnowledgeStore.RouteFailurePolicy(setOf("TARGET_NOT_EXPORTED"))
        val receipt = DeepLinkKnowledgeStore.DeepLinkVerificationEvent("fixture", "route", "Fixture", "search",
            "example.app", "example://search", false, "launch_failed", appLastUpdateTime = 42,
            backendSessionId = "session-1", failureKind = "TARGET_NOT_EXPORTED")
        assertTrue(DeepLinkKnowledgeStore.unavailableForInstallation(receipt, 42, "session-1", policy))
        assertFalse(DeepLinkKnowledgeStore.unavailableForInstallation(receipt, 43, "session-1", policy))
        assertFalse(DeepLinkKnowledgeStore.unavailableForInstallation(receipt, 42, "session-2", policy))
        assertFalse(DeepLinkKnowledgeStore.unavailableForInstallation(receipt, null, "session-1", policy))
        assertFalse(DeepLinkKnowledgeStore.unavailableForInstallation(receipt, 42, null, policy))
        assertFalse(DeepLinkKnowledgeStore.unavailableForInstallation(receipt.copy(failureKind = "NETWORK_TIMEOUT"), 42, "session-1", policy))
        assertFalse(DeepLinkKnowledgeStore.unavailableForInstallation(receipt.copy(success = true), 42, "session-1", policy))
    }

    @Test fun requiredParametersComeFromTheTemplateWithoutInventingFilters() {
        assertEquals(setOf("destination", "keyword"), DeepLinkKnowledgeStore.requiredParameters(
            "example://search?q={keyword}&target={destination}&again={keyword}"))
    }
    @Test
    fun storeKeepsStructuredCandidateEvidenceWithoutToolSpecificFormatting() {
        val source = projectFile("app/src/main/java/com/droidance/config/DeepLinkKnowledgeStore.kt").readText()

        assertTrue(source.contains("data class DeepLinkCandidate"))
        assertTrue(source.contains("lastFailureReason"))
        assertTrue(source.contains("recentFailureByRouteId"))
        assertFalse(source.contains("formatCandidateList"))
        assertFalse(source.contains("launch_deeplink"))
    }

    @Test
    fun naturalLanguageActionCanResolveAContainedCatalogCapability() {
        val source = projectFile("app/src/main/java/com/droidance/config/DeepLinkKnowledgeStore.kt").readText()

        assertTrue(source.contains("internal fun routeActionMatches("))
        assertTrue(source.contains("requested.contains(normalizedRouteAction)"))
        assertTrue(source.contains("routeActionMatches("))
    }

    @Test
    fun configured_route_alias_matches_the_models_canonical_action() {
        assertTrue(
            DeepLinkKnowledgeStore.routeActionMatches(
                routeAction = "搜索",
                routeAliases = listOf("search"),
                requestedAction = "search",
                rawRequestedAction = "search"
            )
        )
        assertTrue(
            DeepLinkKnowledgeStore.routeActionMatches(
                routeAction = "外卖",
                routeAliases = listOf("food", "takeout", "delivery", "waimai"),
                requestedAction = "food",
                rawRequestedAction = "food"
            )
        )
        assertFalse(
            DeepLinkKnowledgeStore.routeActionMatches(
                routeAction = "搜索",
                routeAliases = listOf("search"),
                requestedAction = "research",
                rawRequestedAction = "research"
            )
        )
        assertTrue(
            DeepLinkKnowledgeStore.routeActionMatches(
                routeAction = "搜索",
                routeAliases = listOf("search"),
                requestedAction = "search_items",
                rawRequestedAction = "search_items"
            )
        )
        val firstRoute = Json.decodeFromString<DeepLinkKnowledgeStore.DeepLinkRoute>(
            projectFile("app/src/main/assets/deeplinks/routes.jsonl")
                .useLines { lines -> lines.first() }
        )
        assertTrue(firstRoute.actionAliases.isNotEmpty())
    }

    @Test
    fun onlySemanticallyVerifiedCandidatesAreAutomaticallyRouted() {
        assertFalse(
            DeepLinkKnowledgeStore.isEligibleForAutomaticResolution(
                sourceType = "candidate",
                successCount = 0,
                failureCount = 0
            )
        )
        assertFalse(
            DeepLinkKnowledgeStore.isEligibleForAutomaticResolution(
                sourceType = "candidate",
                successCount = 0,
                failureCount = 1
            )
        )
        assertFalse(
            DeepLinkKnowledgeStore.isEligibleForAutomaticResolution(
                sourceType = "candidate",
                successCount = 1,
                failureCount = 1
            )
        )
        assertTrue(
            DeepLinkKnowledgeStore.isEligibleForAutomaticResolution(
                sourceType = "candidate",
                successCount = 2,
                failureCount = 1
            )
        )
        assertTrue(
            DeepLinkKnowledgeStore.isEligibleForAutomaticResolution(
                sourceType = "official",
                successCount = 0,
                failureCount = 3
            )
        )
    }

    @Test
    fun semanticVerificationUpgradeResetsOnlyCandidateRouteStats() {
        val candidate = DeepLinkKnowledgeStore.DeepLinkRoute(
            id = "candidate",
            category = "test",
            appName = "Candidate",
            packageName = "example.candidate",
            action = "open",
            uriTemplate = "candidate://open",
            sourceType = "candidate"
        )
        val official = candidate.copy(
            id = "official",
            sourceType = "official"
        )
        val manual = candidate.copy(
            id = "manual",
            sourceType = "manual"
        )

        assertTrue(DeepLinkKnowledgeStore.candidateRouteIds(listOf(candidate, official, manual)) == setOf("candidate"))
    }

    @Test
    fun explicit_package_name_takes_precedence_over_a_display_name_mismatch() {
        assertTrue(
            DeepLinkKnowledgeStore.routeAppNameMatches(
                routeAppName = "Target App",
                requestedAppName = "localized display name",
                packageName = "com.example.target"
            )
        )
        assertFalse(
            DeepLinkKnowledgeStore.routeAppNameMatches(
                routeAppName = "Target App",
                requestedAppName = "localized display name",
                packageName = null
            )
        )
    }

    @Test
    fun configured_app_alias_matches_a_route_without_an_installed_label_match() {
        assertTrue(
            DeepLinkKnowledgeStore.routeAppNameMatches(
                routeAppName = "京东",
                requestedAppName = "JD",
                packageName = null,
                routeAppAliases = listOf("JD", "Jingdong")
            )
        )
        val jdSearch = Json.decodeFromString<DeepLinkKnowledgeStore.DeepLinkRoute>(
            projectFile("app/src/main/assets/deeplinks/routes.jsonl")
                .useLines { lines -> lines.first { it.contains("com.jingdong.app.mall:搜索:2-v3") } }
        )
        assertTrue(jdSearch.appAliases.containsAll(listOf("JD", "Jingdong")))
        val idlefishSearch = Json.decodeFromString<DeepLinkKnowledgeStore.DeepLinkRoute>(
            projectFile("app/src/main/assets/deeplinks/routes.jsonl")
                .useLines { lines -> lines.first { it.contains("com.taobao.idlefish:搜索:21-v2") } }
        )
        assertTrue(idlefishSearch.appAliases.containsAll(listOf("Idlefish", "Xianyu", "Goofish")))
        assertTrue(idlefishSearch.uriTemplate.contains("fleamarket://search_items?keyword={keyword}"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
