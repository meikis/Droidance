// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Configuration
import com.droidance.core.device.air.AirModePairingManager
import com.droidance.core.agent.AgentProcessRecoveryCoordinator
import com.droidance.agent.AgentWorkspaceStore
import com.droidance.automation.AppTakeoverCoordinator
import com.droidance.automation.AutomationCoordinator
import com.droidance.automation.AutomationManager
import com.droidance.config.DeepLinkKnowledgeStore
import com.droidance.core.device.config.InstalledAppsManager
import com.droidance.config.RuleConfigManager
import com.droidance.daemon.HeartbeatManager
import com.droidance.daemon.KeepAliveJobService
import com.droidance.daemon.TaskExecutionService
import com.droidance.ui.overlay.AgentStatusOverlay
import com.droidance.ui.overlay.RuntimeForegroundActivities
import android.view.Display
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltAndroidApp
class DroidanceApp : Application(), Configuration.Provider {

    @Inject lateinit var runtimeProcessClient: com.droidance.runtime.RuntimeProcessClient
    @Inject lateinit var foregroundState: com.droidance.agent.AppForegroundState
    @Inject lateinit var builtinSkillInstaller: com.droidance.skill.BuiltinSkillInstaller

    @Inject lateinit var experiences: com.droidance.memory.ExperienceRepository
    @Inject lateinit var deferredMemoryWriter: com.droidance.memory.DeferredMemoryWriter
    @Inject lateinit var agentWorkspaceStore: AgentWorkspaceStore
    @Inject lateinit var heartbeatManager: HeartbeatManager
    @Inject lateinit var agentStatusOverlay: AgentStatusOverlay
    @Inject lateinit var installedAppsManager: InstalledAppsManager
    @Inject @com.droidance.core.agent.di.ApplicationScope lateinit var applicationScope: CoroutineScope
    @Inject lateinit var deepLinkKnowledgeStore: DeepLinkKnowledgeStore
    @Inject lateinit var ruleConfigManager: RuleConfigManager
    @Inject lateinit var capabilityRegistry: com.droidance.agent.RuntimeCapabilityRegistry
    @Inject lateinit var generalSettings: com.droidance.settings.GeneralSettingsRepository
    @Inject lateinit var airModePairingManager: AirModePairingManager
    @Inject lateinit var appTakeoverCoordinator: AppTakeoverCoordinator
    @Inject lateinit var automationCoordinator: AutomationCoordinator
    @Inject lateinit var automationManager: AutomationManager
    @Inject lateinit var agentProcessRecoveryCoordinator: AgentProcessRecoveryCoordinator
    @Inject lateinit var airNotifications: com.droidance.core.device.air.AirModeNotificationController

