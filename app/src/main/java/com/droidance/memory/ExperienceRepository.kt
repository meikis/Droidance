// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.knowledge.skill.SkillCandidateFeedback
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.config.RuleConfigManager
import com.droidance.core.agent.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Immediate task learning and deterministic completion projection. Neither path calls a model. */
@Singleton
class ExperienceRepository @Inject constructor(
    database: MemoryCandidateDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val events: AgentEventStore,
    private val rules: RuleConfigManager,
    @ApplicationScope private val scope: CoroutineScope
) : ExperienceLearningStore {
    private val dao = database.experienceDao()
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }
    val storageFailed = MutableStateFlow(false)
    fun watch(offset: Int = 0) = dao.watch(30, offset)
    fun failures() = dao.failures(20)

    fun resume(turnId: String? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                if (turnId == null) reindex()
                val pendingTurns = if (turnId != null) listOf(turnId)
                    else events.eventsByKind(AgentEventKind.TURN_COMPLETED).mapNotNull { it.turnId }.distinct()
                for (pendingTurn in pendingTurns) {
                    if (dao.receipt("turn:$pendingTurn") == null) project(pendingTurn)
                }
                storageFailed.value = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { storageFailed.value = true }
        }
    }

    /** Settles completion proposals before returning only this Turn's confirmed method writes. */
    suspend fun finalizeTurn(turnId: String): Set<String> = try {
        project(turnId).also { storageFailed.value = false }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { storageFailed.value = true; emptySet() }

    private suspend fun project(turnId: String): Set<String> = mutex.withLock {
        val key = "turn:$turnId"
        val history = events.eventsForTurn(turnId)
        if (history.none { it.eventKind == AgentEventKind.TURN_COMPLETED }) return@withLock emptySet()
        val policy = rules.harnessPolicy().memoryContext
        val proposals = ExperienceProjection.project(history, turnId, policy.experienceMaxUpdates, policy.experienceMaxChars)
        if (dao.receipt(key) == null) {
            for (proposal in proposals) {
                val record = proposal.record
                val row = record?.let { ExperienceEntity(it.update.id, it.revision, json.encodeToString(it), it.update.title,
                    it.update.package_name, true, false, System.currentTimeMillis()) }
                dao.apply(proposal.key, turnId, row, record?.update?.let(::indexTokens).orEmpty(),
                    proposal.error, System.currentTimeMillis())
            }
            dao.record(ExperienceReceiptEntity(key, turnId, "TURN_PROJECTED", System.currentTimeMillis()))
        }
        val sources = ExperienceReflectionSources.collect(history, turnId, proposals)
        ExperienceReflectionSources.applied(sources, sources.associate { it.receiptKey to dao.receipt(it.receiptKey)?.outcome })
    }

    data class ContextHint(val ref: String, val packageName: String, val title: String, val feedback: SkillCandidateFeedback)

    fun appLabel(packageName: String): String? = packageName.takeIf(String::isNotBlank)?.let { name ->
        runCatching {
            val manager = context.packageManager
            manager.getApplicationInfo(name, 0).loadLabel(manager).toString().takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun indexTokens(update: ExperienceUpdate) =
        ExperienceRecall.indexTokens(update, appLabel(update.package_name)).joinToString(" ")

    /** Installed labels and tokenization can change between launches; the index is derived data. */
    private suspend fun reindex() = mutex.withLock {
        for (row in dao.indexed()) {
            val update = runCatching { json.decodeFromString<ExperienceRecord>(row.document).update }.getOrNull() ?: continue
            dao.reindex(row.id, indexTokens(update))
        }
    }

    /** The foreground package and its label lead the query when the current task has observed one. */
    suspend fun contextHints(query: String, foregroundPackage: String?, limit: Int): List<ContextHint> = try {
        search(query, limit, foregroundPackage = foregroundPackage).map { row ->
            val ref = "experience:${row.id}:${row.revision}"
            ContextHint(ref, row.packageName, row.title, recentFeedback(row))
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { storageFailed.value = true; emptyList() }

    suspend fun search(query: String, limit: Int, offset: Int = 0, foregroundPackage: String? = null): List<ExperienceEntity> {
        val size = limit.coerceIn(1, 11)
        val context = listOfNotNull(foregroundPackage?.takeIf(String::isNotBlank), foregroundPackage?.let(::appLabel))
        val tokens = ExperienceRecall.queryTokens(query, context)
        if (tokens.isEmpty()) return dao.recent(size, offset)
        val policy = rules.harnessPolicy().memoryContext
        val hits = dao.match(tokens.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" },
            maxOf(policy.experienceRankCandidates, offset + size))
        val rows = hits.associate { it.row.id to it.row }
        val ranked = ExperienceRecall.rank(hits.map { hit ->
            val feedback = recentFeedback(hit.row)
            ExperienceRecall.Candidate(hit.row.id, hit.searchTokens.split(' ').toSet(), feedback.succeeded,
                feedback.failed, hit.row.updatedAt)
        }, tokens, ExperienceRecall.Weights(policy.experienceRankSuccessWeight, policy.experienceRankFailureWeight))
        return ranked.drop(offset).take(size).map { rows.getValue(it.id) }
    }

    suspend fun formatSearch(query: String, limit: Int, cursor: String?): String {
        val revision = dao.clock()
        val identity = java.security.MessageDigest.getInstance("SHA-256").digest(query.toByteArray()).joinToString("") { "%02x".format(it) }
        val offset = cursor?.split(':')?.let {
            require(it.size == 3 && it[0] == revision.toString() && it[1] == identity) { "MEMORY_CURSOR_STALE_OR_MISMATCHED" }
            it[2].toInt().also { number -> require(number >= 0) }
        } ?: 0
        val size = limit.coerceIn(1, 10)
        val hits = search(query, size + 1, offset)
        val feedback = hits.take(size).associate { it.id to recentFeedback(it) }
        require(dao.clock() == revision) { "MEMORY_CURSOR_STALE_OR_MISMATCHED" }
        return buildJsonObject {
            put("scope", "experience"); put("coverage", "indexed_methods_require_current_verification")
            put("has_more", hits.size > size)
            put("next_cursor", if (hits.size > size) JsonPrimitive("$revision:$identity:${offset + size}") else JsonNull)
            put("items", JsonArray(hits.take(size).map { row -> buildJsonObject {
                put("ref", "experience:${row.id}:${row.revision}"); put("title", row.title.take(rules.harnessPolicy().memoryContext.summaryMaxChars))
                put("package_applicability", row.packageName); put("requires_current_verification", true)
                put("recent_feedback", feedback.getValue(row.id).toJson())
            } }))
        }.toString()
    }

    private suspend fun recentFeedback(row: ExperienceEntity): SkillCandidateFeedback {
        val ref = "experience:${row.id}:${row.revision}"
        val uses = dao.usesAtRevision(row.id, row.revision, SkillCandidateFeedback.DEFAULT_WINDOW_LIMIT)
            .mapNotNull { runCatching { json.decodeFromString<ExperienceUseRecord>(it.document) }.getOrNull() }
        return SkillCandidateFeedback.fromExperienceUses(ref, uses)
    }

    fun installedVersions(packages: Set<String>): Map<String, String> = packages.mapNotNull { name ->
        runCatching {
            @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(name, 0)
            name to "${info.longVersionCode}:${info.versionName.orEmpty()}"
        }.getOrNull()
    }.toMap()

    suspend fun expand(ref: String): String? {
        val parts = ref.split(':')
        if (parts.size != 3 || parts[0] != "experience") return null
        val rev = parts[2].toLongOrNull() ?: return null
        val current = dao.get(parts[1])?.takeIf { it.enabled && !it.deleted } ?: return null
        // Control revisions also invalidate all outstanding proposals and references.
        if (current.revision != rev) return null
        val record = json.decodeFromString<ExperienceRecord>(current.document)
        val installed = installedVersions(setOf(current.packageName))
        val recorded = record.installedPackageVersions[current.packageName]
        val now = installed[current.packageName]
        return buildJsonObject {
            put("installed_version_status", when { recorded == null || now == null -> "unknown"; recorded == now -> "matched"; else -> "mismatched" })
            put("environment_scope", "local_package_catalog; execution-space version and screen still require verification")
            put("ref", ref); put("requires_current_verification", true)
            put("version_validation", "not_established_by_historical_method")
            put("record", Json.parseToJsonElement(current.document))
            put("uses", JsonArray(dao.uses(current.id, 5).map { Json.parseToJsonElement(it.document) }))
        }.toString()
    }

    override suspend fun current(ref: String): ExperienceRecord? {
        val parts = ref.split(':')
        if (parts.size != 3 || parts[0] != "experience") return null
        val row = dao.get(parts[1])?.takeIf { it.enabled && !it.deleted && it.revision == parts[2].toLongOrNull() } ?: return null
        return json.decodeFromString<ExperienceRecord>(row.document)
    }

    override suspend fun save(key: String, record: ExperienceRecord): String = mutex.withLock {
        val row = ExperienceEntity(record.update.id, record.revision, json.encodeToString(record),
            record.update.title, record.update.package_name, true, false, record.recordedAt)
        dao.apply(key, record.turnId, row, indexTokens(record.update), null, record.recordedAt)
        requireNotNull(dao.receipt(key)).outcome
    }

    override suspend fun use(useId: String): ExperienceUseRecord? =
        dao.use(useId)?.let { json.decodeFromString<ExperienceUseRecord>(it.document) }

    override suspend fun beginUse(record: ExperienceUseRecord): String = dao.beginUse(record.entity())

    override suspend fun assessUse(key: String, record: ExperienceUseRecord): String = dao.assessUse(key, record.entity())

    private fun ExperienceUseRecord.entity(): ExperienceUseEntity {
        val parts = experienceRef.split(':')
        require(parts.size == 3 && parts[0] == "experience")
        return ExperienceUseEntity(useId, parts[1], parts[2].toLong(), threadId, turnId,
            json.encodeToString(this), outcome, assessedAt ?: recordedAt)
    }

    suspend fun snapshotForSkill(ref: String): String? = expand(ref)?.let {
        "# Frozen experience source\n\nHistorical task-Agent assessment; verify current applicability and results.\n\n```json\n$it\n```\n"
    }
    suspend fun control(row: ExperienceEntity, enabled: Boolean, deleted: Boolean) {
        dao.control(row.id, row.revision, enabled, deleted, System.currentTimeMillis())
    }
}
