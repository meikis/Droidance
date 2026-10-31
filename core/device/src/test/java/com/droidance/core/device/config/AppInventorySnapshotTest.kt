// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.config

import com.droidance.core.agent.config.*

import com.droidance.core.device.config.*

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppInventorySnapshotTest {
    @Test fun packageEventsUpdateOnlyTheAffectedEntryAndRemovalDoesNotWaitForAFullScan() {
        val reader = AppInfo("sample.reader", "Reader", launchTarget = "sample.reader/.Main")
        val other = AppInfo("sample.other", "Other", launchTarget = "sample.other/.Home")
        val before = snapshot(apps = listOf(reader, other))
        val updated = before.withPackageChange("sample.reader", snapshot(apps = listOf(
            reader.copy(label = "Updated Reader", launchTarget = "sample.reader/.NewMain"))))
        assertEquals(other, updated.apps.first { it.packageName == other.packageName })
        assertEquals("sample.reader/.NewMain", updated.apps.first { it.packageName == reader.packageName }.launchTarget)
        assertEquals(before.capturedAtMs, updated.capturedAtMs)
        assertTrue(updated.complete)
        val removed = updated.withPackageChange("sample.reader", null)
        assertFalse("sample.reader" in removed.packages)
        assertEquals(listOf(other), removed.apps)
        assertTrue(removed.complete)
        val restricted = before.withPackageChange("sample.reader", snapshot(false, emptyList()))
        assertEquals(before.apps, restricted.apps)
        assertTrue(restricted.dirty)
    }
    @Test fun nativeSingleSegmentPackageIdentifiersRemainInTheCompletePresenceSet() {
        assertEquals(setOf("android", "oplus", "sample.reader"), parseInstalledPackageListing(
            "package:android\npackage:oplus\npackage:sample.reader\nservice:unrelated\npackage:invalid/path\n"))
    }
    private fun snapshot(complete: Boolean = true, apps: List<AppInfo> = listOf(
        AppInfo("sample.reader", "Reader", launchTarget = "sample.reader/.Main"))) =
        AppInventorySnapshot(0, 1, apps.map { it.packageName }.toSet() + "system.service", setOf("system.service"),
            apps, if (complete) "authorized_device_shell" else "package_manager_partial", complete, complete,
            1000, 1000, !complete, if (complete) null else "PARTIAL", 900)

    @Test fun collectorUpgradeInvalidatesEvenARecentCompleteCacheWithoutErasingKnownFacts() {
        val original = snapshot()
        assertSame(original, original.forCollector(900))
        val upgraded = original.forCollector(1900)
        assertTrue(upgraded.dirty)
        assertEquals(original.apps, upgraded.apps)
        assertEquals(original.capturedAtMs, upgraded.capturedAtMs)
        assertEquals("COLLECTOR_UPDATED", upgraded.lastError)
        val refreshed = upgraded.merge(snapshot().copy(collectorUpdatedAtMs = 1900, capturedAtMs = 2000))
        assertFalse(refreshed.dirty)
        assertEquals(1900, refreshed.collectorUpdatedAtMs)
    }

    @Test fun aRestrictedRefreshCannotEraseKnownAppsOrPretendTheOldSnapshotIsFresh() {
        val original = snapshot()
        val restricted = snapshot(false, emptyList()).copy(capturedAtMs = 2000, lastAttemptAtMs = 2000)
        val kept = original.merge(restricted)
        assertEquals(original.apps, kept.apps)
        assertEquals(original.packages, kept.packages)
        assertEquals(1000, kept.capturedAtMs)
        assertEquals(2000, kept.lastAttemptAtMs)
        assertTrue(kept.dirty)
        assertTrue(kept.metadata(2100).getValue("stale").jsonPrimitive.boolean)
    }

    @Test fun aCompleteRescanReflectsUninstallationWithoutRetainingDeletedLauncherEntries() {
        val updated = snapshot().merge(snapshot(true, emptyList()).copy(capturedAtMs = 2000))
        assertTrue(updated.apps.isEmpty())
        assertFalse("sample.reader" in updated.packages)
        assertFalse(updated.dirty)
    }

    @Test fun incompleteLauncherVisibilityRetainsKnownEntriesAsUnverifiedWithinKnownPackages() {
        val updated = snapshot().merge(snapshot().copy(apps = emptyList(), launcherComplete = false, dirty = true))
        assertEquals(1, updated.apps.size)
        assertFalse(updated.apps.single().launcherVerified)
    }

    @Test fun servicePackagesAreNotModelIndexRowsAndPaginationRemainsExplicit() {
        val apps = (1..30).map { AppInfo("sample.app$it", "App $it", launchTarget = "sample.app$it/.Main") }
        val snapshot = snapshot(apps = apps)
        val first = snapshot.page("", "", 0, 20, 1001)
        assertEquals(20, first.getValue("apps").jsonArray.size)
        assertEquals(20, first.getValue("next_offset").jsonPrimitive.int)
        val second = snapshot.page("", "", 20, 20, 1001)
        assertEquals(10, second.getValue("apps").jsonArray.size)
        assertEquals(JsonNull, second["next_offset"])
        val context = snapshot.context(1024, null, null, 1001)
        assertTrue(context.length <= 1024)
        assertFalse(context.contains("system.service"))
        assertTrue(Json.parseToJsonElement(context).jsonObject.getValue("has_more").jsonPrimitive.boolean)
    }

    @Test fun aMissingPackageIsUnknownWhenTheSnapshotIsStaleOrRestricted() {
        val nameOnly = snapshot().page("missing label", "", 0, 20, 1001)
        assertEquals("unknown_without_exact_package_query", nameOnly.getValue("package_presence").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("launcher_label_or_package_search", nameOnly.getValue("launcher_search").jsonObject.getValue("scope").jsonPrimitive.content)
        assertEquals(0, nameOnly.getValue("launcher_search").jsonObject.getValue("match_count").jsonPrimitive.int)
        assertFalse(nameOnly.getValue("inventory").jsonObject.containsKey("package_inventory_complete"))
        assertFalse(nameOnly.getValue("inventory").jsonObject.containsKey("package_count"))
        assertTrue(snapshot().page("", "", 0, 20, 1001)
            .getValue("inventory").jsonObject.getValue("package_inventory_complete").jsonPrimitive.boolean)
        assertFalse(nameOnly.containsKey("package_status"))
        for (value in listOf(snapshot(false), snapshot().copy(dirty = true))) {
            assertEquals("unknown", value.page("", "sample.absent", 0, 20, 1001)
                .getValue("package_presence").jsonObject.getValue("status").jsonPrimitive.content)
        }
        assertEquals("not_listed_in_scanned_user", snapshot().page("", "sample.absent", 0, 20, 1001)
            .getValue("package_presence").jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test fun boundedContextKeepsQueryPresenceAndScanFreshnessInTheirOwnScopes() {
        val nameOnly = Json.parseToJsonElement(snapshot().context(1024, null, "missing label", 1001)).jsonObject
        assertEquals(0, nameOnly.getValue("launcher_search").jsonObject.getValue("match_count").jsonPrimitive.int)
        assertEquals("unknown_without_exact_package_query", nameOnly.getValue("package_presence").jsonObject.getValue("status").jsonPrimitive.content)
        assertFalse(nameOnly.containsKey("package_inventory_complete"))
        assertFalse(nameOnly.getValue("inventory").jsonObject.containsKey("package_inventory_complete"))
        assertFalse(nameOnly.getValue("inventory").jsonObject.getValue("stale").jsonPrimitive.boolean)
        val exact = Json.parseToJsonElement(snapshot().context(1024, "system.service", null, 1001)).jsonObject
        assertEquals("listed", exact.getValue("package_presence").jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(exact.getValue("apps").jsonArray.isEmpty())
        val intersected = Json.parseToJsonElement(snapshot().context(
            1024, "sample.reader", "missing label", 1001)).jsonObject
        assertEquals(0, intersected.getValue("launcher_search").jsonObject.getValue("match_count").jsonPrimitive.int)
        assertTrue(intersected.getValue("apps").jsonArray.isEmpty())
        assertFalse(intersected.getValue("has_more").jsonPrimitive.boolean)
        assertEquals("listed", intersected.getValue("package_presence").jsonObject.getValue("status").jsonPrimitive.content)
        val matched = Json.parseToJsonElement(snapshot().context(
            1024, "sample.reader", "read", 1001)).jsonObject
        assertEquals(1, matched.getValue("launcher_search").jsonObject.getValue("match_count").jsonPrimitive.int)
        assertEquals("sample.reader", matched.getValue("apps").jsonArray.single().jsonArray[1].jsonPrimitive.content)
        val bounded = snapshot().context(1024, null, "long query ".repeat(300), 1001)
        assertTrue(bounded.length <= 1024)
        assertTrue(Json.parseToJsonElement(bounded).jsonObject.getValue("context_omitted").jsonPrimitive.boolean)
    }

    @Test fun taskEntryIndexIncludesAllFittingThirdPartyLaunchersAndKeepsAStableOrder() {
        val apps = (1..60).map { AppInfo("sample.app$it", "App $it", launchTarget = "sample.app$it/.Main") } +
            AppInfo("system.launcher", "System", isSystemApp = true)
        val value = snapshot(apps = apps)
        val context = value.context(6144, null, null, 1001)
        val parsed = Json.parseToJsonElement(context).jsonObject
        val packages = parsed.getValue("apps").jsonArray.map { it.jsonArray[1].jsonPrimitive.content }
        assertEquals(60, packages.size)
        assertEquals(packages.sorted(), packages)
        assertFalse(parsed.getValue("has_more").jsonPrimitive.boolean)
        assertFalse("system.launcher" in packages)
        assertEquals(context, value.copy(apps = apps.reversed()).context(6144, null, null, 1002))
        val explicit = Json.parseToJsonElement(value.context(6144, "system.launcher", null, 1001)).jsonObject
        assertEquals("system.launcher", explicit.getValue("apps").jsonArray.single().jsonArray[1].jsonPrimitive.content)
    }

    @Test fun ambiguousLabelsReturnCandidatesInsteadOfAnArbitraryPackage() {
        val apps = listOf(AppInfo("sample.first", "Notes"), AppInfo("sample.second", "Notes"))
        assertNull(AppNameMatcher.findBestPackage("Notes", apps))
        assertEquals(2, AppNameMatcher.findCandidates("Notes", apps).size)
        assertEquals("sample.first", AppNameMatcher.findBestPackage("sample.first", apps))
    }

    @Test fun appPagesExposeVerifiedFlagsAndUnknownFieldsExplicitly() {
        val row = snapshot().page("", "", 0, 20, 1001).getValue("apps").jsonArray.single().jsonObject
        assertTrue(row.getValue("labelVerified").jsonPrimitive.boolean)
        assertTrue(row.getValue("launcherVerified").jsonPrimitive.boolean)
        assertEquals(JsonNull, row.getValue("lastUpdateTime"))
        assertEquals(JsonNull, row.getValue("enabled"))
    }
}
