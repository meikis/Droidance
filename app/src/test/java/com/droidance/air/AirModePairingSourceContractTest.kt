// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.air

import com.droidance.core.device.air.*

import com.droidance.air.*

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirModePairingSourceContractTest {
    @Test
    fun awaitingPairingCodeShowsNotificationInput() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()

        assertTrue(
            "等待配对码时应同时弹出通知输入入口，避免用户离开设置页后丢失配对流程",
            source.contains("notificationController.showPairingRequest")
        )
        assertTrue(source.contains("if (postResult.posted)"))
        assertTrue(source.contains("_state.value = PairingState.AWAITING_CODE"))
        assertTrue(source.contains("stopDiscovery()"))
    }

    @Test
    fun pairingNotificationVisibilityIsCheckedBeforeDiscoveryAndAwaitingCode() {
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val notification = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()
        val navigator = projectFile("app/src/main/java/com/droidance/settings/SystemSettingsNavigator.kt").readText()

        assertTrue(notification.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(notification.contains("areNotificationsEnabled()"))
        assertTrue(notification.contains("getNotificationChannel(CHANNEL_ID)"))
        assertTrue(notification.contains("channel.importance < NotificationManager.IMPORTANCE_HIGH"))
        assertTrue(notification.contains("fun availability(): AirPairingNotificationAvailability"))
        assertTrue(notification.contains("data class PairingNotificationPostResult"))
        assertTrue(manager.contains("notificationController.availability()"))
        assertTrue(manager.contains("pairingNotificationFailure("))
        assertTrue(screen.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue(screen.contains("airPairingNotificationAvailability"))
        assertTrue(viewModel.contains("openAirPairingNotificationSettings"))
        assertTrue(navigator.contains("Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS"))
        assertTrue(manager.contains("fun refreshPairingNotificationIfAwaiting()"))
        assertTrue(viewModel.contains("airModePairingManager.refreshPairingNotificationIfAwaiting()"))
    }

    @Test
    fun firstInstallNotificationGrantResumesThePendingPairingActionExactlyOnce() {
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val notification = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()

        val availability = notification.substringAfter("fun availability()")
            .substringBefore("private fun builder")
        assertTrue(
            "The pairing channel must exist before a first-install permission denial opens channel settings",
            availability.indexOf("ensureChannel()") < availability.indexOf("Manifest.permission.POST_NOTIFICATIONS")
        )
        assertTrue(screen.contains("viewModel.prepareAirPairingAfterNotificationAccess("))
        assertTrue(screen.contains("viewModel.onAirPairingNotificationPermissionResult(granted)"))
        assertTrue(screen.contains("viewModel.resumePendingAirPairingAfterNotificationAccess()"))
        assertTrue(viewModel.contains("pendingAirPairingStart"))
        assertTrue(viewModel.contains("fun prepareAirPairingAfterNotificationAccess("))
        assertTrue(viewModel.contains("fun resumePendingAirPairingAfterNotificationAccess()"))
        assertTrue(viewModel.contains("pendingAirPairingStart = null"))
        assertTrue(manager.contains("AIR pairing discovery started"))
    }

    @Test
    fun repairIsOnlyOfferedForExplicitPairingTrustLoss() {
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val client = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirAdbClient.kt").readText()
        val notification = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()
        val backendProbe = projectFile(
            "app/src/main/java/com/droidance/agent/attempt/AndroidExecutionAttemptBackendProbe.kt"
        ).readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()

        assertTrue(manager.contains("enum class PairingTrustState"))
        assertTrue(manager.contains("val pairingTrustState: StateFlow<PairingTrustState>"))
        assertTrue(manager.contains("val isPairingRequired"))
        assertTrue(manager.contains("AirAdbConnectionFailurePolicy.requiresPairing"))
        assertTrue(manager.contains("fun startRequiredPairing()"))
        assertTrue(manager.contains("markPairingRequired("))
        assertTrue(manager.contains("markPairingTrusted()"))
        assertFalse(manager.contains("val shouldOfferRepair"))
        assertTrue(client.contains("AirAdbAuthorizationException"))
        assertTrue(notification.contains("fun showPairingRequired("))
        assertTrue(backendProbe.contains("airModePairingManager.notifyPairingRequiredIfNeeded()"))
        assertTrue(viewModel.contains("startRequiredAirPairing"))
        assertTrue(screen.contains("airPairingRequired"))
        assertTrue(screen.contains("settings_air_repair"))
        assertFalse(screen.contains("airRepairRecommended"))
    }

    @Test
    fun pairedAirBridgeHasExplicitReconnectEntryPoint() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()

        assertTrue(
            "已配对 AIR 通道需要一个幂等重连入口，供 App 启动、网络恢复和保活服务调用",
            source.contains("fun ensureConnectedIfPaired()")
        )
    }

    @Test
    fun firstAirConnectionGrantsSecureSettingsAndLaterRestoresWirelessDebugging() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(source.contains("pm grant \$packageName android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(source.contains("cmd package check-permission android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(source.contains("Settings.Global.putInt(context.contentResolver, \"adb_wifi_enabled\", 1)"))
        assertTrue(source.contains("ensureWirelessDebuggingEnabled()"))
        assertTrue(source.contains("scheduleReconnectIfPaired()"))
    }

    @Test
    fun connectSuccessRequiresAdbHealthCheckAndFailureReturnsToReconnectableState() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()

        assertTrue(
            "ADB 连接成功前应执行 shell 健康检查，避免静默保持已配对但不可用",
            source.contains("droidance_air_ready") && source.contains(".shell(")
        )
        assertTrue(
            "健康检查失败后应关闭连接并回到 IDLE，允许用户或自动流程重新连接",
            source.contains("failConnection") &&
                source.contains("activeConnection = null") &&
                source.contains("_state.value = PairingState.IDLE")
        )
    }

    @Test
    fun connectedAirBridgeTracksRealShellHealthAndDropsOnShellFailure() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val shellDown = source.substringAfter("private fun markShellDown(")
            .substringBefore("private fun scheduleReconnectIfPaired(")

        assertTrue("AIR shell readiness should be explicit, not inferred only from pairing state", source.contains("enum class ShellHealthState"))
        assertTrue("Upper layers need a stable shell readiness query", source.contains("val isShellReady"))
        assertTrue("Debug export needs a shell health snapshot", source.contains("fun shellHealthSnapshot()"))
        assertTrue("Successful connect should mark shell health ready only after probes pass", source.contains("markShellReady("))
        assertTrue("Broken pipe, timeout, and disconnect must mark the AIR shell down", source.contains("markShellDown("))
        assertTrue("Shell-down handling must close the stale ADB socket", source.contains("activeConnection?.runCatching { close() }"))
        assertTrue("Shell-down handling must leave CONNECTED so callers stop using the dead bridge", source.contains("_state.value = PairingState.IDLE"))
        assertTrue("Timed shell failures must be represented as AIR channel failures", source.contains("[AIR_TIMEOUT]"))
        assertFalse("A transport drop must not erase the persisted pairing", shellDown.contains("KEY_AIR_PAIRED"))
        assertFalse("A transport drop must not request re-pairing", shellDown.contains("PairingTrustState.PAIRING_REQUIRED"))
        assertTrue("A transport drop should remain eligible for automatic reconnect", shellDown.contains("scheduleReconnectIfPaired()"))
    }

    @Test
    fun activeAirConnectionIsEndpointVisibleAndSingleFlight() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()

        assertTrue(
            "AIR health export must include the active wireless debugging endpoint so duplicate adb transports can be diagnosed",
            source.contains("data class ActiveAirEndpoint") &&
                source.contains("val activeEndpoint: ActiveAirEndpoint?") &&
                source.contains("activeEndpoint = ActiveAirEndpoint(") &&
                source.contains("activeEndpoint = null")
        )
        assertTrue(
            "mDNS and port-scan connect attempts must be single-flight so Droidance keeps one active AIR connection",
            source.contains("connectionMutex") &&
                source.contains("Mutex()") &&
                source.contains("withLock")
        )
    }

    @Test
    fun airPairingKeyIsNotRestoredByAndroidBackup() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(
            "AIR pairing keys/prefs must not be restored silently after reinstall; disable backup or exclude those files explicitly",
            manifest.contains("""android:allowBackup="false"""") ||
                manifest.contains("android:dataExtractionRules") ||
                manifest.contains("android:fullBackupContent")
        )
        assertFalse(
            "android:allowBackup=true makes AIR re-pairing state ambiguous after reinstall",
            manifest.contains("""android:allowBackup="true"""")
        )
    }

    @Test
    fun airPairingUsesTheSameAdbKeyForTlsAndPeerInfo() {
        val pairingClient = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirPairingClient.kt").readText()
        val keySource = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirAdbKey.kt").readText()
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()

        assertTrue(
            "无线调试配对的 TLS client certificate 必须来自同一套 ADB RSA key，不能另起 AndroidKeyStore TLS key",
            !pairingClient.contains("AndroidKeyStore") &&
                !pairingClient.contains("TLS_KEY_ALIAS") &&
                pairingClient.contains("adbKey.sslContext") &&
                pairingClient.contains("adbKey.publicKeyBytes")
        )
        assertTrue(
            "ADB public key 必须按 ADB AUTH 格式带 NUL 结尾，和 TLS certificate 对应同一私钥",
            keySource.contains("publicKeyBytes") &&
                keySource.contains("0.toByte()") &&
                manager.contains("AirPairingClient(host, port, code, adbKey)")
        )
    }

    @Test
    fun airConnectionSupportsWirelessDebuggingStlsUpgrade() {
        val client = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirAdbClient.kt").readText()
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()

        assertTrue(
            "Android 11+ 无线调试 connect 端口会先要求 A_STLS，AIR 连接客户端必须升级到同一 ADB key 的 TLS socket",
            client.contains("A_STLS") &&
                client.contains("adbKey.sslContext.socketFactory") &&
                client.contains("startHandshake()")
        )
        assertTrue(
            "AIR shell 连接不能继续依赖 dadb 1.2.7，因为它没有实现无线调试 A_STLS 升级",
            manager.contains("AirAdbClient") && !manager.contains("dadb.Dadb.create")
        )
    }

    @Test
    fun airMdnsDiscoveryMatchesAladdinRawMdnsFallback() {
        val discovery = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirMdnsDiscovery.kt").readText()
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()

        assertTrue(
            "mDNS 发现应使用 resolve 队列，避免 NsdManager 同时 resolve 多个服务导致丢服务",
            discovery.contains("ArrayDeque<NsdServiceInfo>") &&
                discovery.contains("processQueue()")
        )
        assertTrue(
            "需要 raw mDNS fallback：MulticastLock + 224.0.0.251:5353，避免只依赖 NsdManager",
            discovery.contains("createMulticastLock") &&
                discovery.contains("MulticastSocket") &&
                discovery.contains("224.0.0.251") &&
                discovery.contains("5353")
        )
        assertTrue(
            "不要再读 /proc/net/tcp6 做端口差值检测，真机上会触发 SELinux 拒绝且不如 mDNS 稳定",
            !manager.contains("/proc/net/tcp6")
        )
        assertTrue("mDNS failures must be diagnosable", discovery.contains("onDiagnostic"))
        assertFalse(
            "NsdManager startup failures must not be swallowed",
            discovery.contains("onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}")
        )
    }

    @Test
    fun firstPairingDiscoveryRetriesEndpointReadinessAndRecyclesTheListener() {
        val discovery = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirMdnsDiscovery.kt").readText()
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()

        assertTrue(discovery.contains("endpointProbeJob"))
        assertTrue(discovery.contains("endpoint_not_ready"))
        assertTrue(discovery.contains("delay(ENDPOINT_READY_RETRY_MS)"))
        assertTrue(discovery.contains("endpoint_probe_exhausted"))
        assertTrue(discovery.contains("nsdDiscoveryRequested"))
        assertTrue(discovery.contains("if (nsdDiscoveryRequested || registered)"))
        assertTrue(manager.contains("pairingDiscoveryRefreshJob"))
        assertTrue(manager.contains("PAIRING_DISCOVERY_REFRESH_MS"))
        assertTrue(manager.contains("discovery_refresh"))
    }

    @Test
    fun failedPairingClearsStaleEndpointAndRestartsDiscovery() {
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val notification = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()

        assertTrue(
            "配对失败后必须清掉旧 host/port，否则用户重新打开配对码弹窗后仍会连接过期端口",
            manager.contains("clearPendingPairingEndpoint()") &&
                manager.contains("pendingHost = null") &&
                manager.contains("pendingPort = 0")
        )
        assertTrue(
            "配对失败或超时后应重启 pairing mDNS/raw discovery，不能只把状态文字改回 DISCOVERING",
            manager.contains("resumePairingDiscovery(") &&
                manager.contains("startPairingDiscovery()")
        )
        val resolved = manager.substringAfter("private fun installPairingDiscovery()")
            .substringBefore("fun stopDiscovery()")
        val failure = manager.substringAfter("private fun resumePairingDiscovery(")
            .substringBefore("private fun doExecutePairing(")
        val failureNotice = notification.substringAfter("fun showPairingFailure(")
            .substringBefore("fun showConnectionFailure(")
        assertFalse(resolved.contains("else if (_failure.value == null)"))
        assertTrue(resolved.contains("moveToAwaitingCode()"))
        assertTrue(failure.contains("showPairingFailure(failure, allowRetry = true)"))
        assertTrue(failureNotice.contains("setProgress(0, 0, false)"))
        assertTrue(failureNotice.contains("showPairingRequest(failure)"))
        assertTrue(manager.contains("pairingAttemptGeneration.get() != attemptGeneration"))
    }

    @Test
    fun submittedPairingCodeRefreshesDynamicEndpointBeforeExecution() {
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val request = manager.substringAfter("fun requestPairing(")
            .substringBefore("private fun moveToAwaitingCode")
        val discovery = manager.substringAfter("private fun installPairingDiscovery()")
            .substringBefore("fun stopDiscovery()")

        assertTrue(
            "提交配对码时必须丢弃旧 endpoint 并重新发现动态端口",
            request.contains("queuedPairCode = code") &&
                request.contains("clearPendingPairingEndpoint()") &&
                request.contains("startPairingDiscovery()")
        )
        assertFalse(
            "提交配对码不能直接使用等待阶段缓存的 host/port",
            request.contains("doExecutePairing(host, port, code)")
        )
        assertTrue(
            "重启 discovery 后，迟到的旧 listener 回调必须被代际校验丢弃",
            discovery.contains("generation != pairingDiscoveryGeneration.get()")
        )
    }

    @Test
    fun successfulPairingStopsProgressAndBackgroundConnectionsLeaveResultUntouched() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val succeeded = source.substringAfter("fun showPairingSucceeded() {").substringBefore("\n    }")
        assertFalse(succeeded.contains("cancel()"))
        assertTrue(succeeded.contains("showStatus("))
        assertTrue(succeeded.contains("pairingNotificationActive = false"))
        assertTrue(succeeded.contains("indeterminate = false"))
        assertTrue(succeeded.contains("ongoing = false"))
        val failure = source.substringAfter("fun showConnectionFailure(failure: AirPairingFailure) {")
            .substringBefore("\n    }")
        assertTrue(failure.indexOf("if (!pairingNotificationActive) return") < failure.indexOf("showStatus("))
        val submitted = source.substringAfter("fun showPairingSubmitted() {").substringBefore("\n    }")
        assertTrue(submitted.contains("ongoing = false"))
        val waiting = source.substringAfter("fun showPairingWaitingForService() {").substringBefore("\n    }")
        assertTrue(waiting.contains("ongoing = false"))
        val success = manager.substringAfter("if (success) {").substringBefore("} else {")
        assertTrue(success.indexOf("notificationController.showPairingSucceeded()") >= 0)
        assertTrue(success.indexOf("notificationController.showPairingSucceeded()") < success.indexOf("startConnectDiscovery()"))
        val connect = manager.substringAfter("private suspend fun doConnect(").substringBefore("private fun startConnectDiscovery(")
        assertFalse(connect.contains("notificationController."))
    }

    @Test
    fun notificationPairingCodeSubmissionAlwaysShowsProgressOrFailure() {
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val notification = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()
        val failureMessages = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirPairingFailureMessage.kt").readText()
        val receiver = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingReceiver.kt").readText()
        val strings = projectFile("core/device/src/main/res/values-zh/strings.xml").readText()

        listOf(
            "showPairingSubmitted()",
            "showPairingWaitingForService()",
            "showPairingSucceeded()",
            "showPairingFailure("
        ).forEach { call ->
            assertTrue("AIR notification flow must call $call so notification input never looks ignored", manager.contains(call) || receiver.contains(call))
        }

        listOf(
            "settings_air_notification_submitted_title",
            "settings_air_notification_submitted_text",
            "settings_air_notification_waiting_text",
            "settings_air_notification_succeeded_title",
            "settings_air_notification_succeeded_text",
            "settings_air_notification_failed_title",
            "settings_air_notification_invalid_code_text",
            "settings_air_notification_unavailable_text"
        ).forEach { key ->
            assertTrue(
                "AIR notification presentation should use $key",
                notification.contains("R.string.$key") || failureMessages.contains("R.string.$key")
            )
            assertTrue("Chinese resources should define $key", strings.contains("""name="$key""""))
        }
    }

    @Test
    fun airRuntimePublishesTypedFailuresAndLocalizesOnlyAtPresentationBoundaries() {
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val failure = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirPairingFailure.kt").readText()
        val messages = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirPairingFailureMessage.kt").readText()
        val notification = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()

        assertTrue(failure.contains("enum class AirPairingFailure"))
        assertTrue(manager.contains("val failure: StateFlow<AirPairingFailure?>"))
        assertFalse(manager.contains("_errorMessage"))
        assertFalse(manager.contains("context.getString("))
        assertFalse(manager.contains("import com.droidance.R"))
        assertTrue(messages.contains("fun AirPairingFailure.messageRes(): Int"))
        assertTrue(notification.contains("failure.messageRes()"))
        assertTrue(viewModel.contains("airModePairingManager.failure.collect"))
        assertTrue(screen.contains("stringResource(failure.messageRes())"))
    }

    @Test
    fun airPairingUsesPublicAndroidTlsKeyExporter() {
        val pairingClient = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirPairingClient.kt").readText()

        assertTrue(
            "Android 12+ 已提供公开 SSLSockets.exportKeyingMaterial，不能再反射隐藏 Conscrypt API",
            pairingClient.contains("android.net.ssl.SSLSockets") &&
                pairingClient.contains("SSLSockets.exportKeyingMaterial") &&
                !pairingClient.contains("""Class.forName("com.android.org.conscrypt.Conscrypt")""")
        )
    }

    @Test
    fun airAdbKeyCreatesNulTerminatedPublicKeyTlsContextAndAuthSignature() {
        val dir = Files.createTempDirectory("droidance-air-key").toFile()
        try {
            val key = AirAdbKey.loadOrCreate(File(dir, "air_key"), File(dir, "air_key.pub"))

            assertEquals(0.toByte(), key.publicKeyBytes.last())
            assertTrue(String(key.publicKeyBytes.dropLast(1).toByteArray()).contains(" unknown@unknown"))
            assertNotNull(key.sslContext.socketFactory)
            assertEquals(256, key.signToken(ByteArray(20)).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun appAndKeepAlivePathsTryToReconnectPairedAirBridge() {
        val app = projectFile("app/src/main/java/com/droidance/DroidanceApp.kt").readText()
        val service = projectFile("app/src/main/java/com/droidance/daemon/TaskExecutionService.kt").readText()
        val boot = projectFile("app/src/main/java/com/droidance/daemon/BootReceiver.kt").readText()
        val keepAlive = projectFile("app/src/main/java/com/droidance/daemon/KeepAliveJobService.kt").readText()

        assertTrue(app.contains("airModePairingManager.ensureConnectedIfPaired()"))
        assertTrue(service.contains("airModePairingManager.ensureConnectedIfPaired()"))
        assertTrue(boot.contains("ep.airModePairingManager().ensureConnectedIfPaired()"))
        assertTrue(keepAlive.contains("ep.airModePairingManager().ensureConnectedIfPaired()"))
    }

    @Test
    fun wifiTransportChangesDropOnlyTheSocketAndKeepRetryingTheTrustedBridge() {
        val app = projectFile("app/src/main/java/com/droidance/DroidanceApp.kt").readText()
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val transition = manager.substringAfter("fun onWifiNetworkChanged(")
            .substringBefore("fun hasSecureSettingsPermission()")
        val shellDown = manager.substringAfter("private fun markShellDown(")
            .substringBefore("private fun scheduleReconnectIfPaired(")
        val connectTimeout = manager.substringAfter("// 等 mDNS 兜底（最多 20 秒）")
            .substringBefore("fun shell(command: String")

        assertTrue("AIR must listen to Wi-Fi itself instead of treating cellular availability as recovery", app.contains("NetworkCapabilities.TRANSPORT_WIFI"))
        assertTrue("Wi-Fi availability should trigger a settled transport refresh", app.contains("onAvailable(network: Network)") && app.contains("scheduleAirWifiTransportRefresh"))
        assertTrue("Wi-Fi loss must invalidate a stale loopback ADB socket immediately", app.contains("onLost(network: Network)") && app.contains("notifyAirWifiTransportLost"))
        assertTrue("Overlapping Wi-Fi callbacks must be reduced to the networks that are still active", app.contains("activeWifiNetworks.add(network)") && app.contains("activeWifiNetworks.remove(network)"))
        assertTrue("The manager needs one explicit network-transition entry point", manager.contains("fun onWifiNetworkChanged("))
        assertTrue("Network transitions must close an old ADB connection before reconnecting", transition.contains("markShellDown("))
        assertTrue("Network transitions should cancel obsolete discovery attempts", transition.contains("stopConnectAttempt()"))
        assertFalse("A Wi-Fi transition must never clear the persisted ADB trust", transition.contains("KEY_AIR_PAIRED"))
        assertFalse("A Wi-Fi transition must never request pairing", transition.contains("PairingTrustState.PAIRING_REQUIRED"))
        assertFalse("A generic shell transport failure must never clear pairing", shellDown.contains("KEY_AIR_PAIRED"))
        assertTrue("Reconnect attempts must be gated by a real Wi-Fi transport", manager.contains("isWifiTransportAvailable()"))
        val pairingStart = manager.substringAfter("fun startDiscovery(): Boolean")
            .substringBefore("private fun startPairingDiscovery()")
        assertFalse("First-time pairing must not attempt an unauthorized ADB connection", pairingStart.contains("startConnectDiscovery("))
        val connectStart = manager.substringAfter("private fun startConnectDiscovery()")
        assertTrue("Pairing must be protected before connection discovery checks Wi-Fi", connectStart.indexOf("if (isConnectionBlockedByPairingRequirement()) return") in 0 until connectStart.indexOf("if (!isWifiTransportAvailable())"))
        assertTrue("A timed-out discovery should schedule another bounded retry while Wi-Fi remains available", connectTimeout.contains("scheduleReconnectIfPaired("))
    }

    @Test
    fun reconnectingHasASeparateStateAndNeverShowsPairingCodeInstructions() {
        val manager = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()
        val capabilities = projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue("ADB connect discovery must not reuse the pairing discovery state", manager.contains("enum class PairingState") && manager.contains("CONNECTING"))
        assertTrue("The pairing page should render reconnecting explicitly", screen.contains("PairingState.CONNECTING") && screen.contains("settings_air_reconnecting_hint"))
        assertTrue("The capability summary should distinguish reconnecting from pairing discovery", capabilities.contains("PairingState.CONNECTING") && capabilities.contains("settings_air_state_reconnecting"))
        assertTrue(strings.contains("name=\"settings_air_reconnecting_hint\""))
        assertTrue(strings.contains("name=\"settings_air_state_reconnecting\""))
        assertTrue(
            "The pairing intro must state the honest reconnect boundary",
            strings.contains("已有配对会保留") && strings.contains("连接中断时可重新连接")
        )
    }

    @Test
    fun airPairingWizardUsesSingleWirelessDebuggingAndPairingEntry() {
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()
        val navigator = projectFile("app/src/main/java/com/droidance/settings/SystemSettingsNavigator.kt").readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        assertTrue(viewModel.contains("openWirelessDebuggingSettings"))
        assertTrue(viewModel.contains("openWirelessDebuggingAndStartAirPairing"))
        assertTrue(navigator.contains("Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS"))
        assertTrue(screen.contains("viewModel::openWirelessDebuggingAndStartAirPairing"))
        assertTrue(screen.contains("R.string.settings_air_open_wireless_debugging"))
        assertTrue(strings.contains("""name="settings_air_open_wireless_debugging""""))
        assertFalse("Air row should not expose a separate start-pairing button", screen.contains("onStart: () -> Unit"))
        assertFalse("Air row should not route IDLE unpaired state to a second pairing action", screen.contains("R.string.settings_air_start_pairing"))
    }

    @Test
    fun connectedAirPairingStateIsInformationalInsteadOfDisconnectAction() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()

        assertTrue(screen.contains("AirPairingConnectedStatus()"))
        assertTrue(screen.contains("R.string.settings_air_state_connected"))
        assertFalse("Connected state must not offer a disconnect action", screen.contains("R.string.settings_air_disconnect"))
        assertFalse("Connected state must not share the cancel callback", screen.contains("connected || inProgress -> viewModel::stopAirPairing"))
        assertTrue("In-progress pairing should remain cancellable", screen.contains("inProgress -> viewModel::stopAirPairing"))
    }

    @Test
    fun airPairingNotificationAlertsWithHeadsUpPriorityAndCancelsSilentPlaceholder() {
        val notification = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()

        assertTrue("配对通知应具备最高优先级以支持应用外弹窗通知", notification.contains("PRIORITY_MAX"))
        assertTrue("服务就绪进入 AWAITING_CODE 提醒时应取消静默占位以触发系统浮动弹窗", notification.contains("if (alert || failure != null) notificationManager.cancel(NOTIFICATION_ID)"))
        assertTrue("应用内进入 AWAITING_CODE 应支持弹窗输入", screen.contains("AirPairingCodeDialog"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
