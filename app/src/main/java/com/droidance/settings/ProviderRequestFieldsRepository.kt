// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import com.droidance.core.model.llm.ProviderRequestFields
import com.droidance.core.model.llm.models.ProviderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Bundled gateway request fields from model_capabilities/request_fields.json. */
@Singleton
class ProviderRequestFieldsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val catalog = ProviderRequestFields(runCatching {
        context.assets.open("model_capabilities/request_fields.json").bufferedReader().use { it.readText() }
    }.getOrDefault("{}"))

    fun responses(config: ProviderConfig): JsonObject = catalog.responses(config.baseUrl)
}
