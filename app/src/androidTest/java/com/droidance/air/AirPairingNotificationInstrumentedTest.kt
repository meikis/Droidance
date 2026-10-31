// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.air

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.agent.debug.DebugLogAppender
import com.droidance.core.device.air.AirModeNotificationController
import com.droidance.core.device.air.AirModePairingManager
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs with SettingsRegressionTestRunner in its separate package to preserve user pairing state. */
@RunWith(AndroidJUnit4::class)
class AirPairingNotificationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val log = object : DebugLogAppender {
        override fun appendEvent(event: String) = Unit
        override fun appendPersistentEvent(stream: String, event: String) = Unit
    }
    private val controller = AirModeNotificationController(context, log)
    private var posted = false

    @Before
    fun prepare() {
        assumeTrue("Use the separate regression package", context.packageName == "com.droidance.repositorytest")
        assumeTrue("Allow notifications for the regression package before acceptance", manager.areNotificationsEnabled())
        assumeTrue(manager.activeNotifications.none { it.id == AirModeNotificationController.NOTIFICATION_ID })
    }

    @After
    fun restore() {
        if (posted) controller.cancel()
    }

    @Test
    fun discoveryAlertsOnlyWhenThePairingServiceAppearsAndRefreshesSilently() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pairing = AirModePairingManager(context, scope, controller, log)
        val nsd = context.getSystemService(NsdManager::class.java)
        val registered = CountDownLatch(1)
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceRegistered(service: NsdServiceInfo) { registered.countDown() }
            override fun onServiceUnregistered(service: NsdServiceInfo) = Unit
        }
        try {
            assertTrue(pairing.startDiscovery())
            posted = true
            val prepared = awaitNotification { it.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY }
            assertTrue(prepared.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(AirModePairingManager.PairingState.DISCOVERING, pairing.state.value)
            assertPairingInput(prepared)

            // Advertise a local test port just as the system does when its code dialog opens.
            // No code is submitted and no pairing protocol or ADB connection is attempted.
            ServerSocket(0).use { socket ->
                val service = NsdServiceInfo().apply {
                    serviceName = "droidance-notification-test-${UUID.randomUUID()}"
                    serviceType = "_adb-tls-pairing._tcp."
                    port = socket.localPort
                }
                nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
                assertTrue("Local pairing service must register", registered.await(5, TimeUnit.SECONDS))
                val ready = awaitNotification { it.groupAlertBehavior == Notification.GROUP_ALERT_ALL }
                assertFalse(ready.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                assertEquals(Notification.GROUP_ALERT_ALL, ready.groupAlertBehavior)
                assertPairingInput(ready)

                assertTrue(pairing.refreshPairingNotificationIfAwaiting())
                val refreshed = awaitNotification { it.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY }
                assertTrue(refreshed.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                assertPairingInput(refreshed)
            }
        } finally {
            runCatching { nsd.unregisterService(listener) }
            pairing.stopDiscovery()
            scope.cancel()
        }
    }

    private fun assertPairingInput(notification: Notification) {
        assertEquals(AirModeNotificationController.CHANNEL_ID, notification.channelId)
        assertTrue(manager.getNotificationChannel(notification.channelId).importance >= NotificationManager.IMPORTANCE_HIGH)
        assertEquals(AirModeNotificationController.KEY_PAIRING_CODE, notification.actions.single().remoteInputs.single().resultKey)
        assertNotNull(notification.actions.single().actionIntent)
    }

    private fun awaitNotification(predicate: (Notification) -> Boolean): Notification {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            val notification = manager.activeNotifications.singleOrNull {
                it.id == AirModeNotificationController.NOTIFICATION_ID
            }?.notification
            if (notification != null && predicate(notification)) return notification
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Pairing notification did not reach the expected alert state")
    }
}
