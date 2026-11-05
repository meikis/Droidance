// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.content.Context
import android.os.Bundle

/** GUI-owned configuration keys. Runtime receipts, usage and learned caches stay in the owner. */
internal object RuntimeConfigurationSync {
    private val preferences = mapOf(
        "droidance_providers" to setOf("provider_configs", "provider_config_schema_version"),
        "droidance_settings" to setOf("task_wall_minutes", "enable_deeplink", "device_connection_method"),
        "skill-authoring" to setOf("auto_draft")
    )
    fun snapshot(context: Context): Bundle = Bundle().apply {
        preferences.forEach { (name, keys) ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            putBundle(name, Bundle().apply { keys.forEach { key ->
                when (val value = prefs.all[key]) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            } })
        }
        putString("language", com.droidance.config.LanguageManager.resolvedTag(context))
    }
    fun apply(context: Context, values: Bundle): Boolean {
        var changed = false
        preferences.forEach { (name, keys) ->
            val updates = requireNotNull(values.getBundle(name))
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var edited = false
            keys.forEach { key ->
                if (prefs.all[key] != updates.get(key)) {
                    edited = true
                    when (val value = updates.get(key)) {
                        null -> editor.remove(key)
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        else -> error("Unsupported configuration value")
                    }
                }
            }
            if (edited) { check(editor.commit()); changed = true }
        }
        return changed
    }
}
