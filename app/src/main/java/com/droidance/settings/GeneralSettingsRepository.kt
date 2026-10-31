// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import com.droidance.core.agent.device.RuntimeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeneralSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "droidance_settings"
        private const val KEY_HEARTBEAT_INTERVAL = "heartbeat_interval"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val KEY_LAST_UPDATE_CHECK_TIME = "last_update_check_time"
        private const val KEY_LAST_PROMPTED_UPDATE_VERSION = "last_prompted_update_version"
        private const val KEY_KNOWN_UPDATE_VERSION = "known_update_version"
        const val DEFAULT_HEARTBEAT_INTERVAL = 15f
    }

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _autoCheckUpdate = MutableStateFlow(loadAutoCheckUpdate())
    val autoCheckUpdate: StateFlow<Boolean> = _autoCheckUpdate.asStateFlow()

    private val _runtimeMode = MutableStateFlow<RuntimeMode?>(RuntimeMode.STANDARD)
    val runtimeMode: StateFlow<RuntimeMode?> = _runtimeMode.asStateFlow()

    private fun loadThemeMode(): AppThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
        return AppThemeMode.entries.firstOrNull { it.name == stored } ?: AppThemeMode.SYSTEM
    }

    fun heartbeatInterval(): Float {
        return prefs.getFloat(KEY_HEARTBEAT_INTERVAL, DEFAULT_HEARTBEAT_INTERVAL)
    }

    fun setHeartbeatInterval(minutes: Float) {
        prefs.edit().putFloat(KEY_HEARTBEAT_INTERVAL, minutes).apply()
    }

    fun themeMode(): AppThemeMode = _themeMode.value

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun runtimeMode(): RuntimeMode? = _runtimeMode.value

    fun saveGeneralSettings(heartbeatInterval: Float, themeMode: AppThemeMode) {
        prefs.edit().apply {
            putFloat(KEY_HEARTBEAT_INTERVAL, heartbeatInterval)
            putString(KEY_THEME_MODE, themeMode.name)
            apply()
        }
        _themeMode.value = themeMode
    }

    private fun loadAutoCheckUpdate(): Boolean = prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true)

    fun autoCheckUpdate(): Boolean = _autoCheckUpdate.value
    fun setAutoCheckUpdate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, enabled).apply()
        _autoCheckUpdate.value = enabled
    }

    fun lastUpdateCheckTime(): Long = prefs.getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L)
    fun setLastUpdateCheckTime(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_TIME, timestamp).apply()
    }

    fun knownUpdateVersion(): String = prefs.getString(KEY_KNOWN_UPDATE_VERSION, "").orEmpty()
    fun setKnownUpdateVersion(version: String) {
        prefs.edit().putString(KEY_KNOWN_UPDATE_VERSION, version).apply()
    }

    fun lastPromptedUpdateVersion(): String = prefs.getString(KEY_LAST_PROMPTED_UPDATE_VERSION, "").orEmpty()
    fun setLastPromptedUpdateVersion(version: String) {
        prefs.edit().putString(KEY_LAST_PROMPTED_UPDATE_VERSION, version).apply()
    }
}
