// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import com.droidance.core.model.llm.ProviderEndpoint
import com.droidance.core.model.llm.models.ProviderConfig
import java.net.URI
import java.util.Locale

internal fun providerAddressKey(url: String): String {
    val root = ProviderEndpoint.apiRoot(url)
    return runCatching {
        val uri = URI(root)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return root
        val host = uri.host?.lowercase(Locale.ROOT) ?: return root
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        "$scheme://$host$port${uri.rawPath.orEmpty()}$query$fragment"
    }.getOrDefault(root)
}

internal fun List<ProviderPreset>.groupFor(config: ProviderConfig): ProviderPreset? {
    val explicit = config.providerGroupId?.let { group -> firstOrNull { it.id == group && it.groupId == null } }
    if (explicit != null) return explicit
    val original = firstOrNull { config.id == "preset-${it.id}" }
        ?: firstOrNull { providerAddressKey(it.baseUrl) == providerAddressKey(config.baseUrl) }
    return original?.let { endpoint -> firstOrNull { it.id == endpoint.providerGroupId } }
}

internal fun List<ProviderPreset>.endpointsFor(groupId: String): List<ProviderPreset> =
    filter { it.providerGroupId == groupId }

internal fun List<ProviderPreset>.displayNameFor(config: ProviderConfig): String {
    val group = groupFor(config) ?: return config.name
    return group.name.takeIf { endpointsFor(group.id).any { it.name == config.name } } ?: config.name
}

private fun ProviderConfig.isEmptyPreset(presets: List<ProviderPreset>): Boolean {
    val preset = presets.firstOrNull { id == "preset-${it.id}" } ?: return false
    return name == preset.name && apiKey.isBlank() && models.isEmpty() && defaultModel.isBlank() &&
        !isEnabled && reasoningPreference == null && contextWindowTokens == 128_000 &&
        apiType == preset.apiType && transportKind == preset.transportKind &&
        providerAddressKey(baseUrl) == providerAddressKey(preset.baseUrl)
}

internal data class ProviderGroup(
    val preset: ProviderPreset?,
    val configs: List<ProviderConfig>,
    val representative: ProviderConfig,
    val displayName: String
)

/** Group distinct preset addresses without hiding multiple accounts on the same address. */
internal fun List<ProviderConfig>.providerGroups(presets: List<ProviderPreset>): List<ProviderGroup> {
    data class Bucket(val preset: ProviderPreset?, val configs: MutableList<ProviderConfig>)
    val buckets = mutableListOf<Bucket>()
    val mainGroups = mutableMapOf<String, Bucket>()
    inDisplayOrder(presets).forEach { config ->
        val preset = presets.groupFor(config)
        val bucket = preset?.let { mainGroups[it.id] }
        val sameAddress = bucket?.configs?.filter { providerAddressKey(it.baseUrl) == providerAddressKey(config.baseUrl) }.orEmpty()
        val canJoin = sameAddress.isEmpty() || config.isEmptyPreset(presets) || sameAddress.all { it.isEmptyPreset(presets) }
        if (bucket != null && canJoin) {
            bucket.configs += config
        } else {
            val added = Bucket(preset, mutableListOf(config))
            buckets += added
            if (preset != null && bucket == null) mainGroups[preset.id] = added
        }
    }
    val defaultId = firstOrNull(ProviderConfig::isAvailableForRequests)?.id
    val storedOrder = withIndex().associate { it.value.id to it.index }
    return buckets.map { bucket ->
        val ordered = bucket.configs.sortedBy { storedOrder[it.id] ?: Int.MAX_VALUE }
        val representative = ordered.firstOrNull { it.id == defaultId }
            ?: ordered.firstOrNull { it.isEnabled && it.apiKey.isNotBlank() && it.models.isNotEmpty() }
            ?: ordered.firstOrNull { it.apiKey.isNotBlank() && it.models.isNotEmpty() }
            ?: ordered.firstOrNull { it.apiKey.isNotBlank() || it.models.isNotEmpty() || it.defaultModel.isNotBlank() }
            ?: ordered.first()
        val displayName = presets.displayNameFor(representative)
        ProviderGroup(bucket.preset, bucket.configs.toList(), representative, displayName)
    }
}
