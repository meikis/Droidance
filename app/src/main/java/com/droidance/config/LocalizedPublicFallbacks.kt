// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import kotlinx.serialization.Serializable

/** Local runtime notices are configured text, never model-authored task conclusions. */
@Serializable
internal data class LocalizedPublicFallbacks(val translations: Map<String, Map<String, String>>) {
    fun validate() {
        require(translations.keys == ResolvedLanguage.entries.map { it.tag }.toSet())
        val keys = translations.values.first().keys
        require("termination_default" in keys)
        require(translations.values.all { messages ->
            messages.keys == keys && messages.values.all(String::isNotBlank)
        })
    }

    fun text(language: String, key: String): String =
        translations.getValue(language)[key] ?: error("Missing public fallback: $key")

    fun termination(language: String, reason: String): String =
        translations.getValue(language)["termination_$reason"] ?: text(language, "termination_default")
}
