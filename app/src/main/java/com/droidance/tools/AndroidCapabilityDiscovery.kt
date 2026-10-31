// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.config.DeepLinkKnowledgeStore
import com.droidance.core.device.config.InstalledAppsManager
import com.droidance.config.RuleConfigManager
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.settings.ExecutionSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*

/** Reports available methods without launching an app or declaring a task result. */
@Singleton
class AndroidCapabilityDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedApps: InstalledAppsManager,
    private val routes: DeepLinkKnowledgeStore,
    private val executionContextStore: ExecutionContextStore,
    private val rules: RuleConfigManager,
    private val executionSettingsRepository: ExecutionSettingsRepository
) {
    /** Local knowledge only: no package scan, device activation, task inference or launch. */
    suspend fun context(packageName: String?, appName: String?, inventoryPackageName: String?,
                        inventoryQuery: String?, maxChars: Int): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val catalog = if (executionSettingsRepository.isDeepLinkEnabled()) routes.allRoutes() else emptyList()
            installedApps.getAllAwait()
            val targetedRoutes = !packageName.isNullOrBlank() || !appName.isNullOrBlank()
            val inventory = installedApps.modelContext(inventoryPackageName, inventoryQuery,
                if (targetedRoutes) maxOf(1024, maxChars / 3) else maxChars / 2)
            val remaining = maxChars - inventory.length - 64
            val unavailable = routes.unavailableRouteIds(executionContextStore.current()?.backendSessionId)
            val usableCatalog = catalog.filterNot { it.id in unavailable }
            val methods = if (remaining < 1024) "" else if (targetedRoutes) projectContext(
                usableCatalog, routes.latestLaunchResults(), packageName, appName, remaining)
            else projectEntryContext(usableCatalog, installedApps.currentSnapshot().packages, remaining)
            buildJsonObject {
                put("deeplink_enabled", executionSettingsRepository.isDeepLinkEnabled())
                put("device_inventory", Json.parseToJsonElement(inventory))
                if (methods.isNotBlank()) put("route_catalog", Json.parseToJsonElement(methods))
            }.toString()
        }

    companion object {
        /** A bounded installed-app action index is available before the first device call. */
        internal fun projectEntryContext(
            catalog: List<DeepLinkKnowledgeStore.DeepLinkRoute>, installedPackages: Set<String>, maxChars: Int
        ): String {
            val rows = catalog.filter { it.packageName in installedPackages }.groupBy { it.packageName }
                .toSortedMap().map { (pkg, entries) -> buildJsonArray {
                    add(entries.first().appName); add(pkg)
                    add(buildJsonArray { entries.sortedBy { it.id }.distinctBy { it.action }.forEach { route ->
                        add(buildJsonArray { add(route.action); add(buildJsonArray {
                            DeepLinkKnowledgeStore.requiredParameters(route.uriTemplate).forEach { add(it) }
                        }) })
                    } })
                } }
            fun payload(page: List<JsonElement>) = buildJsonObject {
                put("source_kind", "local_capability_catalog_not_device_state")
                put("index_scope", "installed_apps")
                put("index_columns", buildJsonArray { add("app"); add("package"); add("actions_with_required_params") })
                put("app_index", JsonArray(page)); put("app_count", rows.size)
                put("index_has_more", page.size < rows.size)
            }.toString()
            val page = mutableListOf<JsonElement>()
            for (row in rows) {
                if (payload(page + row).length > maxChars) break
                page.add(row)
            }
            return payload(page)
        }

        internal fun projectContext(
            catalog: List<DeepLinkKnowledgeStore.DeepLinkRoute>,
            launchResults: Map<String, DeepLinkKnowledgeStore.DeepLinkVerificationEvent>,
            packageName: String?, appName: String?, maxChars: Int
        ): String {
            require(maxChars >= 1024)
            if (packageName.isNullOrBlank() && appName.isNullOrBlank()) return ""
            val selected = catalog.sortedBy { it.id }.filter { route ->
                if (!packageName.isNullOrBlank()) route.packageName == packageName
                else !appName.isNullOrBlank() && (listOf(route.appName) + route.appAliases)
                    .any { it.equals(appName.trim(), ignoreCase = true) }
            }
            val index = selected.groupBy { it.packageName }.toSortedMap().map { (pkg, entries) ->
                buildJsonArray {
                    add(entries.first().appName); add(pkg)
                    add(buildJsonArray { entries.map { it.action }.distinct().sorted().forEach { add(it) } })
                }
            }
            val candidates = selected.map { route -> buildJsonObject {
                put("package_name", route.packageName)
                put("route_action", route.action)
                put("uri_template", route.uriTemplate)
                put("required_params", buildJsonArray {
                    DeepLinkKnowledgeStore.requiredParameters(route.uriTemplate).forEach { add(it) }
                })
                put("source_type", route.sourceType)
                put("source_url", route.sourceUrl)
                put("success_count", route.successCount)
                put("failure_count", route.failureCount)
                put("last_tested_at", route.lastTestedAt)
                launchResults[route.id]?.let { receipt -> put("last_launch", buildJsonObject {
                    put("result", receipt.resultLabel)
                    put("failure_kind", receipt.failureKind?.let(::JsonPrimitive) ?: JsonNull)
                    put("app_last_update_time", receipt.appLastUpdateTime?.let(::JsonPrimitive) ?: JsonNull)
                    put("backend_session_id", receipt.backendSessionId?.let(::JsonPrimitive) ?: JsonNull)
                    put("timestamp", receipt.timestamp)
                    put("historical_receipt_not_current_state", true)
                }) }
            } }
            fun bounded(entries: List<JsonElement>, budget: Int): List<JsonElement> {
                var used = 2
                return entries.takeWhile { entry ->
                    used += entry.toString().length + 1
                    used <= budget
                }
            }
            val selectedPage = bounded(candidates, if (selected.isEmpty()) 0 else maxChars / 2)
            val indexPage = bounded(index, maxChars - selectedPage.sumOf { it.toString().length + 1 } - 768)
            return buildJsonObject {
                put("source_kind", "local_capability_catalog_not_device_state")
                put("index_scope", "target_app")
                put("target_package", packageName)
                put("target_app", appName)
                put("index_columns", buildJsonArray { add("app"); add("package"); add("actions") })
                put("app_index", JsonArray(indexPage))
                put("app_count", index.size)
                put("index_has_more", indexPage.size < index.size)
                put("target_routes", JsonArray(selectedPage))
                put("target_route_count", candidates.size)
                put("target_has_more", selectedPage.size < candidates.size)
            }.toString()
        }
    }

    suspend fun read(arguments: String): ToolExecutionResult {
        val input = Json.parseToJsonElement(arguments).jsonObject
        val packageName = input["package_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val query = input["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val action = input["intent_action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val uri = input["uri"]?.jsonPrimitive?.contentOrNull
        val options = ExplicitIntentOptions.parse(input)
        val offset = input["offset"]?.jsonPrimitive?.intOrNull ?: 0
        if (offset < 0) return ToolProtocolResult.invalidArgument("offset", "negative")
        val execution = executionContextStore.current()
        val packages = installedApps.packagesAwait(packageName.takeIf(String::isNotBlank))
        val unavailable = routes.unavailableRouteIds(execution?.backendSessionId)
        val catalog = if (executionSettingsRepository.isDeepLinkEnabled()) routes.allRoutes()
            .filterNot { it.id in unavailable } else emptyList()
        // An explicit package query must not lose known routes to an incomplete package cache.
        // Catalog membership establishes a candidate, never installation or launch success.
        val terms = query.lowercase().split(Regex("[\\s\\p{Punct}]+"))
            .filter(String::isNotBlank).distinct()
        val candidates = catalog.asSequence().filter {
            if (packageName.isBlank()) query.isNotBlank() || it.packageName in packages
            else it.packageName == packageName
        }.map { route ->
            val fields = listOf(route.appName, route.action, route.packageName, route.id) +
                route.actionAliases + route.appAliases
            val score = terms.fold(0) { total, term ->
                total + fields.maxOf { value ->
                    when {
                        value.equals(term, ignoreCase = true) -> 3
                        value.contains(term, ignoreCase = true) -> 1
                        else -> 0
                    }
                }
            }
            route to score
        }.filter { terms.isEmpty() || it.second > 0 }
            .sortedBy { it.first.id }.sortedByDescending { it.second }
            .map { it.first }.toList()
        val page = candidates.drop(offset).take(20)
        val effectiveAction = action.takeIf(String::isNotBlank) ?: uri?.let { Intent.ACTION_VIEW }
        val resolved = effectiveAction?.let {
            context.packageManager.resolveActivity(Intent(it).apply {
                val dataUri = uri?.let(android.net.Uri::parse)
                when {
                    dataUri != null && options.mimeType != null -> setDataAndType(dataUri, options.mimeType)
                    dataUri != null -> data = dataUri
                    options.mimeType != null -> type = options.mimeType
                }
                options.categories.forEach(::addCategory)
                if (packageName.isNotBlank()) setPackage(packageName)
            }, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        }
        return ToolProtocolResult.success(buildJsonObject {
            put("source_kind", "android_capability_catalog")
            put("captured_at_ms", System.currentTimeMillis())
            put("scope", "device")
            put("backend_session_id", execution?.backendSessionId)
            put("execution_space", execution?.space?.name)
            put("display_id", execution?.displayId?.let(::JsonPrimitive) ?: JsonNull)
            put("shell_tool_available", JsonPrimitive(true))
            put("shell_backend_bound", JsonPrimitive(execution != null))
            put("shell_scope", "device")
            put("deeplink_enabled", executionSettingsRepository.isDeepLinkEnabled())
            put("catalog_route_count", catalog.size)
            put("package_inventory_count", packages.size)
            put("package_inventory_verified", installedApps.hasVerifiedPackageInventory())
            if (packageName.isNotBlank()) put("package_inventory_status",
                if (packageName in packages) "listed" else "not_listed_installation_unknown")
            put("platform_methods", rules.readMarkdownSection("device_tools.md", "platform_methods"))
            put("route_count", candidates.size)
            put("next_offset", if (offset.toLong() + page.size < candidates.size) JsonPrimitive(offset + page.size) else JsonNull)
            put("routes", JsonArray(page.map { route -> buildJsonObject {
                put("id", route.id)
                put("package_name", route.packageName)
                put("app_name", route.appName)
                put("package_listed", route.packageName in packages)
                put("route_action", route.action)
                put("uri_template", route.uriTemplate)
                put("required_params", buildJsonArray {
                    DeepLinkKnowledgeStore.requiredParameters(route.uriTemplate).forEach { add(it) }
                })
                put("source_type", route.sourceType)
                put("source_url", route.sourceUrl)
                put("success_count", route.successCount)
                put("failure_count", route.failureCount)
                put("last_tested_at", route.lastTestedAt)
            } }))
            if (effectiveAction != null) put("intent_resolution", buildJsonObject {
                put("intent_action", effectiveAction)
                put("uri", uri?.let(::JsonPrimitive) ?: JsonNull)
                put("mime_type", options.mimeType?.let(::JsonPrimitive) ?: JsonNull)
                put("resolved", resolved != null)
                put("package_name", resolved?.packageName?.let(::JsonPrimitive) ?: JsonNull)
                put("activity", resolved?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("exported", resolved?.exported?.let(::JsonPrimitive) ?: JsonNull)
            })
        }.toString())
    }
}
