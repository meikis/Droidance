// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import android.content.Context
import com.droidance.memory.MarkdownFileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deep Link 知识库。
 *
 * 内置资产放在 assets/deeplinks，启动后导入 droidance-data/deeplinks。
 * Agent 只拿第三方 App URI 目录索引，具体 URI 由 android_act(open) 按需解析。
 * Android 平台 Intent 不属于 Deep Link，由 android_act(open, intent_action=...) 的 native 路径执行。
 */
@Singleton
class DeepLinkKnowledgeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileManager: MarkdownFileManager
) {
    companion object {
        private const val BUILTIN_VERSION = "2026-10-20-02"
        private const val ASSET_DIR = "deeplinks"
        private const val LOCAL_DIR = "deeplinks"
        private const val BUILTIN_ROUTES = "$LOCAL_DIR/builtin/ROUTES.jsonl"
        private const val LEARNED_ROUTES = "$LOCAL_DIR/learned/ROUTES.jsonl"
        private const val LEARNED_STATS = "$LOCAL_DIR/learned/ROUTE_STATS.jsonl"
        private const val MANUAL_ROUTES = "$LOCAL_DIR/manual/ROUTES.jsonl"
        private const val INDEX_FILE = "$LOCAL_DIR/INDEX.md"
        private const val VERSION_FILE = "$LOCAL_DIR/BUILTIN_VERSION"
        private const val CANDIDATE_STATS_POLICY_FILE = "$LOCAL_DIR/CANDIDATE_STATS_POLICY_VERSION"
        private const val CANDIDATE_STATS_POLICY_VERSION = "semantic-verification-2026-08-30-01"
        private const val TEST_LOG_FILE = "$LOCAL_DIR/TEST_LOG.jsonl"


        internal fun requiredParameters(template: String): Set<String> =
            Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""").findAll(template).map { it.groupValues[1] }.toSortedSet()

        internal fun unavailableForInstallation(
            receipt: DeepLinkVerificationEvent, appLastUpdateTime: Long?, backendSessionId: String?,
            policy: RouteFailurePolicy
        ): Boolean = !receipt.success && receipt.failureKind in policy.unavailableFailureKinds &&
            appLastUpdateTime != null && receipt.appLastUpdateTime == appLastUpdateTime &&
            backendSessionId != null && receipt.backendSessionId == backendSessionId

        internal fun shouldUpdateRouteStatsForResult(resultLabel: String?, success: Boolean): Boolean {
            val label = resultLabel ?: if (success) "success" else "failure"
            return label != "launched_unverified" && label != "launched_pending"
        }

        /**
         * Publicly documented routes remain available despite transient failures. Candidate
         * routes become automatic only after their semantic verification history is positive;
         * unknown candidates remain discoverable to the Agent but do not force route-first.
         */
        internal fun isEligibleForAutomaticResolution(
            sourceType: String,
            successCount: Int,
            failureCount: Int
        ): Boolean = sourceType == "official" || failureCount < successCount

        internal fun candidateRouteIds(routes: List<DeepLinkRoute>): Set<String> = routes
            .filter { route ->
                (route.sourceType == "candidate" ||
                    route.sourceType == "web") &&
                    !route.uriTemplate.startsWith("android.settings.")
            }
            .map { it.id }
            .toSet()

        internal fun routeActionMatches(
            routeAction: String,
            routeAliases: List<String>,
            requestedAction: String?,
            rawRequestedAction: String?
        ): Boolean {
            val requested = requestedAction?.trim()?.lowercase().orEmpty()
            if (requested.isBlank()) return true
            val normalizedRouteAction = routeAction.trim().lowercase()
            val normalizedAliases = routeAliases
                .map { it.trim().lowercase() }
                .filter(String::isNotBlank)
            val requestedTokens = actionTokens(requested)
            val routeMatches = normalizedRouteAction.isNotBlank() && (
                normalizedRouteAction == requested ||
                normalizedRouteAction.contains(requested) ||
                requested.contains(normalizedRouteAction) ||
                rawRequestedAction?.contains(normalizedRouteAction, ignoreCase = true) == true
            )
            // Models often express a catalog capability as a compound protocol name such as
            // `search_items`, while the catalog keeps the canonical capability `search`. Match
            // complete action tokens only; this deliberately keeps `research` from matching
            // `search` through an unsafe substring.
            val aliasTokenMatches = normalizedAliases.any { alias ->
                actionTokens(alias).any { it in requestedTokens }
            }
            return routeMatches ||
                requested in normalizedAliases ||
                aliasTokenMatches ||
                rawRequestedAction?.let { raw ->
                    normalizedAliases.any { alias -> raw.equals(alias, ignoreCase = true) }
                } == true
        }

        private fun actionTokens(action: String): Set<String> = action
            .trim()
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter(String::isNotBlank)
            .toSet()

        internal fun routeAppNameMatches(
            routeAppName: String,
            requestedAppName: String?,
            packageName: String?,
            routeAppAliases: List<String> = emptyList()
        ): Boolean {
            val requested = requestedAppName?.trim()?.lowercase().orEmpty()
            if (requested.isBlank() || !packageName.isNullOrBlank()) return true
            return (listOf(routeAppName) + routeAppAliases)
                .map { it.trim().lowercase() }
                .filter(String::isNotBlank)
                .any { name ->
                    name.contains(requested) ||
                        (requested.contains(name) && name.length >= MIN_APP_ALIAS_MATCH_LENGTH)
                }
        }

        private const val MIN_APP_ALIAS_MATCH_LENGTH = 2
    }

    @Serializable
    data class RouteFailurePolicy(val unavailableFailureKinds: Set<String>)

    private lateinit var failurePolicy: RouteFailurePolicy
    fun configureFailurePolicy(policy: RouteFailurePolicy) { failurePolicy = policy }

    fun unavailableRouteIds(backendSessionId: String?): Set<String> {
        if (backendSessionId == null) return emptySet()
        val receipts = latestLaunchResults().values
        val currentUpdates = receipts.map { it.packageName }.distinct().associateWith { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrNull()
        }
        return receipts.filter { unavailableForInstallation(it,
            currentUpdates[it.packageName], backendSessionId, failurePolicy) }.map { it.routeId }.toSet()
    }

    @Serializable
    data class RouteVerification(
        val activityContains: String = "",
        val textAny: List<String> = emptyList()
    )

    @Serializable
    data class DeepLinkRoute(
        val id: String,
        val category: String,
        val appName: String,
        val appAliases: List<String> = emptyList(),
        val packageName: String,
        val action: String,
        val actionAliases: List<String> = emptyList(),
        val depth: String = "deep_entry",
        val uriTemplate: String,
        val note: String = "",
        val sourceType: String = "candidate",
        val sourceUrl: String = "",
        val semanticProof: String = "",
        val verification: RouteVerification = RouteVerification(),
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val lastTestedAt: String = ""
    )

    data class AppCatalogEntry(
        val category: String,
        val appName: String,
        val packageName: String,
        val actions: List<String>
    )

    data class DeepLinkResolution(
        val route: DeepLinkRoute,
        val resolvedUri: String,
        val score: Int
    )

    data class DeepLinkCandidate(
        val routeId: String,
        val appName: String,
        val packageName: String,
        val action: String,
        val depth: String,
        val sourceType: String,
        val uri: String,
        val score: Int,
        val successCount: Int,
        val failureCount: Int,
        val lastTestedAt: String,
        val lastFailureReason: String?
    )

    @Serializable
    private data class DeepLinkRouteStats(
        val id: String,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val lastTestedAt: String = ""
    )

    @Serializable
    data class DeepLinkVerificationEvent(
        val timestamp: String,
        val routeId: String,
        val appName: String,
        val action: String,
        val packageName: String,
        val uri: String,
        val success: Boolean,
        val resultLabel: String,
        val detail: String = "",
        val appLastUpdateTime: Long? = null,
        val backendSessionId: String? = null,
        val failureKind: String? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Volatile
    private var routeCache: List<DeepLinkRoute>? = null

    @Volatile
    private var statsCache: Map<String, DeepLinkRouteStats>? = null

    fun initializeBuiltinKnowledge() {
        val current = fileManager.readFile(VERSION_FILE)?.trim()
        if (current != BUILTIN_VERSION || !fileManager.exists(BUILTIN_ROUTES)) {
            val routes = readAsset("$ASSET_DIR/routes.jsonl")
            val index = readAsset("$ASSET_DIR/INDEX.md")
            if (routes.isNotBlank()) fileManager.writeFile(BUILTIN_ROUTES, routes)
            if (index.isNotBlank()) fileManager.writeFile(INDEX_FILE, index)
            fileManager.writeFile(VERSION_FILE, BUILTIN_VERSION)
            routeCache = null
        }
        resetCandidateStatsAfterSemanticVerificationPolicyUpgrade()
    }

    fun allRoutes(): List<DeepLinkRoute> {
        routeCache?.let { return it }
        initializeBuiltinKnowledge()
        val stats = routeStats()
        val loaded = listOf(BUILTIN_ROUTES, MANUAL_ROUTES, LEARNED_ROUTES)
            .flatMap { path -> parseRoutes(fileManager.readFile(path).orEmpty()) }
            .map { route ->
                stats[route.id]?.let { learned ->
                    route.copy(
                        successCount = learned.successCount,
                        failureCount = learned.failureCount,
                        lastTestedAt = learned.lastTestedAt
                    )
                } ?: route
            }
            .distinctBy { it.id }
        routeCache = loaded
        return loaded
    }

    fun catalog(
        installedPackages: Set<String>,
        query: String? = null,
        appName: String? = null,
        category: String? = null,
        packageName: String? = null
    ): List<AppCatalogEntry> {
        val queryLower = query?.lowercase()?.trim().orEmpty()
        val appLower = appName?.lowercase()?.trim().orEmpty()
        val normalizedCategory = category?.let { normalizeCategory(it) }
        return allRoutes()
            .asSequence()
            .filterInstalled(installedPackages)
            .filter { route ->
                val categoryOk = normalizedCategory == null || normalizeCategory(route.category) == normalizedCategory
                val appOk = routeAppNameMatches(
                    routeAppName = route.appName,
                    requestedAppName = appLower,
                    packageName = packageName,
                    routeAppAliases = route.appAliases
                )
                val packageOk = packageName.isNullOrBlank() || route.packageName == packageName
                val queryOk = queryLower.isBlank() ||
                    route.appName.lowercase().contains(queryLower) ||
                    route.appAliases.any { it.lowercase().contains(queryLower) } ||
                    route.action.lowercase().contains(queryLower) ||
                    route.actionAliases.any { it.lowercase().contains(queryLower) } ||
                    route.category.lowercase().contains(queryLower)
                categoryOk && appOk && packageOk && queryOk
            }
            .groupBy { Triple(it.category, it.appName, it.packageName) }
            .map { (key, values) ->
                AppCatalogEntry(
                    category = key.first,
                    appName = key.second,
                    packageName = key.third,
                    actions = values.map { it.action }.distinct().sorted()
                )
            }
            .sortedWith(compareBy<AppCatalogEntry> { it.category }.thenBy { it.appName })
    }

    fun query(keyword: String, installedPackages: Set<String>): List<DeepLinkRoute> {
        val lower = keyword.lowercase().trim()
        val normalizedAction = normalizeAction(keyword)
        val normalizedCategory = normalizeCategory(keyword)
        return allRoutes()
            .filterInstalled(installedPackages)
            .filter { route ->
                route.category.lowercase().contains(lower) ||
                    route.appName.lowercase().contains(lower) ||
                    route.appAliases.any { it.lowercase().contains(lower) } ||
                    route.action.lowercase().contains(lower) ||
                    route.actionAliases.any { it.lowercase().contains(lower) } ||
                    normalizeAction(route.action) == normalizedAction ||
                    normalizeCategory(route.category) == normalizedCategory
            }
    }

    fun resolve(
        installedPackages: Set<String>,
        appName: String? = null,
        packageName: String? = null,
        category: String? = null,
        action: String? = null,
        keyword: String? = null,
        params: Map<String, String> = emptyMap(),
        excludedRouteIds: Set<String> = emptySet()
    ): DeepLinkResolution? = resolveAll(
        installedPackages = installedPackages,
        appName = appName,
        packageName = packageName,
        category = category,
        action = action,
        keyword = keyword,
        params = params,
        excludedRouteIds = excludedRouteIds
    ).firstOrNull()

    fun resolveAll(
        installedPackages: Set<String>,
        appName: String? = null,
        packageName: String? = null,
        category: String? = null,
        action: String? = null,
        keyword: String? = null,
        params: Map<String, String> = emptyMap(),
        excludedRouteIds: Set<String> = emptySet()
    ): List<DeepLinkResolution> {
        val normalizedAction = action?.let { normalizeAction(it) }
        val normalizedCategory = category?.let { normalizeCategory(it) }
        val appLower = appName?.lowercase()?.trim()
        val keywordLower = keyword?.lowercase()?.trim()
        val cleanParams = params
            .mapKeys { it.key.trim() }
            .mapValues { it.value.trim() }
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        val hasEffectiveParams = !keywordLower.isNullOrBlank() || cleanParams.isNotEmpty()
        val explicitActionRequested = !normalizedAction.isNullOrBlank()
        val acceptableActions = acceptableActionsFor(normalizedAction)

        return allRoutes()
            .asSequence()
            .filterInstalled(installedPackages)
            .filterNot { it.id in excludedRouteIds }
            .filter { route ->
                // An explicit route_action is auditable and may use a not-yet-verified catalog
                // candidate. Its rendered semantics still need independent verification.
                explicitActionRequested || isEligibleForAutomaticResolution(
                    sourceType = route.sourceType,
                    successCount = route.successCount,
                    failureCount = route.failureCount
                )
            }
            .filter { packageName.isNullOrBlank() || it.packageName == packageName }
            .filter { normalizedCategory == null || normalizeCategory(it.category) == normalizedCategory }
            .filter {
                routeAppNameMatches(
                    routeAppName = it.appName,
                    requestedAppName = appLower,
                    packageName = packageName,
                    routeAppAliases = it.appAliases
                )
            }
            .filter { route ->
                if (acceptableActions.isEmpty()) true
                else routeActionMatches(
                    routeAction = normalizeAction(route.action),
                    routeAliases = route.actionAliases.map(::normalizeAction),
                    requestedAction = normalizedAction,
                    rawRequestedAction = action
                )
            }
            .map { route ->
                var score = 0
                if (!packageName.isNullOrBlank() && route.packageName == packageName) score += 200
                if (normalizedCategory != null && normalizeCategory(route.category) == normalizedCategory) score += 80
                if (!appLower.isNullOrBlank()) {
                    val routeNames = (listOf(route.appName) + route.appAliases)
                        .map(String::lowercase)
                    if (routeNames.any { it == appLower }) score += 150
                    else if (routeNames.any { it.contains(appLower) || appLower.contains(it) }) score += 100
                }
                if (normalizedAction != null) {
                    val routeAction = normalizeAction(route.action)
                    val routeAliases = route.actionAliases.map(::normalizeAction)
                    if (routeAction == normalizedAction || normalizedAction in routeAliases) score += 220
                    else if (
                        actionTokens(normalizedAction)
                            .intersect(routeAliases.flatMap(::actionTokens).toSet())
                            .isNotEmpty()
                    ) score += 180
                    else if (route.action.contains(action!!, ignoreCase = true)) score += 120
                    else if (normalizedAction.contains(routeAction)) score += 100
                    else if (routeAction in acceptableActions) score += 70
                }
                val placeholders = placeholderNames(route.uriTemplate)
                if (!hasEffectiveParams) {
                    if (placeholders.isEmpty()) score += 30
                } else {
                    if (placeholders.any { it in cleanParams.keys || it == "keyword" || it == "query" }) score += 40
                    if (route.depth == "search_results") score += 20
                }
                if (isNativeScheme(route.uriTemplate)) {
                    score += 24
                } else if (isWebUri(route.uriTemplate)) {
                    score -= 8
                }
                if (normalizedAction != null && route.depth != "app_home") {
                    score += 40
                }
                // 深度策略：deep_entry > app_home > search_results
                // 官方路由的 search_results 已验证可用，不扣分
                score += when {
                    route.depth == "deep_entry" -> 60
                    route.depth == "app_home" -> 30
                    route.depth == "search_results" && route.sourceType == "official" -> 0
                    route.depth == "search_results" -> -30
                    else -> 0
                }
                score += reliabilityScore(route)
                score += route.successCount * 15
                // failureCount 超过 5 次后额外加速衰减，确保多次失败路由被过滤
                score -= route.failureCount * 18
                if (route.failureCount >= 5) score -= (route.failureCount - 5) * 30
                DeepLinkResolution(route, materialize(route, keyword, cleanParams), score)
            }
            .filter { it.score > 0 && it.resolvedUri.isNotBlank() }
            .sortedByDescending { it.score }
            .toList()
    }

    fun candidates(
        installedPackages: Set<String>,
        appName: String? = null,
        packageName: String? = null,
        category: String? = null,
        action: String? = null,
        keyword: String? = null,
        params: Map<String, String> = emptyMap()
    ): List<DeepLinkCandidate> {
        val failures = recentFailureByRouteId()
        return resolveAll(
            installedPackages = installedPackages,
            appName = appName,
            packageName = packageName,
            category = category,
            action = action,
            keyword = keyword,
            params = params
        ).map { resolution ->
            val route = resolution.route
            DeepLinkCandidate(
                routeId = route.id,
                appName = route.appName,
                packageName = route.packageName,
                action = route.action,
                depth = route.depth,
                sourceType = route.sourceType,
                uri = resolution.resolvedUri,
                score = resolution.score,
                successCount = route.successCount,
                failureCount = route.failureCount,
                lastTestedAt = route.lastTestedAt,
                lastFailureReason = failures[route.id]
            )
        }
    }

    @Synchronized
    fun recordLaunchResult(
        route: DeepLinkRoute,
        resolvedUri: String,
        success: Boolean,
        detail: String,
        resultLabel: String? = null,
        appLastUpdateTime: Long? = null,
        backendSessionId: String? = null,
        failureKind: String? = null
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val label = resultLabel ?: if (success) "success" else "failure"
        val event = DeepLinkVerificationEvent(
            timestamp = timestamp,
            routeId = route.id,
            appName = route.appName,
            action = route.action,
            packageName = route.packageName,
            uri = resolvedUri,
            success = success,
            resultLabel = label,
            detail = detail,
            appLastUpdateTime = appLastUpdateTime,
            backendSessionId = backendSessionId,
            failureKind = failureKind
        )
        fileManager.appendFile(TEST_LOG_FILE, json.encodeToString(event) + "\n")
        launchResultsCache = null
        if (shouldUpdateRouteStatsForResult(resultLabel = label, success = success)) {
            updateRouteStats(route.id, success, timestamp)
        }
    }

    private var launchResultsCache: Map<String, DeepLinkVerificationEvent>? = null

    /** Latest receipt per route, including success: a later launch supersedes an older failure. */
    @Synchronized
    fun latestLaunchResults(): Map<String, DeepLinkVerificationEvent> = launchResultsCache
        ?: fileManager.readFile(TEST_LOG_FILE)?.lineSequence()?.mapNotNull { line -> runCatching {
            json.decodeFromString<DeepLinkVerificationEvent>(line)
        }.getOrNull() }?.associateBy { it.routeId }.orEmpty().also { launchResultsCache = it }

    private fun parseRoutes(raw: String): List<DeepLinkRoute> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                runCatching { json.decodeFromString<DeepLinkRoute>(line) }.getOrNull()
            }
            .filterNot { it.uriTemplate.startsWith("android.settings.") }
            .toList()
    }

    private fun routeStats(): Map<String, DeepLinkRouteStats> {
        statsCache?.let { return it }
        val parsed = fileManager.readFile(LEARNED_STATS)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && !it.startsWith("#") }
            ?.mapNotNull { line ->
                runCatching { json.decodeFromString<DeepLinkRouteStats>(line) }.getOrNull()
            }
            ?.associateBy { it.id }
            ?: emptyMap()
        statsCache = parsed
        return parsed
    }

    /**
     * Older builds promoted a candidate after any later UI action, before the target was
     * semantically verified. Those counters must not influence automatic routing after the
     * verification policy changes. Official and non-candidate user routes are retained.
     */
    private fun resetCandidateStatsAfterSemanticVerificationPolicyUpgrade() {
        if (fileManager.readFile(CANDIDATE_STATS_POLICY_FILE)?.trim() == CANDIDATE_STATS_POLICY_VERSION) {
            return
        }
        val candidateIds = candidateRouteIds(
            listOf(BUILTIN_ROUTES, MANUAL_ROUTES, LEARNED_ROUTES)
                .flatMap { path -> parseRoutes(fileManager.readFile(path).orEmpty()) }
        )
        if (candidateIds.isNotEmpty()) {
            val retained = routeStats().filterKeys { id -> id !in candidateIds }
            if (retained.size != routeStats().size) {
                writeRouteStats(retained)
            }
        }
        fileManager.writeFile(CANDIDATE_STATS_POLICY_FILE, CANDIDATE_STATS_POLICY_VERSION)
    }

    private fun updateRouteStats(routeId: String, success: Boolean, timestamp: String) {
        val current = routeStats().toMutableMap()
        val existing = current[routeId] ?: DeepLinkRouteStats(id = routeId)
        current[routeId] = existing.copy(
            successCount = existing.successCount + if (success) 1 else 0,
            failureCount = existing.failureCount + if (success) 0 else 1,
            lastTestedAt = timestamp
        )
        writeRouteStats(current)
        routeCache = null
    }

    private fun writeRouteStats(stats: Map<String, DeepLinkRouteStats>) {
        val serialized = stats.values
            .sortedBy { it.id }
            .joinToString(separator = "\n") { entry -> json.encodeToString(entry) }
            .let { if (it.isBlank()) it else "$it\n" }
        fileManager.writeFile(LEARNED_STATS, serialized)
        statsCache = stats
    }

    private fun recentFailureByRouteId(): Map<String, String> = latestLaunchResults()
        .filterValues { !it.success && it.resultLabel != "launched_unverified" }
        .mapValues { (_, event) -> "${event.resultLabel}: ${event.detail}".take(220) }

    private fun readAsset(path: String): String {
        return runCatching {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }

    private fun Sequence<DeepLinkRoute>.filterInstalled(installedPackages: Set<String>): Sequence<DeepLinkRoute> {
        if (installedPackages.isEmpty()) return this
        return filter { it.packageName in installedPackages }
    }

    private fun List<DeepLinkRoute>.filterInstalled(installedPackages: Set<String>): List<DeepLinkRoute> {
        if (installedPackages.isEmpty()) return this
        return filter { it.packageName in installedPackages }
    }

    private fun acceptableActionsFor(normalizedAction: String?): Set<String> {
        if (normalizedAction.isNullOrBlank()) return emptySet()
        return setOf(normalizedAction) + normalizedAction
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotBlank)
    }

    private fun reliabilityScore(route: DeepLinkRoute): Int {
        return when (route.sourceType) {
            "official" -> 35
            "system" -> 30
            "web" -> 10
            else -> 0
        }
    }

    private fun isNativeScheme(uri: String): Boolean =
        !isWebUri(uri) && !uri.startsWith("android.settings.")

    private fun isWebUri(uri: String): Boolean =
        uri.startsWith("http://") || uri.startsWith("https://")

    private fun normalizeAction(action: String): String {
        return action.trim().lowercase()
    }

    private fun normalizeCategory(category: String): String {
        return category.trim()
    }

    private fun materialize(route: DeepLinkRoute, keyword: String?, params: Map<String, String>): String {
        val merged = linkedMapOf<String, String>()
        params.forEach { (key, value) ->
            val cleanKey = key.trim()
            val cleanValue = value.trim()
            if (cleanKey.isNotBlank() && cleanValue.isNotBlank()) merged[cleanKey] = cleanValue
        }
        if (!keyword.isNullOrBlank()) {
            merged.putIfAbsent("keyword", keyword)
            merged.putIfAbsent("query", keyword)
            merged.putIfAbsent("keywords", keyword)
            merged.putIfAbsent("q", keyword)
        }

        var resolved = route.uriTemplate
        placeholderNames(route.uriTemplate).forEach { name ->
            val value = merged[name]
            if (!value.isNullOrBlank()) {
                resolved = resolved.replace("{$name}", URLEncoder.encode(value, "UTF-8"))
            }
        }
        // 如果还有未解析的占位符，返回空串让调用层跳过这条路由
        if (Regex("""\{[A-Za-z_][A-Za-z0-9_]*\}""").containsMatchIn(resolved)) return ""
        return resolved
    }

    private fun placeholderNames(uriTemplate: String): Set<String> {
        return Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")
            .findAll(uriTemplate)
            .map { it.groupValues[1] }
            .toSet()
    }
}
