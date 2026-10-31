// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.config

import com.droidance.core.agent.config.*

import com.droidance.core.device.config.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class AppInventoryPolicy(val retryAfterMs: Long, val missCacheEntries: Int,
    val contextMaxChars: Int, val pageSize: Int) {
    fun validate() {
        require(retryAfterMs > 0 && missCacheEntries in 1..1024)
        require(contextMaxChars in 512..8192 && pageSize in 1..100)
    }
}

/** One immutable device-user snapshot; package presence and launcher visibility are distinct facts. */
@Serializable
data class AppInventorySnapshot(
    val userId: Int,
    val generation: Long,
    val packages: Set<String>,
    val systemPackages: Set<String>,
    val apps: List<AppInfo>,
    val source: String,
    val complete: Boolean,
    val launcherComplete: Boolean,
    val capturedAtMs: Long,
    val lastAttemptAtMs: Long,
    val dirty: Boolean,
    val lastError: String?,
    val collectorUpdatedAtMs: Long
) {
    fun forCollector(updatedAtMs: Long): AppInventorySnapshot = if (collectorUpdatedAtMs == updatedAtMs) this
        else copy(dirty = true, generation = generation + 1, lastError = "COLLECTOR_UPDATED")

    fun merge(attempt: AppInventorySnapshot): AppInventorySnapshot {
        require(userId == attempt.userId)
        if (complete && !attempt.complete) return copy(generation = generation + 1,
            lastAttemptAtMs = attempt.lastAttemptAtMs, dirty = true,
            lastError = attempt.lastError ?: "PARTIAL_PACKAGE_VISIBILITY")
        val known = apps.associateBy { it.packageName }
        val observed = attempt.apps.map { app ->
            val previous = known[app.packageName]
            if (!app.labelVerified && previous?.labelVerified == true) app.copy(label = previous.label)
            else app
        }
        val retained = if (attempt.launcherComplete) emptyList() else apps.filter {
            it.packageName !in observed.map(AppInfo::packageName) &&
                (!attempt.complete || it.packageName in attempt.packages)
        }.map { it.copy(launcherVerified = false) }
        return attempt.copy(generation = generation + 1, apps = (observed + retained).sortedBy { it.label })
    }

    fun withPackageChange(packageName: String, attempt: AppInventorySnapshot?): AppInventorySnapshot {
        if (attempt != null && !attempt.complete) return copy(generation = generation + 1,
            dirty = true, lastError = attempt.lastError ?: "PACKAGE_REFRESH_INCOMPLETE")
        val changedApps = attempt?.apps.orEmpty().filter { it.packageName == packageName }
        return copy(generation = generation + 1,
            packages = (packages - packageName) + attempt?.packages.orEmpty().filter { it == packageName },
            systemPackages = (systemPackages - packageName) + attempt?.systemPackages.orEmpty().filter { it == packageName },
            apps = (apps.filterNot { it.packageName == packageName } + changedApps).sortedBy { it.label },
            dirty = dirty || (attempt != null && !attempt.launcherComplete),
            lastError = attempt?.lastError ?: lastError)
    }

    fun metadata(now: Long): JsonObject = buildJsonObject {
        put("source_kind", "device_application_inventory")
        put("user_id", userId); put("generation", generation); put("source", source)
        put("captured_at_ms", capturedAtMs); put("last_attempt_at_ms", lastAttemptAtMs)
        put("collector_updated_at_ms", collectorUpdatedAtMs)
        put("package_inventory_complete", complete); put("launcher_inventory_complete", launcherComplete)
        put("stale", dirty || capturedAtMs == 0L)
        put("last_refresh_error", lastError?.let(::JsonPrimitive) ?: JsonNull)
        put("package_count", packages.size); put("desktop_app_count", apps.size)
    }

    private fun queryMetadata(now: Long) = JsonObject(metadata(now)
        .filterKeys { it in setOf("user_id", "generation", "source", "captured_at_ms", "stale", "last_refresh_error") })

    private fun selectedApps(query: String, packageName: String) = apps.filter { app ->
        (packageName.isBlank() || app.packageName == packageName) &&
            (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
    }

    fun page(query: String, packageName: String, offset: Int, limit: Int, now: Long): JsonObject {
        require(offset >= 0 && limit > 0)
        val selected = selectedApps(query, packageName)
        val page = selected.drop(offset).take(limit)
        val factJson = Json { encodeDefaults = true }
        return buildJsonObject {
            put("launcher_search", buildJsonObject {
                put("scope", "launcher_label_or_package_search")
                put("query", query); put("package_filter", packageName)
                put("match_count", selected.size)
            })
            put("package_presence", buildJsonObject {
                put("scope", "exact_package_in_scanned_user"); put("user_id", userId)
                put("package_name", packageName)
                put("status", when {
                    packageName.isBlank() -> "unknown_without_exact_package_query"
                    packageName in packages -> "listed"
                    complete && !dirty && capturedAtMs > 0 -> "not_listed_in_scanned_user"
                    else -> "unknown"
                })
            })
            put("inventory", if (query.isBlank() && packageName.isBlank()) metadata(now)
                else queryMetadata(now))
            put("apps", JsonArray(page.map { factJson.encodeToJsonElement(it) }))
            put("next_offset", if (offset + page.size < selected.size) JsonPrimitive(offset + page.size) else JsonNull)
        }
    }

    fun context(maxChars: Int, packageName: String?, query: String?, now: Long): String {
        val explicitSearch = !packageName.isNullOrBlank() || !query.isNullOrBlank()
        val selected = selectedApps(query.orEmpty(), packageName.orEmpty())
            .filter { explicitSearch || !it.isSystemApp }
        val rows = mutableListOf<JsonElement>()
        val queryFacts = page(query.orEmpty(), packageName.orEmpty(), 0, 1, now)
            .filterKeys { it != "apps" && it != "next_offset" && it != "inventory" }
        // This is a bounded capability index, not the complete scan. Keep provenance
        // and freshness here; full scan metadata remains available in the apps receipt.
        val facts = queryFacts + mapOf(
            "launcher_search" to buildJsonObject {
                put("scope", if (explicitSearch) "launcher_label_or_package_search" else "third_party_launcher_index")
                put("query", query.orEmpty()); put("package_filter", packageName.orEmpty())
                put("system_apps_included", explicitSearch); put("match_count", selected.size)
            },
            "inventory" to queryMetadata(now),
            "index_columns" to buildJsonArray { add("name"); add("package"); add("launcher_verified"); add("label_verified") }
        )
        if (JsonObject(facts + mapOf("apps" to JsonArray(emptyList()),
                "has_more" to JsonPrimitive(selected.isNotEmpty()))).toString().length > maxChars) {
            return "{\"source_kind\":\"device_application_inventory\",\"context_omitted\":true}"
        }
        for (app in selected.sortedBy { it.packageName }) {
            val row = buildJsonArray {
                add(app.label); add(app.packageName); add(app.launcherVerified); add(app.labelVerified)
            }
            if (JsonObject(facts + mapOf("apps" to JsonArray(rows + listOf(row)))).toString().length + 128 > maxChars) break
            rows.add(row)
        }
        return JsonObject(facts + mapOf("apps" to JsonArray(rows),
            "has_more" to JsonPrimitive(rows.size < selected.size))).toString()
    }
}
