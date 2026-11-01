// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import com.droidance.core.model.llm.models.ApiType
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.model.llm.session.ModelTransport
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiType: ApiType,
    val transportKind: ModelTransport
) {
    fun createConfig(id: String = "preset-${this.id}"): ProviderConfig = ProviderConfig(
        id = id,
        name = name,
        apiType = apiType,
        transportKind = transportKind,
        baseUrl = baseUrl,
        isEnabled = false
    )
}

object ProviderPresetCatalog {
    const val ASSET_PATH = "model_capabilities/provider_presets.json"

    fun decode(source: String): List<ProviderPreset> = Json.decodeFromString<List<ProviderPreset>>(source).also { presets ->
        require(presets.isNotEmpty())
        require(presets.map { it.id }.distinct().size == presets.size)
        presets.forEach { preset ->
            require(preset.id.isNotBlank() && preset.name.isNotBlank())
            val uri = URI(preset.baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            require(preset.createConfig().isTransportConfigured())
        }
    }
}
