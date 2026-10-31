// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import com.droidance.core.agent.device.ExecutionSpace
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ComposerSettings(
    val providerId: String? = null,
    val modelId: String? = null,
    val executionSpace: ExecutionSpace = ExecutionSpace.VIRTUAL_SPACE
)

/** The last manual composer choice is shared by conversations and survives app restarts. */
@Singleton
class ComposerSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("droidance_composer", Context.MODE_PRIVATE)

    init {
        val obsoleteKeys = prefs.all.keys.filter { it == "reasoning" || it.startsWith("reasoning:") }
        if (obsoleteKeys.isNotEmpty()) {
            prefs.edit().apply { obsoleteKeys.forEach(::remove) }.apply()
        }
    }

    fun read(): ComposerSettings = ComposerSettings(
        providerId = prefs.getString("provider", null),
        modelId = prefs.getString("model", null),
        executionSpace = ExecutionSpace.VIRTUAL_SPACE
    )

    fun save(settings: ComposerSettings) {
        prefs.edit()
            .putString("provider", settings.providerId)
            .putString("model", settings.modelId)
            .remove("space")
            .apply()
    }
}
