// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.ui.navigation

import android.content.Context
import com.droidance.core.knowledge.agent.device.DeviceProfileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local presentation state only; rendering must not wait for the resident execution owner. */
@Singleton
class StartupNavigationStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("navigation_presentation", Context.MODE_PRIVATE)
    private val localProfile = File(context.noBackupFilesDir, "droidance-data/${DeviceProfileStore.PATH}")
    private val transferred = File(context.noBackupFilesDir, "runtime-storage-transferred").isFile
    private val _onboardingComplete = MutableStateFlow<Boolean?>(when {
        prefs.contains("onboarding_complete") -> prefs.getBoolean("onboarding_complete", false)
        localProfile.isFile && localProfile.length() > 0 -> true
        !transferred -> false
        else -> null // Await facts in the background while showing the normal conversation UI.
    })
    val onboardingComplete = _onboardingComplete.asStateFlow()
    val lastConversationId: String? get() = prefs.getString("conversation", null)
        ?.takeUnless { it == prefs.getString("empty_conversation", null) }

    fun recordConversationEmpty(id: String, empty: Boolean) {
        if (empty) prefs.edit().putString("empty_conversation", id).apply()
        else if (prefs.getString("empty_conversation", null) == id) {
            prefs.edit().remove("empty_conversation").apply()
        }
    }

    fun recordOnboarding(complete: Boolean) {
        if (_onboardingComplete.value == complete && prefs.contains("onboarding_complete")) return
        prefs.edit().putBoolean("onboarding_complete", complete).apply()
        _onboardingComplete.value = complete
    }

    fun recordConversation(id: String) {
        if (id != lastConversationId) prefs.edit().putString("conversation", id).apply()
    }
}
