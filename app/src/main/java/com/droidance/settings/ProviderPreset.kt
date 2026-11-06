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
    val transportKind: ModelTransport,
    val suggestedModels: List<String> = emptyList(),
    val modelListSupported: Boolean = true,
    val groupId: String? = null,
    val addressLabel: ProviderAddressLabel = ProviderAddressLabel.API
) {
    val providerGroupId: String get() = groupId ?: id

    fun createConfig(id: String = "preset-${this.id}"): ProviderConfig = ProviderConfig(
        id = id,
        name = name,
        apiType = apiType,
        transportKind = transportKind,
        baseUrl = baseUrl,
        isEnabled = false,
        providerGroupId = providerGroupId
    )
}

@Serializable
enum class ProviderAddressLabel { API, CODING_PLAN, AGENT_PLAN, TOKEN_PLAN, KIMI_CODE }

/**
 * Default, then custom, enabled preset and disabled preset entries; an entry is custom when its
 * address is not in the catalog. Display only: the stored order selects the default Provider.
 */
fun List<ProviderConfig>.inDisplayOrder(presets: List<ProviderPreset>): List<ProviderConfig> {
    val presetRanks = presets.mapIndexed { index, preset ->
        ProviderEndpoint.apiRoot(preset.baseUrl).lowercase() to index
    }.toMap()
    val defaultId = firstOrNull(ProviderConfig::isAvailableForRequests)?.id
    return sortedWith(compareBy<ProviderConfig>(
        { config ->
            when {
                config.id == defaultId -> 0
                ProviderEndpoint.apiRoot(config.baseUrl).lowercase() !in presetRanks -> 1
                config.isEnabled -> 2
                else -> 3
            }
        },
        { config ->
            if (config.id == defaultId) 0
            else presetRanks[ProviderEndpoint.apiRoot(config.baseUrl).lowercase()] ?: 0
        }
    ))
}

object ProviderPresetCatalog {
    const val ASSET_PATH = "model_capabilities/provider_presets.json"

    fun decode(source: String): List<ProviderPreset> = Json.decodeFromString<List<ProviderPreset>>(source).also { presets ->
        require(presets.isNotEmpty())
        require(presets.map { it.id }.distinct().size == presets.size)
        val groupIds = presets.mapTo(HashSet()) { it.id }
        presets.forEach { preset ->
            require(preset.id.isNotBlank() && preset.name.isNotBlank())
            require(preset.groupId == null || (preset.groupId in groupIds && preset.groupId != preset.id))
            require(preset.groupId == null || presets.first { it.id == preset.groupId }.groupId == null)
            val uri = URI(preset.baseUrl)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            require(preset.createConfig().isTransportConfigured())
            require(preset.suggestedModels.all { it.isNotBlank() && it == it.trim() })
            require(preset.suggestedModels.distinct().size == preset.suggestedModels.size)
        }
    }
}
