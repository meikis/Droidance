// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import com.droidance.core.agent.RequestedSkill
import kotlinx.serialization.json.*

/** Owns guided run cleanup and explicitly selected identities for one task only. */
class SkillTurnSession(private val engine: SkillEngine) : AutoCloseable {
    private val guided = linkedMapOf<String, String>()
    private val snapshots = linkedMapOf<String, SkillExecutionSnapshot>()
    private val selected = linkedSetOf<RequestedSkill>()

    @Synchronized
    fun isExplicit(snapshot: SkillExecutionSnapshot): Boolean =
        RequestedSkill(snapshot.skill.name, snapshot.revision, snapshot.skill.isDraft) in selected

    @Synchronized
    fun trackRun(runId: String, snapshot: SkillExecutionSnapshot, guidedToken: String? = null) {
        snapshots[runId] = snapshot
        if (guidedToken != null) guided[runId] = guidedToken
    }

    @Synchronized
    fun snapshot(runId: String): SkillExecutionSnapshot? = snapshots[runId]

    @Synchronized
    fun assessGuided(runId: String, outcome: String) {
        val token = guided[runId] ?: return
        engine.settleGuidedExecution(token, outcome)
        guided.remove(runId)
    }

    /** Bounded source data, retained in turn context; never executes a Skill or fills parameters. */
    @Synchronized
    fun preload(requests: List<RequestedSkill>, maxChars: Int): String {
        if (requests.isEmpty()) return ""
        selected.addAll(requests)
        val entries = mutableListOf<JsonElement>()
        var remaining = maxChars.coerceAtLeast(0)
        for (request in requests) {
            val identity = buildJsonObject {
                put("name", request.name); put("revision", request.revision); put("draft", request.draft)
            }
            // Keep a retrieval identity even when no body fits; source text is always marked partial.
            val snapshot = if (remaining > 0) engine.findExecutionSnapshot(request.name, request.draft) else null
            val entry = if (snapshot != null && snapshot.revision == request.revision) {
                val source = snapshot.files.getValue("SKILL.md")
                var end = minOf(source.length, remaining)
                if (end < source.length && end > 0 && source[end - 1].isHighSurrogate()) end--
                remaining -= end
                JsonObject(identity + buildJsonObject {
                    put("text", source.substring(0, end)); put("next_offset", end)
                    put("has_more", end < source.length)
                })
            } else JsonObject(identity + ("status" to JsonPrimitive(
                if (remaining <= 0) "read_required" else "unavailable_or_changed")))
            entries += entry
        }
        return "<selected_skills>\n${JsonArray(entries)}\n</selected_skills>"
    }

    @Synchronized
    override fun close() {
        var failure: Exception? = null
        guided.values.forEach { token ->
            try { engine.settleGuidedExecution(token) } catch (error: Exception) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        guided.clear()
        snapshots.clear()
        selected.clear()
        failure?.let { throw it }
    }
}
