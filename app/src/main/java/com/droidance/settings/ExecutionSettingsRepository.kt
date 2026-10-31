// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

object ExecutionSettingsPolicy {
    // Active task duration limit in minutes: 0 disables it. Human waiting is excluded.
    const val DEFAULT_TASK_WALL_MINUTES = 0
    const val MIN_TASK_WALL_MINUTES = 0
    // Third-party Deep Links: optional direct route attempt. Disabled by default to avoid app risk controls and stale routes.
    const val DEFAULT_ENABLE_DEEPLINK = false

    fun normalizeTaskWallMinutes(value: Int): Int {
        return value.coerceAtLeast(MIN_TASK_WALL_MINUTES)
    }
}

@Singleton
class ExecutionSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun taskWallMinutes(): Int {
        return ExecutionSettingsPolicy.normalizeTaskWallMinutes(
            prefs.getInt(KEY_TASK_WALL_MINUTES, ExecutionSettingsPolicy.DEFAULT_TASK_WALL_MINUTES)
        )
    }

    fun setTaskWallMinutes(value: Int) {
        prefs.edit()
            .putInt(KEY_TASK_WALL_MINUTES, ExecutionSettingsPolicy.normalizeTaskWallMinutes(value))
            .apply()
    }

    fun isDeepLinkEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLE_DEEPLINK, ExecutionSettingsPolicy.DEFAULT_ENABLE_DEEPLINK)
    }

    fun setDeepLinkEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLE_DEEPLINK, enabled)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "droidance_settings"
        const val KEY_TASK_WALL_MINUTES = "task_wall_minutes"
        const val KEY_ENABLE_DEEPLINK = "enable_deeplink"
    }
}
