// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.settings

import android.content.Context
import com.droidance.core.model.llm.ReasoningModelDiscovery
import com.droidance.core.model.llm.models.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReasoningCapabilityRepository @Inject constructor(
    @ApplicationContext context: Context,
    httpClient: OkHttpClient
) {
    private val prefs = context.getSharedPreferences("droidance_reasoning_capabilities", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val catalog = ReasoningCatalog(runCatching {
        context.assets.open("model_capabilities/reasoning.json").bufferedReader().use { it.readText() }
    }.getOrDefault("{}"))
    private val cache = ReasoningCapabilityCache(catalog, runCatching {
        json.decodeFromString<ReasoningCacheSnapshot>(prefs.getString("cache", "{}").orEmpty())
    }.getOrDefault(ReasoningCacheSnapshot()))
    private val discovery = ReasoningModelDiscovery(httpClient)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    fun capability(config: ProviderConfig, model: String): ReasoningCapability = cache.capability(config, model)

    suspend fun refresh(config: ProviderConfig) = withContext(Dispatchers.IO) {
        if (!config.isTransportConfigured() || config.baseUrl.isBlank()) return@withContext
        val key = reasoningProviderKey(config)
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        if (!mutex.tryLock()) return@withContext
        try {
            if (!cache.shouldRefresh(config)) return@withContext
            cache.recordAttempt(config)
            persist()
            val raw = discovery.fetch(config) ?: return@withContext
            (config.models + config.defaultModel).filter(String::isNotBlank).distinct().forEach { model ->
                ReasoningMetadata.parse(raw, model, catalog.parameter(config.baseUrl))?.let {
                    cache.recordMetadata(config, model, it)
                }
            }
            persist()
            _revision.update { it + 1 }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional discovery must not fail the settings page or a foreground task.
        } finally {
            mutex.unlock()
        }
    }

    fun recordRejected(config: ProviderConfig, model: String) {
        cache.recordRejected(config, model)
        persist()
        _revision.update { it + 1 }
    }

    @Synchronized private fun persist() {
        runCatching { prefs.edit().putString("cache", json.encodeToString(cache.snapshot())).apply() }
    }
}
