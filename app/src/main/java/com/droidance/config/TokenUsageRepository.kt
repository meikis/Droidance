// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*
import com.droidance.core.model.llm.ModelRequestUsage

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
    @ApplicationContext private val context: Context,
    private val runtimeClient: dagger.Lazy<com.droidance.runtime.RuntimeProcessClient>?
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
    val records: StateFlow<List<DailyUsage>> get() = runtimeClient?.get()?.takeUnless { it.isOwner }?.usage ?: _records.asStateFlow()

    @Serializable
    data class TurnUsage(val threadId: String, val turnId: String, val totals: TokenUsageTotals)

    private val _turnRecords = MutableStateFlow(loadTurnUsage())
    val turnRecords: StateFlow<List<TurnUsage>> get() = runtimeClient?.get()?.takeUnless { it.isOwner }?.turnUsage ?: _turnRecords.asStateFlow()

    /** 每次实际发出的模型 HTTP 请求只记一次；Token 与缓存只采用 Provider 返回值。 */
    @Synchronized
    fun recordRequest(usage: ModelRequestUsage) {
        val date = today()
        val key = json.encodeToString(listOf(date, usage.provider, usage.model))
        val current = load(key) ?: DailyUsage(
            date = date,
            provider = usage.provider,
            model = usage.model,
            promptTokens = 0,
            completionTokens = 0,
            calls = 0
        )
        val updated = current.addRequest(
            promptTokens = usage.inputTokens ?: 0,
            completionTokens = usage.outputTokens ?: 0,
            cachedInputTokens = usage.cachedInputTokens ?: 0,
            cacheFieldsReported = usage.cachedInputTokens != null
        )
        val editor = prefs.edit().putString(key, json.encodeToString(updated))
        val turn = usage.scope?.let { scope ->
            val previous = _turnRecords.value.firstOrNull { it.threadId == scope.threadId && it.turnId == scope.turnId }
            TurnUsage(scope.threadId, scope.turnId, (previous?.totals ?: TokenUsageTotals()).addRequest(usage))
        }
        if (turn != null) editor.putString(turnKey(turn), json.encodeToString(turn))
        editor.apply()
        if (turn != null) _turnRecords.value = _turnRecords.value.filterNot {
            it.threadId == turn.threadId && it.turnId == turn.turnId
        } + turn
        _records.value = loadAll()
    }

    /** Conversation deletion removes attribution while retaining the existing all-time daily statistics. */
    @Synchronized
    fun deleteConversationUsage(threadIds: Set<String>? = null) {
        val removed = _turnRecords.value.filter { threadIds == null || it.threadId in threadIds }
        val editor = prefs.edit()
        removed.forEach { editor.remove(turnKey(it)) }
        editor.apply()
        _turnRecords.value = _turnRecords.value - removed.toSet()
    }

    fun getAll(): List<DailyUsage> = records.value

    private fun loadAll(): List<DailyUsage> = prefs.all.filterKeys { !it.startsWith(TURN_PREFIX) }.values
        .mapNotNull { value ->
            (value as? String)?.let { encoded ->
                runCatching { json.decodeFromString<DailyUsage>(encoded) }.getOrNull()
            }
        }
        .sortedWith(compareBy<DailyUsage> { it.date }.thenBy { it.provider }.thenBy { it.model })

    private fun loadTurnUsage(): List<TurnUsage> = prefs.all.filterKeys { it.startsWith(TURN_PREFIX) }.values
        .mapNotNull { value -> (value as? String)?.let { runCatching { json.decodeFromString<TurnUsage>(it) }.getOrNull() } }

    private fun turnKey(turn: TurnUsage) = TURN_PREFIX + json.encodeToString(listOf(turn.threadId, turn.turnId))

    private fun load(key: String): DailyUsage? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<DailyUsage>(it) }.getOrNull() }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private companion object { const val TURN_PREFIX = "turn:" }
}
