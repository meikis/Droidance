// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenUsageRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("droidance_token_usage", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DailyUsage(
        val date: String,
        val provider: String,
        val model: String = "",
        val promptTokens: Long,
        val completionTokens: Long,
        val calls: Long,
        val cachedInputTokens: Long = 0,
        val cacheReportedInputTokens: Long = 0,
        val cacheReportedCalls: Long = 0
    ) {
        internal fun addRequest(
            promptTokens: Long,
            completionTokens: Long,
            cachedInputTokens: Long,
            cacheFieldsReported: Boolean
        ): DailyUsage {
            val prompt = promptTokens.coerceAtLeast(0)
            val completion = completionTokens.coerceAtLeast(0)
            val cached = if (cacheFieldsReported) cachedInputTokens.coerceIn(0, prompt) else 0
            return copy(
                promptTokens = this.promptTokens + prompt,
                completionTokens = this.completionTokens + completion,
                calls = calls + 1,
                cachedInputTokens = this.cachedInputTokens + cached,
                cacheReportedInputTokens = cacheReportedInputTokens +
                    if (cacheFieldsReported) prompt else 0,
                cacheReportedCalls = cacheReportedCalls +
                    if (cacheFieldsReported) 1 else 0
            )
        }
    }

    private val _records = MutableStateFlow(loadAll())
    val records: StateFlow<List<DailyUsage>> = _records.asStateFlow()

    /** 每次实际发出的模型 HTTP 请求只记一次；Token 与缓存只采用 Provider 返回值。 */
    @Synchronized
    fun recordRequest(
        provider: String,
        model: String,
        promptTokens: Long = 0,
        completionTokens: Long = 0,
        cachedInputTokens: Long = 0,
        cacheFieldsReported: Boolean = false
    ) {
        val date = today()
        val key = json.encodeToString(listOf(date, provider, model))
        val current = load(key) ?: DailyUsage(
            date = date,
            provider = provider,
            model = model,
            promptTokens = 0,
            completionTokens = 0,
            calls = 0
        )
        val updated = current.addRequest(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedInputTokens = cachedInputTokens,
            cacheFieldsReported = cacheFieldsReported
        )
        prefs.edit().putString(key, json.encodeToString(updated)).apply()
        _records.value = loadAll()
    }

    fun getAll(): List<DailyUsage> = records.value

    private fun loadAll(): List<DailyUsage> = prefs.all.values
        .mapNotNull { value ->
            (value as? String)?.let { encoded ->
                runCatching { json.decodeFromString<DailyUsage>(encoded) }.getOrNull()
            }
        }
        .sortedWith(compareBy<DailyUsage> { it.date }.thenBy { it.provider }.thenBy { it.model })

    private fun load(key: String): DailyUsage? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<DailyUsage>(it) }.getOrNull() }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}
