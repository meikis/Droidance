// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.localization

import android.app.LocaleManager
import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.app.LocaleManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.DroidanceApp
import com.droidance.MainActivity
import com.droidance.R
import com.droidance.config.AppLanguage
import com.droidance.config.LanguageManager
import com.droidance.core.device.air.AirModeNotificationController
import com.droidance.core.device.presentation.LocalizedNotifications
import com.droidance.daemon.TaskExecutionService
import com.droidance.ui.overlay.RuntimeIslandPresentation
import com.droidance.ui.overlay.RuntimeSurfaceBitmapRenderer
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real configuration changes and notifications; never requests a model or performs device tasks. */
@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<DroidanceApp>()
    private val language = LanguageManager(context)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var original: AppLanguage
    private val notificationManager get() = NotificationManagerCompat.from(context)
    private val testNotificationId = 198321
    private val createdNotifications = mutableSetOf<Int>()

    @Before fun launch() {
        original = language.getPreference()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun restore() {
        createdNotifications.forEach { notificationManager.cancel(it) }
        if (::scenario.isInitialized) {
            select(original)
            scenario.close()
        }
    }

    @Test fun systemSettingsAndAppPickerShareOnePreferenceAndFollowSystem() {
        select(AppLanguage.ENGLISH)
        assertEquals("en", LocaleManagerCompat.getApplicationLocales(context)[0]?.language)
        assertEquals(expected("en", R.string.settings_language), activityString(R.string.settings_language))
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.runOnMainSync {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh")
            }
            awaitLanguage("zh")
            assertEquals(AppLanguage.SIMPLIFIED_CHINESE, language.getPreference())
        } else {
            select(AppLanguage.SIMPLIFIED_CHINESE)
        }
        assertEquals(expected("zh", R.string.settings_language), activityString(R.string.settings_language))
        select(AppLanguage.SYSTEM)
        assertTrue(LocaleManagerCompat.getApplicationLocales(context).isEmpty)
        assertEquals(AppLanguage.SYSTEM, language.getPreference())
        screenshot("follow-system.png")
    }

    @Test fun postedNoticesAndRemoteInputRefreshWithoutChangingUserContentOrExpiry() {
        val ids = setOf(AirModeNotificationController.NOTIFICATION_ID, TaskExecutionService.TASK_DONE_NOTIFICATION_ID)
        assumeTrue("Do not replace an existing user notification", notificationManager.activeNotifications.none { it.id in ids })
        assumeTrue("Notification permission must be granted for acceptance", notificationManager.areNotificationsEnabled())
        select(AppLanguage.ENGLISH)
        val air = context.airNotifications
        assertTrue(air.showPairingRequest().posted)
        createdNotifications += AirModeNotificationController.NOTIFICATION_ID
        val originalText = "User content / 用户原文"
        // Task notifications are posted only outside Droidance. Return to the same
        // Activity afterwards to exercise live localization of the existing notice.
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        awaitCondition { !context.foregroundState.isInForeground }
        assertTrue(TaskExecutionService.notifyTaskCompleted(context, summary = originalText))
        createdNotifications += TaskExecutionService.TASK_DONE_NOTIFICATION_ID
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        val localized = ContextCompat.getContextForLanguage(context)
        val expiring = NotificationCompat.Builder(localized, AirModeNotificationController.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setTimeoutAfter(30_000)
            .apply { LocalizedNotifications.setText(this, localized, R.string.task_completed_notification_title) }
            .build()
        notificationManager.notify(testNotificationId, expiring)
        createdNotifications += testNotificationId
        val before = active(testNotificationId)
        val deadline = before.postTime + before.notification.timeoutAfter
        select(AppLanguage.SIMPLIFIED_CHINESE)
        awaitCondition {
            active(AirModeNotificationController.NOTIFICATION_ID).notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() ==
                expected("zh", com.droidance.core.device.R.string.settings_air_notification_title)
        }
        val pairing = active(AirModeNotificationController.NOTIFICATION_ID).notification
        assertEquals(expected("zh", com.droidance.core.device.R.string.settings_air_notification_action), pairing.actions.single().title.toString())
        assertEquals(expected("zh", com.droidance.core.device.R.string.settings_air_notification_input_label), pairing.actions.single().remoteInputs.single().label.toString())
        val done = active(TaskExecutionService.TASK_DONE_NOTIFICATION_ID).notification
        assertEquals(expected("zh", R.string.task_completed_notification_title), done.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(originalText, done.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        LocalizedNotifications.refresh(context, setOf(testNotificationId))
        val after = active(testNotificationId)
        assertTrue("Locale refresh must not extend a notification lifetime", after.postTime + after.notification.timeoutAfter <= deadline + 250)
        assertEquals(before.notification.`when`, after.notification.`when`)
        notificationManager.cancel(testNotificationId)
        LocalizedNotifications.refresh(context, setOf(testNotificationId))
        assertFalse(notificationManager.activeNotifications.any { it.id == testNotificationId })
        select(AppLanguage.ENGLISH)
        awaitCondition { active(AirModeNotificationController.NOTIFICATION_ID).notification.actions.single().title.toString() ==
            expected("en", com.droidance.core.device.R.string.settings_air_notification_action) }
    }

    @Test fun englishQuantitiesAndIslandStatesChangeLanguageWhileTaskTitlesRemainVerbatim() {
        val renderer = RuntimeSurfaceBitmapRenderer(context)
        select(AppLanguage.ENGLISH)
        val english = ContextCompat.getContextForLanguage(context)
        assertEquals("Every 1 minute", english.resources.getQuantityString(R.plurals.automation_schedule_interval, 1, 1))
        assertEquals("Every 2 minutes", english.resources.getQuantityString(R.plurals.automation_schedule_interval, 2, 2))
        assertEquals("Executed 1/1 step.", english.resources.getQuantityString(R.plurals.execution_batch_progress, 1, 1, 1))
        val englishLabels = RuntimeIslandPresentation.State.entries.associateWith { RuntimeIslandPresentation.label(context, it) }
        val artifacts = File(context.getExternalFilesDir(null), "localization-acceptance").apply { mkdirs() }
        for (preference in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
            select(preference)
            for (state in RuntimeIslandPresentation.State.entries) {
                val label = RuntimeIslandPresentation.label(context, state)
                if (preference == AppLanguage.SIMPLIFIED_CHINESE) assertNotEquals(englishLabels[state], label)
                File(artifacts, "island-${preference.tag}-${state.name.lowercase()}.png").writeBytes(
                    renderer.renderIsland(label, RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                        state == RuntimeIslandPresentation.State.USER_CONTROL, state, 0f).png)
            }
            assertEquals("Keep 原文", RuntimeIslandPresentation.label(context, RuntimeIslandPresentation.State.EXECUTING, "Keep 原文"))
            screenshot("main-${preference.tag}.png")
        }
    }

    private fun select(preference: AppLanguage) {
        instrumentation.runOnMainSync { language.setPreference(preference) }
        val tag = if (preference == AppLanguage.SYSTEM) {
            LocaleManagerCompat.getSystemLocales(context).getFirstMatch(arrayOf("en", "zh"))?.language
                .let { if (it == "zh") "zh" else "en" }
        } else preference.tag
        awaitLanguage(tag)
    }

    private fun awaitLanguage(tag: String) = awaitCondition {
        var actual = ""
        scenario.onActivity { actual = it.resources.configuration.locales[0].language }
        actual == tag && LanguageManager.resolvedTag(context) == tag
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        assertTrue("Language presentation did not update within 10 seconds", condition())
    }

    private fun activityString(id: Int): String {
        var value = ""
        scenario.onActivity { value = it.getString(id) }
        return value
    }

    private fun expected(tag: String, id: Int): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    ).getString(id)

    private fun active(id: Int) = notificationManager.activeNotifications.single { it.id == id }

    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val folder = File(context.getExternalFilesDir(null), "localization-acceptance").apply { mkdirs() }
        File(folder, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
