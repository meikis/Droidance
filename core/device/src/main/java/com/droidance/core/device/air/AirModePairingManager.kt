// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.droidance.core.agent.debug.DebugLogAppender
import com.droidance.core.agent.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AirModePairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val notificationController: AirModeNotificationController,
    private val debugLogAppender: DebugLogAppender
) {
    enum class PairingState { IDLE, DISCOVERING, AWAITING_CODE, PAIRING, CONNECTING, CONNECTED }
    enum class PairingTrustState { UNPAIRED, PAIRED, PAIRING_REQUIRED }
    enum class ShellHealthState { UNKNOWN, READY, DOWN }

    data class ActiveAirEndpoint(
        val host: String,
        val port: Int,
        val source: String,
        val endpointId: String = "$host:$port"
    )

    data class ShellHealthSnapshot(
        val state: ShellHealthState,
        val ready: Boolean,
        val lastFailure: String,
        val lastCheckedAtMs: Long,
        val probeOutput: String,
        val pairingState: PairingState,
        val pairingTrustState: PairingTrustState,
        val previouslyPaired: Boolean,
        val wifiTransportAvailable: Boolean?,
        val secureSettingsGranted: Boolean,
        val wirelessDebuggingEnabled: Boolean?,
        val activeEndpoint: ActiveAirEndpoint?
    )

    private companion object {
        const val AIR_HEALTH_CHECK_MARKER = "droidance_air_ready"
        const val AIR_HEALTH_CHECK_COMMAND = "echo $AIR_HEALTH_CHECK_MARKER"
        const val LOCAL_ADB_HOST = "127.0.0.1"
        const val TLS_PAIRING_SERVICE = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT_SERVICE = "_adb-tls-connect._tcp"
        const val PAIRING_DISCOVERY_REFRESH_MS = 8_000L
        const val PAIRING_DISCOVERY_RESTART_GAP_MS = 250L
        const val RECONNECT_DELAY_MS = 1_500L
        const val CONNECT_RETRY_DELAY_MS = 5_000L
        const val KEY_AIR_PAIRED = "air_paired"
        const val KEY_AIR_PAIRING_REQUIRED = "air_pairing_required"
    }

    private val prefs = context.getSharedPreferences("droidance_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PairingState.IDLE)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private val _pairingTrustState = MutableStateFlow(initialPairingTrustState())
    val pairingTrustState: StateFlow<PairingTrustState> = _pairingTrustState.asStateFlow()
    val isPairingRequired: Boolean
        get() = _pairingTrustState.value == PairingTrustState.PAIRING_REQUIRED
    val isConnected: Boolean get() = isShellReady
    val isShellReady: Boolean
        get() = _state.value == PairingState.CONNECTED &&
            shellHealthState == ShellHealthState.READY &&
            activeConnection != null

    // 带超时的 shell 在独立线程跑阻塞的 conn.shell；超时后任务可能仍占用线程，故用可回收的 cached 池
    private val shellTimeoutExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "air-shell-timeout").apply { isDaemon = true }
    }

    private val _failure = MutableStateFlow<AirPairingFailure?>(null)
    val failure: StateFlow<AirPairingFailure?> = _failure.asStateFlow()

    val isPreviouslyPaired: Boolean
        get() = _pairingTrustState.value == PairingTrustState.PAIRED && privateKeyFile().exists()

    init {
        if (isPairingRequired) {
            prefs.edit()
                .remove(KEY_AIR_PAIRED)
                .putBoolean(KEY_AIR_PAIRING_REQUIRED, true)
                .apply()
            _failure.value = AirPairingFailure.PAIRING_REQUIRED
            runCatching { notificationController.showPairingRequired() }
        } else if (isPreviouslyPaired && isWifiTransportAvailable()) {
            runCatching { notificationController.cancel() }
            appScope.launch(Dispatchers.IO) {
                ensureWirelessDebuggingEnabled()
                _state.value = PairingState.CONNECTING
                startConnectDiscovery()
            }
        }
    }

    @Volatile private var pendingHost: String? = null
    @Volatile private var pendingPort: Int = 0
    @Volatile private var activeConnection: AirAdbClient? = null
    @Volatile private var activeEndpoint: ActiveAirEndpoint? = null
    @Volatile private var pairingTimeoutJob: Job? = null
    @Volatile private var discoveryTimeoutJob: Job? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var queuedPairCode: String? = null
    @Volatile private var shellHealthState: ShellHealthState = ShellHealthState.UNKNOWN
    @Volatile private var lastShellFailure: String = ""
    @Volatile private var lastShellCheckedAtMs: Long = 0L
    @Volatile private var lastShellProbeOutput: String = ""
    @Volatile private var shellSessionGeneration: Long = 0L
    // A callback has not established Wi-Fi availability at process startup.
    @Volatile private var wifiTransportAvailable: Boolean? = null

    @Volatile private var pairingDiscovery: AirMdnsDiscovery? = null
    @Volatile private var pairingDiscoveryRefreshJob: Job? = null
    private val pairingDiscoveryGeneration = AtomicLong(0L)
    private val pairingAttemptGeneration = AtomicLong(0L)
    @Volatile private var connectDiscovery: AirMdnsDiscovery? = null
    @Volatile private var connectScanJob: Job? = null
    private val connectionMutex = Mutex()

    private fun privateKeyFile(): File = File(context.filesDir, "air_key")
    private fun publicKeyFile(): File = File(context.filesDir, "air_key.pub")

    private fun initialPairingTrustState(): PairingTrustState = when {
        prefs.getBoolean(KEY_AIR_PAIRING_REQUIRED, false) -> PairingTrustState.PAIRING_REQUIRED
        prefs.getBoolean(KEY_AIR_PAIRED, false) && privateKeyFile().exists() -> PairingTrustState.PAIRED
        prefs.getBoolean(KEY_AIR_PAIRED, false) -> PairingTrustState.PAIRING_REQUIRED
        else -> PairingTrustState.UNPAIRED
    }

    private fun loadOrCreateAdbKey(): AirAdbKey =
        AirAdbKey.loadOrCreate(privateKeyFile(), publicKeyFile())

    fun shellHealthSnapshot(): ShellHealthSnapshot = ShellHealthSnapshot(
        state = shellHealthState,
        ready = isShellReady,
        lastFailure = lastShellFailure,
        lastCheckedAtMs = lastShellCheckedAtMs,
        probeOutput = lastShellProbeOutput,
        pairingState = _state.value,
        pairingTrustState = _pairingTrustState.value,
        previouslyPaired = isPreviouslyPaired,
        wifiTransportAvailable = wifiTransportAvailable,
        secureSettingsGranted = hasSecureSettingsPermission(),
        wirelessDebuggingEnabled = isWirelessDebuggingEnabled(),
        activeEndpoint = activeEndpoint
    )

    /**
     * Identity of the live ADB shell connection. It changes on every successful reconnect even
     * when wireless debugging happens to reuse the same endpoint address and port.
     */
    fun currentShellSessionId(): String? = activeEndpoint
        ?.takeIf { isShellReady }
        ?.let { endpoint -> "air-shell:${endpoint.endpointId}:$shellSessionGeneration" }

    fun notificationAvailability(): AirPairingNotificationAvailability =
        notificationController.availability()

    fun startDiscovery(): Boolean {
        if (_state.value in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE) && queuedPairCode == null) {
            return refreshPairingNotificationIfAwaiting()
        }
        if (_state.value != PairingState.IDLE) return false
        val notificationAvailability = notificationController.availability()
        if (notificationAvailability != AirPairingNotificationAvailability.AVAILABLE) {
            _failure.value = pairingNotificationFailure(notificationAvailability)
            logPairingEvent(
                "AIR pairing discovery withheld: notification=${notificationAvailability.name.lowercase()}"
            )
            return false
        }
        _failure.value = null
        queuedPairCode = null
        clearPendingPairingEndpoint()
        stopConnectAttempt()
        _state.value = PairingState.DISCOVERING
        logPairingEvent("AIR pairing discovery started")
        // Prepare a silent RemoteInput while foreground so it remains usable if frozen.
        // Alert only after discovering the service opened by the system pairing-code dialog.
        if (!showPairingCodeNotification(alert = false)) return false
        // Connecting before pairing grants trust can reject the new key and reset this flow.
        // Start ADB connection discovery only after the pairing protocol succeeds.
        startPairingDiscovery()
        return true
    }

    private fun startPairingDiscovery() {
        pairingDiscoveryRefreshJob?.cancel()
        installPairingDiscovery()
        pairingDiscoveryRefreshJob = appScope.launch {
            while (_state.value == PairingState.DISCOVERING && pendingPort <= 0) {
                delay(PAIRING_DISCOVERY_REFRESH_MS)
                if (_state.value != PairingState.DISCOVERING || pendingPort > 0) break
                logPairingEvent("AIR pairing mDNS: discovery_refresh")
                pairingDiscovery?.stop()
                pairingDiscovery = null
                delay(PAIRING_DISCOVERY_RESTART_GAP_MS)
                if (_state.value == PairingState.DISCOVERING && pendingPort <= 0) {
                    installPairingDiscovery()
                }
            }
        }
    }

    private fun installPairingDiscovery() {
        val generation = pairingDiscoveryGeneration.incrementAndGet()
        pairingDiscovery?.stop()
        pairingDiscovery = AirMdnsDiscovery(
            context = context,
            appScope = appScope,
            serviceType = TLS_PAIRING_SERVICE,
            onResolved = { host, port ->
                if (
                    _state.value != PairingState.DISCOVERING ||
                    generation != pairingDiscoveryGeneration.get()
                ) return@AirMdnsDiscovery
                pendingHost = preferLocalAdbHost(host)
                pendingPort = port
                val queued = queuedPairCode
                if (queued != null) {
                    doExecutePairing(pendingHost ?: LOCAL_ADB_HOST, port, queued)
                } else {
                    moveToAwaitingCode()
                }
            },
            onDiagnostic = { detail -> logPairingEvent("AIR pairing mDNS: $detail") }
        ).also { it.start() }
    }

    fun stopDiscovery() {
        pairingAttemptGeneration.incrementAndGet()
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        pairingDiscoveryGeneration.incrementAndGet()
        queuedPairCode = null
        clearPendingPairingEndpoint()
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        pairingDiscovery?.stop()
        pairingDiscovery = null
        if (_state.value !in setOf(PairingState.CONNECTED, PairingState.PAIRING)) {
            notificationController.cancel()
            _state.value = PairingState.IDLE
        }
    }

    // Called when user submits the 6-digit pairing code from the notification.
    // The code is used by the system's wireless debugging pairing protocol (SPAKE2+).
    // After submitting, we discover the ADB connect port and attempt connection
    // using the pre-generated key pair (which the system will trust post-pairing).

    fun reconnect(): Boolean {
        if (!isPreviouslyPaired || _state.value != PairingState.IDLE) return false
        if (!isWifiTransportAvailable()) {
            if (wifiTransportAvailable == false) _failure.value = AirPairingFailure.WIFI_UNAVAILABLE
            return false
        }
        _failure.value = null
        _state.value = PairingState.CONNECTING
        appScope.launch(Dispatchers.IO) {
            ensureWirelessDebuggingEnabled()
            startConnectDiscovery()
        }
        return true
    }

    fun ensureConnectedIfPaired() {
        if (!isPreviouslyPaired || _state.value != PairingState.IDLE || !isWifiTransportAvailable()) return
        ensureWirelessDebuggingEnabled()
        reconnect()
    }

    fun onWifiNetworkChanged(trigger: String, wifiAvailable: Boolean) {
        wifiTransportAvailable = wifiAvailable
        debugLogAppender.appendEvent(
            "AIR wifi_transport_changed(trigger=${trigger.take(40)}, available=$wifiAvailable, " +
                "state=${_state.value.name.lowercase()}, trust=${_pairingTrustState.value.name.lowercase()})"
        )
        if (!isPreviouslyPaired) return

        stopConnectAttempt()
        markShellDown(
            "AIR WiFi transport changed: trigger=${trigger.take(40)} available=$wifiAvailable",
            scheduleReconnect = false
        )
        if (wifiAvailable) {
            _failure.value = null
            scheduleReconnectIfPaired()
        } else {
            _failure.value = AirPairingFailure.WIFI_UNAVAILABLE
        }
    }

    fun hasSecureSettingsPermission(): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun isWifiTransportAvailable(): Boolean = wifiTransportAvailable == true

    fun isWirelessDebuggingEnabled(): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    }.getOrNull()

    /**
     * 首次 ADB 连通后已经授予 WRITE_SECURE_SETTINGS；以后 App/保活服务可以在本机恢复无线调试。
     * 这里只恢复开关，不伪造端口：设备重启后的动态端口仍由 Settings/mDNS 发现。
     */
    fun ensureWirelessDebuggingEnabled(): Boolean {
        if (!isPreviouslyPaired || !isWifiTransportAvailable() || !hasSecureSettingsPermission()) return false
        if (isWirelessDebuggingEnabled() == true) return true
        return runCatching {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        }.getOrDefault(false)
    }

    fun requestPairing(code: String) {
        if (code.length != 6 || !code.all { it.isDigit() }) {
            _failure.value = AirPairingFailure.INVALID_PAIRING_CODE
            runCatching { notificationController.showInvalidPairingCode() }
            return
        }
        if (_state.value !in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE)) {
            _failure.value = AirPairingFailure.PAIRING_REQUEST_UNAVAILABLE
            runCatching { notificationController.showPairingUnavailable() }
            return
        }
        _failure.value = null
        queuedPairCode = code
        // The system allocates a fresh pairing port when its code dialog is reopened. Never
        // bind a submitted code to the endpoint that happened to be discovered earlier.
        pairingDiscoveryGeneration.incrementAndGet()
        clearPendingPairingEndpoint()
        _state.value = PairingState.DISCOVERING
        logPairingEvent("AIR pairing endpoint refresh requested for submitted code")
        runCatching { notificationController.showPairingWaitingForService() }
        // Time the submitted request, not the user's time spent in system settings.
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = appScope.launch {
            delay(120_000)
            if (_state.value == PairingState.DISCOVERING && queuedPairCode != null) {
                val failure = AirPairingFailure.PAIRING_SERVICE_NOT_FOUND
                _failure.value = failure
                logPairingEvent("AIR pairing endpoint discovery timed out after code submission")
                stopDiscovery()
                runCatching { notificationController.showPairingFailure(failure) }
            }
        }
        startPairingDiscovery()
    }

    private fun moveToAwaitingCode() {
        if (showPairingCodeNotification(alert = true)) {
            pairingDiscoveryRefreshJob?.cancel()
            pairingDiscoveryRefreshJob = null
            _state.value = PairingState.AWAITING_CODE
        }
    }

    private fun showPairingCodeNotification(alert: Boolean): Boolean {
        val postResult = notificationController.showPairingRequest(_failure.value, alert = alert)
        if (postResult.posted) {
            return true
        } else {
            _failure.value = pairingNotificationFailure(postResult.availability)
            logPairingEvent(
                "AIR pairing code notification unavailable: ${postResult.availability.name.lowercase()} " +
                    postResult.detail.take(160)
            )
            stopDiscovery()
            return false
        }
    }

    fun refreshPairingNotificationIfAwaiting(): Boolean {
        if (_state.value !in setOf(PairingState.DISCOVERING, PairingState.AWAITING_CODE) || queuedPairCode != null) return false
        // Discovery can remain active after rejection or an invalid code. Restore RemoteInput
        // immediately without requiring another settings navigation or reusing a stale endpoint.
        return showPairingCodeNotification(alert = false)
    }

    private fun pairingNotificationFailure(
        availability: AirPairingNotificationAvailability
    ): AirPairingFailure = when (availability) {
            AirPairingNotificationAvailability.PERMISSION_REQUIRED ->
                AirPairingFailure.NOTIFICATION_PERMISSION_REQUIRED
            AirPairingNotificationAvailability.APP_NOTIFICATIONS_DISABLED ->
                AirPairingFailure.APP_NOTIFICATIONS_DISABLED
            AirPairingNotificationAvailability.CHANNEL_DISABLED ->
                AirPairingFailure.NOTIFICATION_CHANNEL_DISABLED
            AirPairingNotificationAvailability.POST_FAILED,
            AirPairingNotificationAvailability.AVAILABLE ->
                AirPairingFailure.NOTIFICATION_POST_FAILED
        }

    private fun logPairingEvent(event: String) {
        debugLogAppender.appendEvent(event)
        debugLogAppender.appendPersistentEvent("air-pairing", event)
    }

    private fun clearPendingPairingEndpoint() {
        pendingHost = null
        pendingPort = 0
    }

    private fun resumePairingDiscovery(failure: AirPairingFailure, diagnostic: String) {
        pairingAttemptGeneration.incrementAndGet()
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDiscoveryGeneration.incrementAndGet()
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        queuedPairCode = null
        clearPendingPairingEndpoint()
        _failure.value = failure
        _state.value = PairingState.DISCOVERING
        logPairingEvent(diagnostic)
        runCatching { notificationController.showPairingFailure(failure, allowRetry = true) }
        startPairingDiscovery()
    }

    private fun doExecutePairing(host: String, port: Int, code: String) {
        val attemptGeneration = pairingAttemptGeneration.incrementAndGet()
        queuedPairCode = null
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        _failure.value = null
        runCatching { notificationController.showPairingSubmitted() }
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        _state.value = PairingState.PAIRING
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = appScope.launch(Dispatchers.IO) {
            delay(20_000)
            if (_state.value == PairingState.PAIRING &&
                pairingAttemptGeneration.get() == attemptGeneration) {
                resumePairingDiscovery(
                    AirPairingFailure.PAIRING_TIMEOUT,
                    "AIR pairing timed out while waiting for the system pairing service"
                )
            }
        }
        appScope.launch(Dispatchers.IO) {
            val adbKey = loadOrCreateAdbKey()
            runCatching {
                AirPairingClient(host, port, code, adbKey).pair()
            }.onSuccess { success ->
                if (pairingAttemptGeneration.get() != attemptGeneration) return@onSuccess
                pairingTimeoutJob?.cancel()
                pairingTimeoutJob = null
                if (success) {
                    markPairingTrusted()
                    runCatching { notificationController.showPairingSucceeded() }
                    _state.value = PairingState.CONNECTING
                    startConnectDiscovery()
                } else {
                    resumePairingDiscovery(
                        AirPairingFailure.PAIRING_REJECTED,
                        "AIR pairing was rejected by the system pairing service"
                    )
                }
            }.onFailure { e ->
                if (pairingAttemptGeneration.get() != attemptGeneration) return@onFailure
                resumePairingDiscovery(
                    AirPairingFailure.PAIRING_CONNECTION_FAILED,
                    "AIR pairing connection failed: ${e::class.java.simpleName}: ${e.message.orEmpty().take(120)}"
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String? = runCatching {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ip = wm?.connectionInfo?.ipAddress?.takeIf { it != 0 } ?: return null
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }.getOrNull()

    private fun getAdbWifiPort(): Int? = runCatching {
        android.provider.Settings.Global.getString(context.contentResolver, "adb_wifi_port")
            ?.toIntOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    private fun preferLocalAdbHost(discoveredHost: String?): String =
        discoveredHost?.takeIf { it == LOCAL_ADB_HOST } ?: LOCAL_ADB_HOST

    private fun isConnectionBlockedByPairingRequirement(): Boolean =
        !isPreviouslyPaired || _state.value in setOf(
            PairingState.DISCOVERING,
            PairingState.AWAITING_CODE,
            PairingState.PAIRING
        )

    private fun failConnection(
        connection: AirAdbClient?,
        failure: AirPairingFailure,
        diagnostic: String
    ) {
        markShellDown(diagnostic, connection, scheduleReconnect = false)
        _failure.value = failure
        logPairingEvent(diagnostic)
        runCatching { notificationController.showConnectionFailure(failure) }
    }

    private fun markPairingRequired(connection: AirAdbClient?, diagnostic: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        connectScanJob?.cancel()
        connectScanJob = null
        connectDiscovery?.stop()
        connectDiscovery = null
        markShellDown(diagnostic, connection, scheduleReconnect = false)
        prefs.edit()
            .remove(KEY_AIR_PAIRED)
            .putBoolean(KEY_AIR_PAIRING_REQUIRED, true)
            .apply()
        _pairingTrustState.value = PairingTrustState.PAIRING_REQUIRED
        _failure.value = AirPairingFailure.PAIRING_REQUIRED
        logPairingEvent("AIR pairing authorization rejected; explicit re-pairing required")
        runCatching { notificationController.showPairingRequired() }
    }

    private fun markPairingTrusted(connectPort: Int? = null) {
        val editor = prefs.edit()
            .putBoolean(KEY_AIR_PAIRED, true)
            .remove(KEY_AIR_PAIRING_REQUIRED)
        if (connectPort != null) editor.putInt("air_last_connect_port", connectPort)
        editor.apply()
        _pairingTrustState.value = PairingTrustState.PAIRED
    }

    fun notifyPairingRequiredIfNeeded(): Boolean {
        if (!isPairingRequired) return false
        runCatching { notificationController.showPairingRequired() }
        return true
    }

    private fun markShellReady(probeOutput: String) {
        shellHealthState = ShellHealthState.READY
        lastShellFailure = ""
        lastShellCheckedAtMs = System.currentTimeMillis()
        lastShellProbeOutput = probeOutput.take(800)
    }

    private fun stopConnectAttempt() {
        reconnectJob?.cancel()
        reconnectJob = null
        connectScanJob?.cancel()
        connectScanJob = null
        connectDiscovery?.stop()
        connectDiscovery = null
    }

    private fun markShellDown(
        message: String,
        connection: AirAdbClient? = activeConnection,
        scheduleReconnect: Boolean = true
    ) {
        shellHealthState = ShellHealthState.DOWN
        lastShellFailure = message.take(240)
        lastShellCheckedAtMs = System.currentTimeMillis()
        lastShellProbeOutput = ""
        activeConnection?.runCatching { close() }
        if (connection !== activeConnection) {
            connection?.runCatching { close() }
        }
        activeConnection = null
        activeEndpoint = null
        _state.value = PairingState.IDLE
        if (scheduleReconnect) scheduleReconnectIfPaired()
    }

    private fun scheduleReconnectIfPaired(delayMs: Long = RECONNECT_DELAY_MS) {
        if (!isPreviouslyPaired || !isWifiTransportAvailable() || reconnectJob?.isActive == true) return
        reconnectJob = appScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (
                _state.value != PairingState.IDLE ||
                !isPreviouslyPaired ||
                !isWifiTransportAvailable()
            ) return@launch
            ensureWirelessDebuggingEnabled()
            _state.value = PairingState.CONNECTING
            debugLogAppender.appendEvent("AIR reconnect attempt started after ${delayMs}ms")
            startConnectDiscovery()
        }
    }

    private suspend fun doConnect(host: String, port: Int, source: String) = connectionMutex.withLock {
        check(!isConnectionBlockedByPairingRequirement()) {
            "ADB pairing authorization must be restored before reconnecting"
        }
        if (isShellReady) {
            return
        }
        val adbKey = loadOrCreateAdbKey()
        activeConnection?.runCatching { close() }
        activeConnection = null
        activeEndpoint = null
        val endpoint = ActiveAirEndpoint(host = host, port = port, source = source)
        val connection = AirAdbClient(host, port, adbKey)
        try {
            connection.connect()
        } catch (error: Throwable) {
            if (AirAdbConnectionFailurePolicy.requiresPairing(error)) {
                markPairingRequired(
                    connection,
                    "ADB authorization rejected: ${error.message.orEmpty().take(120)}"
                )
            } else {
                connection.runCatching { close() }
            }
            throw error
        }
        val healthOutput = runCatching {
            connection.shell(AIR_HEALTH_CHECK_COMMAND)
        }.getOrElse { error ->
            failConnection(
                connection,
                AirPairingFailure.ADB_HEALTH_CHECK_FAILED,
                "ADB health check failed: ${error::class.java.simpleName}: ${error.message.orEmpty().take(120)}"
            )
            throw error
        }
        if (!healthOutput.contains(AIR_HEALTH_CHECK_MARKER)) {
            failConnection(
                connection,
                AirPairingFailure.ADB_HEALTH_RESPONSE_MISSING,
                "ADB health check did not return the ready marker"
            )
            throw IllegalStateException("ADB health check did not return ready marker")
        }
        val identityOutput = runCatching {
            connection.shell("id")
        }.getOrElse { error ->
            failConnection(
                connection,
                AirPairingFailure.ADB_IDENTITY_CHECK_FAILED,
                "ADB shell identity check failed: ${error::class.java.simpleName}: ${error.message.orEmpty().take(120)}"
            )
            throw error
        }
        if (!identityOutput.contains("uid=")) {
            failConnection(
                connection,
                AirPairingFailure.ADB_IDENTITY_RESPONSE_MISSING,
                "ADB shell identity check did not return uid"
            )
            throw IllegalStateException("ADB shell identity check did not return uid")
        }
        val packageName = context.packageName
        val permissionOutput = runCatching {
            connection.shell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            connection.shell(
                "cmd package check-permission android.permission.WRITE_SECURE_SETTINGS $packageName 0"
            )
        }.getOrDefault("")
        if (permissionOutput.contains("granted", ignoreCase = true) || hasSecureSettingsPermission()) {
            prefs.edit().putBoolean("air_secure_settings_granted", true).apply()
        }
        activeConnection = connection
        activeEndpoint = ActiveAirEndpoint(
            host = endpoint.host,
            port = endpoint.port,
            source = endpoint.source,
            endpointId = endpoint.endpointId
        )
        shellSessionGeneration += 1L
        markShellReady("$healthOutput\n$identityOutput")
        connectDiscovery?.stop()
        connectDiscovery = null
        connectScanJob?.cancel()
        connectScanJob = null
        // 记住本次成功端口，仅作为同一无线调试 TLS 服务仍存活时的快速候选；WiFi 断开或切换后
        // Android 会关闭旧服务，必须等待 WiFi 恢复并由 Settings/mDNS 取得当前端口。
        markPairingTrusted(connectPort = port)
        ensureWirelessDebuggingEnabled()
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        _failure.value = null
        _state.value = PairingState.CONNECTED
    }

    private fun startConnectDiscovery() {
        if (isConnectionBlockedByPairingRequirement()) return
        if (!isWifiTransportAvailable()) {
            _state.value = PairingState.IDLE
            return
        }
        // 优先：从 Settings.Global 读取 connect 端口（Android 11+ 有效），再尝试标准 adb tcpip 端口。
        connectDiscovery?.stop()
        connectDiscovery = AirMdnsDiscovery(
            context = context,
            appScope = appScope,
            serviceType = TLS_CONNECT_SERVICE,
            onResolved = { host, port ->
                if (
                    _state.value == PairingState.CONNECTED ||
                    isConnectionBlockedByPairingRequirement()
                ) return@AirMdnsDiscovery
                appScope.launch(Dispatchers.IO) {
                    runCatching { doConnect(preferLocalAdbHost(host), port, "mdns") }
                        .onFailure {
                            if (
                                !isConnectionBlockedByPairingRequirement() &&
                                _state.value != PairingState.CONNECTED
                            ) {
                                val failure = AirPairingFailure.ADB_CONNECTION_FAILED
                                _failure.value = failure
                                logPairingEvent(
                                    "ADB connection failed: ${it::class.java.simpleName}: ${it.message.orEmpty().take(120)}"
                                )
                                runCatching { notificationController.showConnectionFailure(failure) }
                                _state.value = PairingState.IDLE
                            }
                        }
                }
            },
            onDiagnostic = { detail -> debugLogAppender.appendEvent("AIR connect mDNS: $detail") }
        ).also { it.start() }

        connectScanJob?.cancel()
        connectScanJob = appScope.launch(Dispatchers.IO) {
            val lastPort = prefs.getInt("air_last_connect_port", -1).takeIf { it in 1..65535 }
            val ports = AirAdbPortPolicy.connectPorts(lastPort = lastPort, settingsPort = getAdbWifiPort())
            if (ports.isNotEmpty()) {
                val hosts = listOfNotNull(LOCAL_ADB_HOST, getWifiIpAddress()).distinct()
                for (port in ports) {
                    for (host in hosts) {
                        if (
                            _state.value == PairingState.CONNECTED ||
                            isConnectionBlockedByPairingRequirement()
                        ) return@launch
                        val attempt = runCatching { doConnect(host, port, "port_scan") }
                        if (attempt.isSuccess || isPairingRequired) return@launch
                    }
                }
                // 端口候选失败时不立即中止，继续等 mDNS 接力
            }
            // 等 mDNS 兜底（最多 20 秒）
            repeat(20) {
                delay(1_000)
                if (
                    _state.value == PairingState.CONNECTED ||
                    isConnectionBlockedByPairingRequirement()
                ) return@launch
            }
            if (!isConnectionBlockedByPairingRequirement() && _state.value != PairingState.CONNECTED) {
                val failure = AirPairingFailure.ADB_ENDPOINT_NOT_FOUND
                _failure.value = failure
                logPairingEvent("ADB connect discovery timed out without a usable endpoint")
                runCatching { notificationController.showConnectionFailure(failure) }
                connectDiscovery?.stop()
                connectDiscovery = null
                _state.value = PairingState.IDLE
                if (isWifiTransportAvailable()) {
                    debugLogAppender.appendEvent("AIR reconnect retry scheduled after connect discovery timeout")
                    scheduleReconnectIfPaired(CONNECT_RETRY_DELAY_MS)
                }
            }
        }
    }

    fun shell(command: String, service: String = "shell"): String {
        val conn = activeConnection ?: run {
            markShellDown("AIR shell disconnected: no active ADB connection")
            return "[AIR_DISCONNECTED]"
        }
        return runCatching { conn.shell(command, service) }
            .getOrElse {
                val message = "AIR shell error: ${it.message.orEmpty().ifBlank { it::class.java.simpleName }}"
                markShellDown(message, conn)
                "[AIR_ERROR] ${it.message}"
            }
    }

    /**
     * 带硬超时的 shell：ADB over TLS 的 socket 读在弱网/异常页（如反爬验证页）下可能长时间阻塞，
     * 截图等对时延敏感的调用用此版本兜底，超时返回 [AIR_TIMEOUT]，避免整个执行循环被一次截图挂死几十秒。
     */
    fun shell(command: String, timeoutMs: Long, service: String = "shell"): String {
        if (timeoutMs <= 0) return shell(command, service)
        val conn = activeConnection ?: run {
            markShellDown("AIR shell disconnected: no active ADB connection")
            return "[AIR_DISCONNECTED]"
        }
        val future = shellTimeoutExecutor.submit<String> { conn.shell(command, service) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            markShellDown("AIR shell timeout after ${timeoutMs}ms", conn)
            "[AIR_TIMEOUT] shell exceeded ${timeoutMs}ms"
        } catch (e: Exception) {
            markShellDown("AIR shell error: ${e.cause?.message ?: e.message}", conn)
            "[AIR_ERROR] ${e.cause?.message ?: e.message}"
        }
    }

    fun disconnect() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        stopConnectAttempt()
        queuedPairCode = null
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        pairingDiscoveryRefreshJob?.cancel()
        pairingDiscoveryRefreshJob = null
        pairingDiscovery?.stop()
        pairingDiscovery = null
        markShellDown("AIR bridge disconnected", scheduleReconnect = false)
        notificationController.cancel()
    }

    fun resetPairing() {
        disconnect()
        prefs.edit()
            .remove(KEY_AIR_PAIRED)
            .remove(KEY_AIR_PAIRING_REQUIRED)
            .remove("air_secure_settings_granted")
            .remove("air_last_connect_port")
            .apply()
        privateKeyFile().delete()
        publicKeyFile().delete()
        _pairingTrustState.value = PairingTrustState.UNPAIRED
        _failure.value = null
    }

    fun startRequiredPairing(): Boolean {
        if (!isPairingRequired) return false
        val notificationAvailability = notificationController.availability()
        if (notificationAvailability != AirPairingNotificationAvailability.AVAILABLE) {
            _failure.value = pairingNotificationFailure(notificationAvailability)
            return false
        }
        disconnect()
        return startDiscovery()
    }
}
