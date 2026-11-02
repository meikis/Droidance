// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import com.droidance.core.model.llm.ProviderEndpoint
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

/**
 * Default, then custom, enabled preset and disabled preset entries; an entry is custom when its
 * address is not in the catalog. Display only: the stored order selects the default Provider.
 */
fun List<ProviderConfig>.inDisplayOrder(presets: List<ProviderPreset>): List<ProviderConfig> {
    val presetRoots = presets.mapTo(HashSet()) { ProviderEndpoint.apiRoot(it.baseUrl).lowercase() }
    val defaultId = firstOrNull(ProviderConfig::isAvailableForRequests)?.id
    return sortedBy { config ->
        when {
            config.id == defaultId -> 0
            ProviderEndpoint.apiRoot(config.baseUrl).lowercase() !in presetRoots -> 1
            config.isEnabled -> 2
            else -> 3
        }
    }
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
