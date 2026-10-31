// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The empty locale list means that Android chooses from the system languages. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    SIMPLIFIED_CHINESE("zh"),
    ENGLISH("en")
}

enum class ResolvedLanguage(val tag: String, val promptName: String) {
    SIMPLIFIED_CHINESE("zh", "Simplified Chinese"),
    ENGLISH("en", "English")
}

/** Android/AndroidX owns the preference and its persistence, including system settings changes. */
@Singleton
class LanguageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getPreference(): AppLanguage {
        val locale = LocaleManagerCompat.getApplicationLocales(context)[0]
        return AppLanguage.entries.firstOrNull { it.tag == locale?.language } ?: AppLanguage.SYSTEM
    }

    /** Must be called on the main thread, as the API updates Activity configuration. */
    fun setPreference(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    fun resolved(): ResolvedLanguage =
        ResolvedLanguage.entries.first { it.tag == resolvedTag(context) }

    companion object {
        fun resolvedTag(context: Context): String {
            val requested = LocaleManagerCompat.getApplicationLocales(context)
            val locales = if (requested.isEmpty) LocaleManagerCompat.getSystemLocales(context) else requested
            return when (locales.getFirstMatch(arrayOf("en", "zh"))?.language) {
                "zh" -> "zh"
                else -> "en"
            }
        }
    }
}
