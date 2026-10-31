// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.DroidanceApp
import com.droidance.MainActivity
import com.droidance.core.agent.AgentState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tests real notification delivery and Activity visibility without calling a model. */
@RunWith(AndroidJUnit4::class)
class TaskNotificationVisibilityInstrumentedTest {
    @Test fun taskNoticesOnlyPostOutsideTheHostAndNeverResumeItsActivity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DroidanceApp>()
        val manager = NotificationManagerCompat.from(app)
        val entryPoint = EntryPointAccessors.fromApplication(app, KeepAliveEntryPoint::class.java)
        val surface = entryPoint.runtimeSurfaceController()
        val ids = setOf(TaskExecutionService.TASK_STARTED_NOTIFICATION_ID,
            TaskExecutionService.TASK_DONE_NOTIFICATION_ID, TaskExecutionService.MANUAL_TAKEOVER_NOTIFICATION_ID)
        val createdChannels = listOf(TaskExecutionService.TASK_DONE_CHANNEL_ID,
            TaskExecutionService.MANUAL_TAKEOVER_CHANNEL_ID).filter { manager.getNotificationChannel(it) == null }
        assumeTrue("Do not replace existing user notices", manager.activeNotifications.none { it.id in ids })
        assumeTrue("Notifications must be enabled for delivery acceptance", manager.areNotificationsEnabled())
        assumeTrue("Do not cover a user's open virtual viewer", !surface.isViewerExpanded)
        val notices = listOf<Pair<Int, () -> Unit>>(
            TaskExecutionService.TASK_STARTED_NOTIFICATION_ID to {
                assertTrue(TaskExecutionService.notifyAutomationStarted(app, "Test task"))
            },
            TaskExecutionService.TASK_DONE_NOTIFICATION_ID to {
                assertTrue(TaskExecutionService.notifyTaskCompleted(app, summary = "Completed"))
            },
            TaskExecutionService.TASK_DONE_NOTIFICATION_ID to {
                assertTrue(TaskExecutionService.notifyTaskInterrupted(app, reason = "Interrupted"))
            },
            TaskExecutionService.TASK_DONE_NOTIFICATION_ID to {
                assertTrue(TaskExecutionService.notifyTaskDone(app, "Final reply"))
            },
            TaskExecutionService.MANUAL_TAKEOVER_NOTIFICATION_ID to {
                TaskExecutionService.notifyManualTakeoverRequired(app)
            },
            TaskExecutionService.MANUAL_TAKEOVER_NOTIFICATION_ID to {
                TaskExecutionService.notifyRuntimePermissionRequired(app, "Permission required")
            }
        )
        ActivityScenario.launch(MainActivity::class.java).use { host ->
            host.onActivity {
                assertEquals("A running task must not be interrupted", AgentState.IDLE, it.agentRuntime.state.value)
                assertFalse(it.agentRuntime.isMobileUseTaskActive.value)
            }
            try {
                await { app.foregroundState.isInForeground }
                notices.forEach { (_, post) -> post() }
                delay(300)
                assertTrue("Foreground notices must be suppressed", manager.activeNotifications.none { it.id in ids })
                assertEquals(Lifecycle.State.RESUMED, host.state)

                host.moveToState(Lifecycle.State.CREATED)
                await { !app.foregroundState.isInForeground }
                // A viewer can remain visible after cross-display focus pauses it.
                surface.setViewerExpanded(true)
                try {
                    notices.forEach { (_, post) -> post() }
                    delay(300)
                    assertTrue("A visible viewer must suppress notices", manager.activeNotifications.none { it.id in ids })
                } finally {
                    surface.setViewerExpanded(false)
                }

                for ((id, post) in notices) {
                    post()
                    await { manager.activeNotifications.any { it.id == id } }
                    val notification = manager.activeNotifications.single { it.id == id }.notification
                    assertEquals("Starting a task first must not lower result alert importance",
                        NotificationManagerCompat.IMPORTANCE_HIGH,
                        manager.getNotificationChannel(notification.channelId)?.importance)
                    assertNull("Notices must not launch a full-screen Activity", notification.fullScreenIntent)
                    assertNotNull("Opening the host requires a user notification tap", notification.contentIntent)
                    assertNotNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
                    delay(300)
                    assertFalse("Posting must leave Droidance in the background", app.foregroundState.isInForeground)
                    assertEquals(Lifecycle.State.CREATED, host.state)
                    manager.cancel(id)
                    await { manager.activeNotifications.none { it.id == id } }
                }
            } finally {
                ids.forEach(manager::cancel)
                createdChannels.forEach(manager::deleteNotificationChannel)
            }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(25)
    }
}