    private var presentationLanguage: String? = null

    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val activeWifiNetworks = ConcurrentHashMap.newKeySet<Network>()
    // Connection callbacks can write remote diagnostics and wait for the owner.
    // Keep them ordered without occupying the UI looper that receives its Binder.
    private val networkHandler by lazy {
        Handler(android.os.HandlerThread("droidance-network-events").apply { start() }.looper)
    }
    @Volatile private var pendingAirWifiTrigger: String = "registered"
    private val airWifiTransportRefresh = Runnable {
        airModePairingManager.onWifiNetworkChanged(
            pendingAirWifiTrigger,
            activeWifiNetworks.isNotEmpty()
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .build()

    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onCreate() {
        // Do not initialize Hilt, credentials, or app state in the isolated worker.
        // Application.onCreate itself is empty; the Hilt superclass performs injection.
        val role = getProcessName().substringAfter(':', "")
        if (role.startsWith("program_worker") || role == "input_driver") return
        super.onCreate()
        builtinSkillInstaller.install()
        initializeRuntimeStorage()
        if (runtimeProcessClient.isOwner) {
            deferredMemoryWriter.resume()
            experiences.resume()
            agentProcessRecoveryCoordinator.start()
            appTakeoverCoordinator.start()
            automationManager.restoreSchedules()
            automationCoordinator.start()
            applicationScope.launch(Dispatchers.IO) { installedAppsManager.init() }
            return
        }
        runtimeProcessClient.startPresentation()
        // 追踪自身 Activity 前台状态，用于悬浮窗可见性判断
        val foregroundActivities = RuntimeForegroundActivities<Activity>()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                refreshLanguage()
                val displayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    activity.display?.displayId
                } else {
                    @Suppress("DEPRECATION")
                    activity.windowManager.defaultDisplay?.displayId
                }
                val onMainDisplay = activity !is com.droidance.agent.device.VirtualSpaceProbeActivity &&
                    (displayId == Display.DEFAULT_DISPLAY || displayId == null)
                foregroundState.update(foregroundActivities.resume(activity, onMainDisplay))
                runtimeProcessClient.send("foreground", android.os.Bundle().apply { putBoolean("visible", foregroundState.isInForeground) })
            }
            override fun onActivityPaused(activity: Activity) {
                foregroundState.update(foregroundActivities.remove(activity))
                runtimeProcessClient.send("foreground", android.os.Bundle().apply { putBoolean("visible", foregroundState.isInForeground) })
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                foregroundState.update(foregroundActivities.remove(activity))
                runtimeProcessClient.send("foreground", android.os.Bundle().apply { putBoolean("visible", foregroundState.isInForeground) })
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                foregroundState.update(foregroundActivities.remove(activity))
                runtimeProcessClient.send("foreground", android.os.Bundle().apply { putBoolean("visible", foregroundState.isInForeground) })
            }
        })
        KeepAliveJobService.schedule(this)
        heartbeatManager.startHeartbeat()
        registerNetworkCallback()
        applicationScope.launch(Dispatchers.IO) { airModePairingManager.ensureConnectedIfPaired() }
        applicationScope.launch(Dispatchers.IO) {
            installedAppsManager.init()
            com.droidance.daemon.startAppInventoryMaintenance(this@DroidanceApp, installedAppsManager, applicationScope)
            capabilityRegistry.monitorChannelAvailability { installedAppsManager.refreshIfIncomplete() }
        }
    }

    private fun initializeRuntimeStorage() {
        initDataDirectories()
        agentWorkspaceStore.initializeDefault()
        ruleConfigManager.initialize()
        installedAppsManager.configurePolicy(kotlinx.serialization.json.Json.decodeFromString<com.droidance.core.device.config.AppInventoryPolicy>(
            ruleConfigManager.readMarkdownSection("device_tools.md", "app_inventory_policy")))
        deepLinkKnowledgeStore.configureFailurePolicy(kotlinx.serialization.json.Json.decodeFromString(
            ruleConfigManager.readMarkdownSection("device_tools.md", "route_failure_policy")))
        deepLinkKnowledgeStore.initializeBuiltinKnowledge()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        if (wifiNetworkCallback == null) {
            val wifiCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    activeWifiNetworks.add(network)
                    scheduleAirWifiTransportRefresh("available")
                }

                override fun onLost(network: Network) {
                    activeWifiNetworks.remove(network)
                    notifyAirWifiTransportLost()
                }
            }
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            try {
                connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback)
                wifiNetworkCallback = wifiCallback
                scheduleAirWifiTransportRefresh("registered")
            } catch (e: Exception) {
                Log.w("DroidanceApp", "注册 WiFi 监听失败: ${e.message}")
            }
        }
    }

    private fun scheduleAirWifiTransportRefresh(trigger: String) {
        pendingAirWifiTrigger = trigger
        networkHandler.removeCallbacks(airWifiTransportRefresh)
        networkHandler.postDelayed(airWifiTransportRefresh, 1_500L)
    }

    private fun notifyAirWifiTransportLost() {
        networkHandler.removeCallbacks(airWifiTransportRefresh)
        networkHandler.post {
            airModePairingManager.onWifiNetworkChanged(
                "lost",
                activeWifiNetworks.isNotEmpty()
            )
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val role = getProcessName().substringAfter(':', "")
        if (!role.startsWith("program_worker") && role != "input_driver") refreshLanguage()
    }

    private fun refreshLanguage() {
        val current = com.droidance.config.LanguageManager.resolvedTag(this)
        if (current == presentationLanguage) return
        presentationLanguage = current
        TaskExecutionService.refreshLanguage(this)
        airNotifications.refreshLanguage()
        agentStatusOverlay.refreshLanguage()
    }

    private fun initDataDirectories() {
        val baseDir = noBackupFilesDir ?: filesDir
        listOf(
            "agents/default", "skills", "memory", "memory/apps", "tasks", "scripts",
            "deeplinks", "deeplinks/builtin", "deeplinks/learned", "deeplinks/manual",
            "global", "rules"
        ).forEach { dir ->
            File(baseDir, "droidance-data/$dir").mkdirs()
        }
    }
}
