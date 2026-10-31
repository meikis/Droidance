// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.device.ExecutionSpace
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryRegressionTest {
    private lateinit var baseContext: Context
    private lateinit var isolatedContext: Context
    private val isolatedPreferenceNames = linkedSetOf<String>()

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
        val prefix = "settings-regression-${UUID.randomUUID()}-"
        isolatedContext = object : ContextWrapper(baseContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = prefix + name
                isolatedPreferenceNames += isolatedName
                return baseContext.getSharedPreferences(isolatedName, mode)
            }
        }
    }

    @After
    fun tearDown() {
        isolatedPreferenceNames.forEach(baseContext::deleteSharedPreferences)
        isolatedPreferenceNames.clear()
    }

    @Test
    fun composerChoicesSurviveReconstructionAndExplicitReturnToDefaults() {
        val repository = ComposerSettingsRepository(isolatedContext)
        assertEquals(ComposerSettings(), repository.read())
        val manual = ComposerSettings("provider-id", "chosen-model", ExecutionSpace.MAIN_SCREEN)
        repository.save(manual)
        assertEquals(manual.copy(executionSpace = ExecutionSpace.VIRTUAL_SPACE), ComposerSettingsRepository(isolatedContext).read())

        repository.save(ComposerSettings())
        assertEquals(ComposerSettings(), ComposerSettingsRepository(isolatedContext).read())
    }

    @Test
    fun obsoleteComposerReasoningIsRemovedWithoutChangingTheSelectedModel() {
        val preferences = isolatedContext.getSharedPreferences("droidance_composer", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("provider", "p")
            .putString("model", "a")
            .putString("reasoning", "MAX")
            .putString("reasoning:p\u001fa", "XHIGH")
            .putString("reasoning:p\u001fb", "LOW")
            .commit()

        val repository = ComposerSettingsRepository(isolatedContext)
        assertEquals(ComposerSettings("p", "a"), repository.read())
        assertTrue(preferences.all.keys.none { it == "reasoning" || it.startsWith("reasoning:") })
        assertEquals(repository.read(), ComposerSettingsRepository(isolatedContext).read())
    }

    @Test
    fun themeWritesUpdateOneRepositoryStateAndSurviveReconstruction() = runBlocking {
        val repository = GeneralSettingsRepository(isolatedContext)
        val preferences = isolatedPreferences()
        assertEquals(AppThemeMode.SYSTEM, repository.themeMode.value)
        assertEquals(AppThemeMode.SYSTEM, repository.themeMode())

        val observed = mutableListOf<AppThemeMode>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.themeMode.take(2).toList(observed)
        }
        repository.setThemeMode(AppThemeMode.DARK)
        collection.join()

        assertEquals(listOf(AppThemeMode.SYSTEM, AppThemeMode.DARK), observed)
        assertEquals(AppThemeMode.DARK, repository.themeMode.value)
        assertEquals(AppThemeMode.DARK, repository.themeMode())
        assertEquals(AppThemeMode.DARK.name, preferences.getString(THEME_MODE_KEY, null))

        repository.saveGeneralSettings(heartbeatInterval = 27f, themeMode = AppThemeMode.LIGHT)
        assertEquals(AppThemeMode.LIGHT, repository.themeMode.value)
        assertEquals(AppThemeMode.LIGHT, repository.themeMode())
        assertEquals(AppThemeMode.LIGHT.name, preferences.getString(THEME_MODE_KEY, null))
        assertEquals(27f, preferences.getFloat(HEARTBEAT_INTERVAL_KEY, -1f))

        val reconstructed = GeneralSettingsRepository(isolatedContext)
        assertEquals(AppThemeMode.LIGHT, reconstructed.themeMode.value)
        assertEquals(AppThemeMode.LIGHT, reconstructed.themeMode())
    }

    @Test
    fun retiredPerceptionPreferencesAreRemovedWithoutChangingExecutionChoices() {
        val preferences = isolatedPreferences()
        preferences.edit()
            .putBoolean("enable_ocr", true)
            .putInt("perception_defaults_version", 3)
            .putInt("task_wall_minutes", 7)
            .putBoolean("enable_deeplink", true)
            .commit()

        val repository = ExecutionSettingsRepository(isolatedContext)
        assertFalse(preferences.contains("enable_ocr"))
        assertFalse(preferences.contains("perception_defaults_version"))
        assertEquals(7, repository.taskWallMinutes())
        assertTrue(repository.isDeepLinkEnabled())
        val reconstructed = ExecutionSettingsRepository(isolatedContext)
        assertEquals(7, reconstructed.taskWallMinutes())
        assertTrue(reconstructed.isDeepLinkEnabled())
    }

    @Test fun archivedUltraPreferenceCannotReactivateExecution() {
        isolatedPreferences().edit().putString(SELECTED_RUNTIME_MODE_KEY, "ULTRA")
            .putBoolean("ultra_unlocked", true).commit()
        val repository = GeneralSettingsRepository(isolatedContext)
        assertEquals(RuntimeMode.STANDARD, repository.runtimeMode())
        assertEquals(RuntimeMode.STANDARD, repository.runtimeMode.value)
    }

    private fun isolatedPreferences(): SharedPreferences =
        isolatedContext.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)

    private companion object {
        const val SETTINGS_PREFERENCES = "droidance_settings"
        const val THEME_MODE_KEY = "theme_mode"
        const val SELECTED_RUNTIME_MODE_KEY = "selected_runtime_mode"
        const val HEARTBEAT_INTERVAL_KEY = "heartbeat_interval"
    }
}
