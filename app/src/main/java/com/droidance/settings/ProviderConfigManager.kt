// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import com.droidance.core.model.llm.models.ImageDetailCapabilityStatus
import com.droidance.core.model.llm.models.ImageDetailCapabilitySnapshot
import com.droidance.core.model.llm.models.ImageDetailNegotiation
import com.droidance.core.model.llm.models.ImageDetailOption
import com.droidance.core.model.llm.models.ProviderConfig
import com.droidance.core.device.agent.device.RuntimeProcessRole
import com.droidance.runtime.RuntimeProviderBootstrap
import com.droidance.runtime.RuntimeProviderSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider 配置管理器
 * 负责 Provider 配置的 CRUD 和 JSON 持久化
 */
@Singleton
class ProviderConfigManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val PROVIDER_CONFIGS_KEY = "provider_configs"
        const val PROVIDER_CONFIG_SCHEMA_VERSION_KEY = "provider_config_schema_version"
        const val IMAGE_DETAIL_CAPABILITIES_KEY = "image_detail_capabilities"
        const val LEGACY_PROVIDER_CONFIGS_BACKUP_KEY = "provider_configs_backup"
        const val UNREADABLE_PROVIDER_CONFIGS_BACKUP_KEY = "provider_configs_unreadable_backup"
        const val CURRENT_SCHEMA_VERSION = 3
        const val IMAGE_DETAIL_CAPABILITY_TTL_MS = 24L * 60L * 60L * 1_000L
        const val RETURNED_MODEL_REFRESH_MIN_INTERVAL_MS = 60L * 60L * 1_000L
    }

    private val prefs = context.getSharedPreferences("droidance_providers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val presets: List<ProviderPreset> by lazy {
        context.assets.open(ProviderPresetCatalog.ASSET_PATH).bufferedReader().use {
            ProviderPresetCatalog.decode(it.readText())
        }
    }

    /** 内存缓存 */
    private var _configs = mutableListOf<ProviderConfig>()
    private var imageDetailCapabilities = mutableListOf<PersistedImageDetailCapability>()

    /**
     * 同 Provider 内模型降级记录
     * key = providerId, value = 当前使用的模型索引（从 0 开始）
     * 当某个模型调用出错时，索引 +1 切换到下一个模型
     */
    private val _modelFallbackIndex = mutableMapOf<String, Int>()

    init {
        if (RuntimeProcessRole.isOwner) {
            updateRuntimeSnapshot(requireNotNull(RuntimeProviderBootstrap.take()) { "Runtime provider configuration is unavailable" })
        } else loadConfigs()
        loadImageDetailCapabilities()
    }

    /** Runtime credentials are delivered by the authenticated client and never saved in shell storage. */
    @Synchronized internal fun updateRuntimeSnapshot(snapshot: RuntimeProviderSnapshot) {
        check(RuntimeProcessRole.isOwner)
        val raw = snapshot.configs
        _configs = when {
            raw == null -> presets.map { it.createConfig() }.toMutableList()
            snapshot.schemaVersion != CURRENT_SCHEMA_VERSION -> {
                android.util.Log.e("ProviderConfigManager", "Unknown runtime provider config schema rejected")
                mutableListOf()
            }
            else -> try { json.decodeFromString<List<ProviderConfig>>(raw).toMutableList() }
            catch (_: Exception) {
                android.util.Log.e("ProviderConfigManager", "Corrupt runtime provider config rejected")
                mutableListOf()
            }
        }
    }

    /** 获取所有 Provider 配置 */
    @Synchronized fun getAllConfigs(): List<ProviderConfig> = _configs.toList()

    /** 获取所有启用的 Provider 配置 */
    @Synchronized fun getEnabledConfigs(): List<ProviderConfig> = _configs.filter {
        it.isAvailableForRequests()
    }

    /** 根据 ID 获取配置 */
    @Synchronized fun getConfig(id: String): ProviderConfig? = _configs.find { it.id == id }

    /** 添加新 Provider */
    @Synchronized fun addConfig(config: ProviderConfig) {
        require(!config.isEnabled || config.isTransportConfigured()) {
            "Enabled Provider must have a confirmed transport"
        }
        _configs.add(config)
        saveConfigsSafe()
    }

    /** 更新 Provider */
    @Synchronized fun updateConfig(config: ProviderConfig) {
        require(!config.isEnabled || config.isTransportConfigured()) {
            "Enabled Provider must have a confirmed transport"
        }
        val previous = _configs.toList()
        val index = _configs.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            if (_configs[index].imageCapabilityIdentity() != config.imageCapabilityIdentity()) {
                invalidateImageDetailCapability(config.id)
            }
            _configs[index] = config
        } else {
            _configs.add(config)
        }
        try {
            saveConfigs()
        } catch (error: Exception) {
            _configs = previous.toMutableList()
            throw error
        }
    }

    /** 删除 Provider */
    @Synchronized fun deleteConfig(id: String) {
        _configs.removeAll { it.id == id }
        invalidateImageDetailCapability(id)
        saveConfigsSafe()
    }

    /** The first enabled configured Provider is the single default Provider. */
    @Synchronized fun getDefaultConfig(): ProviderConfig? = getEnabledConfigs().firstOrNull()

    @Synchronized fun setDefaultProvider(id: String) {
        val selected = getConfig(id) ?: error("Provider not found")
        require(selected.isAvailableForRequests())
        val previous = _configs.toList()
        _configs.removeAll { it.id == id }
        _configs.add(0, selected)
        try {
            saveConfigs()
        } catch (error: Exception) {
            _configs = previous.toMutableList()
            throw error
        }
    }

    /** One configured default Provider is selected and then frozen by LLMRouter for the Turn. */
    @Synchronized fun resolveProvider(): ProviderConfig? = getDefaultConfig()

    // ====== 同 Provider 模型自动降级 ======

    /**
     * 获取当前应使用的模型（考虑降级状态）
     * 优先返回 defaultModel，如果已降级则返回 models 列表中的下一个
     */
    @Synchronized fun getCurrentModel(config: ProviderConfig): String {
        if (config.models.isEmpty()) return config.defaultModel
        // 手动选中的模型（defaultModel）作为基准，自动降级在其后偏移
        val baseIndex = config.models.indexOf(config.defaultModel).coerceAtLeast(0)
        val offset = _modelFallbackIndex[config.id] ?: 0
        val index = baseIndex + offset
        return if (index < config.models.size) {
            config.models[index]
        } else {
            config.defaultModel.ifBlank { config.models.first() }
        }
    }

    /**
     * 模型调用失败时，切换到下一个模型
     * @return 下一个模型名称，如果所有模型都用完了返回 null
     */
    @Synchronized fun advanceToNextModel(config: ProviderConfig): String? {
        if (config.models.isEmpty()) return null
        val baseIndex = config.models.indexOf(config.defaultModel).coerceAtLeast(0)
        val nextOffset = (_modelFallbackIndex[config.id] ?: 0) + 1
        val nextIndex = baseIndex + nextOffset
        return if (nextIndex < config.models.size) {
            _modelFallbackIndex[config.id] = nextOffset
            config.models[nextIndex]
        } else {
            null
        }
    }

    /** 重置模型降级状态（调用成功后或新对话开始时） */
    @Synchronized fun resetModelFallback(configId: String) {
        _modelFallbackIndex.remove(configId)
    }

    @Synchronized
    fun imageDetailCandidates(config: ProviderConfig, model: String): List<ImageDetailOption> {
        val now = System.currentTimeMillis()
        val capability = imageDetailCapabilities.find { it.cacheKey == imageCapabilityKey(config, model) }
        return ImageDetailNegotiation.candidates(
            capability = capability?.let {
                ImageDetailCapabilitySnapshot(it.accepted, it.rejected, it.detectedAtMillis)
            },
            nowMillis = now,
            ttlMillis = IMAGE_DETAIL_CAPABILITY_TTL_MS
        )
    }

    @Synchronized
    fun recordImageDetailAccepted(
        config: ProviderConfig,
        model: String,
        detail: ImageDetailOption,
        returnedModel: String?
    ) {
        val key = imageCapabilityKey(config, model)
        val now = System.currentTimeMillis()
        val prior = imageDetailCapabilities.find { it.cacheKey == key }
        val returnedModelChanged = !returnedModel.isNullOrBlank() &&
            !prior?.returnedModel.isNullOrBlank() && prior?.returnedModel != returnedModel
        val markChanged = returnedModelChanged &&
            now - (prior?.lastReturnedModelRefreshAtMillis ?: 0L) >= RETURNED_MODEL_REFRESH_MIN_INTERVAL_MS
        if (!markChanged && prior?.accepted == detail && prior.detectedAtMillis > 0L &&
            now - prior.detectedAtMillis < IMAGE_DETAIL_CAPABILITY_TTL_MS &&
            (returnedModel.isNullOrBlank() || prior.returnedModel == returnedModel)
        ) return
        val firstSuccessfulDetection = prior?.accepted == null ||
            prior.detectedAtMillis <= 0L ||
            now - prior.detectedAtMillis >= IMAGE_DETAIL_CAPABILITY_TTL_MS ||
            prior.accepted != detail
        val updated = PersistedImageDetailCapability(
            providerId = config.id,
            cacheKey = key,
            accepted = detail,
            rejected = if (markChanged) emptySet() else prior?.rejected.orEmpty(),
            detectedAtMillis = when {
                markChanged -> 0L
                firstSuccessfulDetection -> now
                else -> prior.detectedAtMillis
            },
            returnedModel = returnedModel?.takeIf(String::isNotBlank) ?: prior?.returnedModel,
            lastReturnedModelRefreshAtMillis = if (markChanged) now else
                prior?.lastReturnedModelRefreshAtMillis ?: 0L
        )
        imageDetailCapabilities.removeAll { it.cacheKey == key }
        imageDetailCapabilities.add(updated)
        saveImageDetailCapabilities()
    }

    @Synchronized
    fun recordImageDetailRejected(config: ProviderConfig, model: String, detail: ImageDetailOption) {
        val key = imageCapabilityKey(config, model)
        val prior = imageDetailCapabilities.find { it.cacheKey == key }
        val stale = prior == null || prior.detectedAtMillis <= 0L ||
            System.currentTimeMillis() - prior.detectedAtMillis >= IMAGE_DETAIL_CAPABILITY_TTL_MS
        imageDetailCapabilities.removeAll { it.cacheKey == key }
        imageDetailCapabilities.add(PersistedImageDetailCapability(
            providerId = config.id,
            cacheKey = key,
            accepted = prior?.accepted?.takeIf { !stale && it != detail },
            rejected = (if (stale) emptySet() else prior?.rejected.orEmpty()) + detail,
            detectedAtMillis = System.currentTimeMillis(),
            returnedModel = prior?.returnedModel,
            lastReturnedModelRefreshAtMillis = prior?.lastReturnedModelRefreshAtMillis ?: 0L
        ))
        saveImageDetailCapabilities()
    }

    @Synchronized
    fun invalidateImageDetailCapability(configId: String) {
        if (imageDetailCapabilities.removeAll { it.providerId == configId }) {
            saveImageDetailCapabilities()
        }
    }

    @Synchronized
    fun imageDetailCapabilityStatus(
        config: ProviderConfig,
        model: String
    ): ImageDetailCapabilityStatus {
        val capability = imageDetailCapabilities.find { it.cacheKey == imageCapabilityKey(config, model) }
        val detectedAt = capability?.detectedAtMillis?.takeIf { it > 0L }
        val fresh = detectedAt != null &&
            System.currentTimeMillis() - detectedAt < IMAGE_DETAIL_CAPABILITY_TTL_MS
        return ImageDetailCapabilityStatus(
            effective = capability?.accepted,
            detectedAtMillis = detectedAt,
            returnedModel = capability?.returnedModel,
            needsDetection = !fresh
        )
    }

    // ====== 持久化 ======

    private fun saveConfigs() {
        if (RuntimeProcessRole.isOwner) return
        val jsonString = json.encodeToString(_configs.toList())
        check(
            prefs.edit()
                .putString(PROVIDER_CONFIGS_KEY, jsonString)
                .putInt(PROVIDER_CONFIG_SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
                .remove(LEGACY_PROVIDER_CONFIGS_BACKUP_KEY)
                .commit()
        ) { "Provider config persistence failed" }
    }

    private fun saveConfigsSafe() {
        if (RuntimeProcessRole.isOwner) return
        try {
            val jsonString = json.encodeToString(_configs.toList())
            check(
                prefs.edit()
                    .putString(PROVIDER_CONFIGS_KEY, jsonString)
                    .putInt(PROVIDER_CONFIG_SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
                    .remove(LEGACY_PROVIDER_CONFIGS_BACKUP_KEY)
                    .commit()
            ) { "Provider config persistence failed" }
        } catch (e: Exception) {
            android.util.Log.e("ProviderConfigManager", "Provider config persistence failed", e)
        }
    }

    private fun loadConfigs() {
        val rawConfigs = prefs.getString(PROVIDER_CONFIGS_KEY, null)
        if (rawConfigs == null) {
            _configs = presets.map { it.createConfig() }.toMutableList()
            saveConfigs()
            return
        }

        when (prefs.getInt(PROVIDER_CONFIG_SCHEMA_VERSION_KEY, 0)) {
            CURRENT_SCHEMA_VERSION -> {
                _configs = runCatching {
                    json.decodeFromString<List<ProviderConfig>>(rawConfigs).toMutableList()
                }.getOrElse { error ->
                    android.util.Log.e("ProviderConfigManager", "Corrupt Provider config rejected", error)
                    backupUnreadableConfigs(rawConfigs)
                    mutableListOf()
                }
            }
            else -> {
                android.util.Log.e("ProviderConfigManager", "Unknown Provider config schema rejected")
                // Keep the raw payload so a downgrade/upgrade does not irrecoverably wipe user providers.
                backupUnreadableConfigs(rawConfigs)
                _configs = mutableListOf()
                saveConfigs()
            }
        }
    }

    private fun backupUnreadableConfigs(rawConfigs: String) {
        prefs.edit().putString(UNREADABLE_PROVIDER_CONFIGS_BACKUP_KEY, rawConfigs).commit()
    }

    private fun loadImageDetailCapabilities() {
        imageDetailCapabilities = runCatching {
            json.decodeFromString<List<PersistedImageDetailCapability>>(
                prefs.getString(IMAGE_DETAIL_CAPABILITIES_KEY, "[]") ?: "[]"
            ).toMutableList()
        }.getOrElse {
            android.util.Log.e("ProviderConfigManager", "Corrupt image detail capability cache rejected", it)
            mutableListOf()
        }
    }

    private fun saveImageDetailCapabilities() {
        runCatching {
            check(prefs.edit().putString(
                IMAGE_DETAIL_CAPABILITIES_KEY,
                json.encodeToString(imageDetailCapabilities.toList())
            ).commit()) { "Image detail capability persistence failed" }
        }.onFailure {
            android.util.Log.e("ProviderConfigManager", "Image detail capability persistence failed", it)
        }
    }

    private fun imageCapabilityKey(config: ProviderConfig, model: String): String = sha256(
        listOf(
            config.id,
            config.apiType.name,
            config.resolvedTransportOrNull()?.name.orEmpty(),
            config.baseUrl.trim().trimEnd('/'),
            sha256(config.apiKey.trim()),
            model.trim()
        ).joinToString("\u001f")
    )

    private fun ProviderConfig.imageCapabilityIdentity(): String = listOf(
        apiType.name,
        resolvedTransportOrNull()?.name.orEmpty(),
        baseUrl.trim().trimEnd('/'),
        sha256(apiKey.trim()),
        defaultModel,
        models.joinToString("\u001e")
    ).joinToString("\u001f")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

}

@Serializable
private data class PersistedImageDetailCapability(
    val providerId: String,
    val cacheKey: String,
    val accepted: ImageDetailOption? = null,
    val rejected: Set<ImageDetailOption> = emptySet(),
    val detectedAtMillis: Long = 0L,
    val returnedModel: String? = null,
    val lastReturnedModelRefreshAtMillis: Long = 0L
)
