// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.config

import com.droidance.core.agent.config.*

import com.droidance.core.device.config.*

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.droidance.core.device.tools.DeviceShellCommandChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the device-user inventory, persisted quality and all refresh serialization. */
@Singleton
class InstalledAppsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellChannel: DeviceShellCommandChannel
) {
    private val prefs by lazy { context.getSharedPreferences("droidance_installed_apps", Context.MODE_PRIVATE) }
    private val userId = Process.myUid() / 100000
    private val collectorUpdatedAtMs by lazy { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }
    private val mutex = Mutex()
    private lateinit var policy: AppInventoryPolicy
    @Volatile private var initialized = false
    @Volatile private var snapshot = AppInventorySnapshot(userId, 0, emptySet(), emptySet(), emptyList(),
        "unavailable", false, false, 0, 0, true, "NOT_SCANNED", 0)

    fun configurePolicy(value: AppInventoryPolicy) { value.validate(); policy = value }
    fun policy(): AppInventoryPolicy = policy
    fun currentSnapshot(): AppInventorySnapshot = snapshot
    fun hasVerifiedPackageInventory(): Boolean = snapshot.complete && !snapshot.dirty &&
        snapshot.capturedAtMs > 0

    suspend fun init() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return@withLock
            // Only the current snapshot format is read. Old list-only caches are re-collected.
            prefs.getString("snapshot_json", null)?.let { raw ->
                runCatching { Json.decodeFromString<AppInventorySnapshot>(raw) }.getOrNull()
                    ?.takeIf { it.userId == userId }?.let { snapshot = it.forCollector(collectorUpdatedAtMs) }
            }
            initialized = true
        }
    }

    @Synchronized fun invalidate() {
        snapshot = snapshot.copy(dirty = true, generation = snapshot.generation + 1)
        persist()
    }

    private fun persist() {
        prefs.edit().putString("snapshot_json", Json.encodeToString(snapshot)).apply()
    }

    private suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val requestedAt = System.currentTimeMillis()
        mutex.withLock {
            val before = snapshot
            if (before.lastAttemptAtMs >= requestedAt) return@withLock false
            val attempt = collect()
            synchronized(this@InstalledAppsManager) {
                val invalidatedDuringScan = snapshot.generation != before.generation
                snapshot = snapshot.merge(attempt).let {
                    if (invalidatedDuringScan) it.copy(dirty = true) else it
                }
                persist()
            }
            snapshot != before
        }
    }

    private fun collect(packageName: String? = null): AppInventorySnapshot {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            packageName?.let(::setPackage)
        }
        val visible = runCatching { if (packageName == null) pm.getInstalledApplications(0)
            else listOf(pm.getApplicationInfo(packageName, 0)) }.getOrDefault(emptyList())
        val system = visible.filter { it.isSystemApp() }.map { it.packageName }.toSet()
        var packages = visible.map { it.packageName }.toSet()
        var systemPackages = system
        var complete = false
        var launcherComplete = false
        var source = "package_manager_partial"
        var error: String? = "PACKAGE_VISIBILITY_UNVERIFIED"
        val entries = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())
            .filter { it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled && it.activityInfo.exported }
            .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }.toMutableSet()
        val packageFilter = packageName?.let { " $it" }.orEmpty()
        if (shellChannel.isAvailable()) {
            val users = shellChannel.exec("pm list packages --user $userId -3$packageFilter", 10000)
            val systems = shellChannel.exec("pm list packages --user $userId -s$packageFilter", 10000)
            if (users.success && systems.success && !users.stdoutTruncated && !systems.stdoutTruncated) {
                val scanned = (parseInstalledPackageListing(users.stdout) + parseInstalledPackageListing(systems.stdout))
                    .filter { packageName == null || it == packageName }.toSet()
                if (scanned.isNotEmpty() || packageName != null) {
                    packages = scanned; systemPackages = parseInstalledPackageListing(systems.stdout).intersect(scanned)
                    complete = true; source = "authorized_device_shell"; error = null
                }
            } else error = "PACKAGE_SCAN_FAILED"
            val launchers = shellChannel.exec("cmd package query-activities --brief --components --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER${packageName?.let { " -p $it" }.orEmpty()}", 10000)
            if (launchers.success && !launchers.stdoutTruncated && complete) {
                val queried = launchers.stdout.lineSequence().map(String::trim)
                    .filter { '/' in it && it.substringBefore('/') in packages }.toSet()
                if (queried.isNotEmpty() || packageName != null) { entries.clear(); entries.addAll(queried); launcherComplete = true }
                else error = "LAUNCHER_QUERY_UNAVAILABLE"
            } else if (complete) error = "LAUNCHER_QUERY_FAILED"
        }
        val apps = entries.groupBy { it.substringBefore('/') }.map { (pkg, targets) ->
            val application = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            val label = application?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            val update = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrNull()
            AppInfo(pkg, label ?: pkg, application?.isSystemApp() ?: (pkg in systemPackages), targets.sorted().first(), update,
                labelVerified = label != null, launcherVerified = launcherComplete || application != null,
                enabled = application?.enabled ?: true.takeIf { launcherComplete })
        }.sortedBy { it.label }
        if (!complete) {
            packages = packages + apps.map { it.packageName }
            systemPackages = systemPackages + apps.filter { it.isSystemApp }.map { it.packageName }
        }
        val now = System.currentTimeMillis()
        return AppInventorySnapshot(userId, 0, packages, systemPackages, apps, source, complete,
            launcherComplete, now, now, !complete || !launcherComplete, error, collectorUpdatedAtMs)
    }

    private val lookup by lazy { AppInventoryLookup(
        current = { snapshot }, refresh = { refresh() }, policy = { policy }
    ) }

    /** A package broadcast is authoritative for removal; additions are queried by exact package. */
    suspend fun onPackageChanged(packageName: String, removed: Boolean) = withContext(Dispatchers.IO) {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")))
        init()
        mutex.withLock {
            val attempt = if (removed) null else collect(packageName)
            synchronized(this@InstalledAppsManager) {
                snapshot = snapshot.withPackageChange(packageName, attempt)
                persist()
            }
        }
    }

    /** Channel recovery only repairs incomplete collection, never scans a healthy cache. */
    suspend fun refreshIfIncomplete() {
        init()
        if (!snapshot.complete || snapshot.dirty) lookup.ensure("", "", refreshIncomplete = true)
    }

    fun getAll(): List<AppInfo> = snapshot.apps
    suspend fun getAllAwait(): List<AppInfo> { ensureLookup(); return snapshot.apps }
    suspend fun packagesAwait(packageName: String? = null): Set<String> {
        ensureLookup(packageName = packageName.orEmpty()); return snapshot.packages
    }
    private suspend fun ensureLookup(query: String = "", packageName: String = "") {
        init()
        lookup.ensure(query, packageName)
    }
    fun getLabel(packageName: String): String? = snapshot.apps.firstOrNull { it.packageName == packageName }?.label
    fun isSystemPackage(packageName: String): Boolean? = if (packageName in snapshot.packages)
        packageName in snapshot.systemPackages else null
    fun count(): Int = snapshot.apps.size
    suspend fun findCandidates(appName: String): List<AppInfo> {
        ensureLookup(query = appName)
        return AppNameMatcher.findCandidates(appName, snapshot.apps)
    }
    suspend fun findPackageAwait(appName: String): String? = findCandidates(appName).singleOrNull()?.packageName
    /** Re-resolve only the failed launcher; never repeats the launch itself. */
    suspend fun refreshPackage(packageName: String) = onPackageChanged(packageName, removed = false)

    suspend fun readApps(arguments: String): com.droidance.core.agent.event.ToolExecutionResult {
        val args = Json.parseToJsonElement(arguments).jsonObject
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: policy.pageSize
        if (offset < 0 || limit !in 1..policy.pageSize) return com.droidance.core.agent.tools.ToolProtocolResult.invalidArgument("pagination", "out_of_range")
        val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        ensureLookup(query, packageName)
        return com.droidance.core.agent.tools.ToolProtocolResult.success(snapshot.page(
            query, packageName, offset, limit, System.currentTimeMillis()).toString())
    }
    fun modelContext(packageName: String?, query: String?, maxChars: Int): String = snapshot.context(
        minOf(maxChars, policy.contextMaxChars), packageName, query, System.currentTimeMillis())
    private fun ApplicationInfo.isSystemApp(): Boolean = flags and
        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}

internal fun parseInstalledPackageListing(text: String): Set<String> = text.lineSequence().map(String::trim)
    .filter { it.startsWith("package:") }.map { it.removePrefix("package:") }
    .filter { it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) }.toSet()
