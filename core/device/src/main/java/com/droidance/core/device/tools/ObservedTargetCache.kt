// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

class ObservedTargetCache {
    data class TargetEntry(
        val id: String,
        val role: String,
        val text: String,
        val bounds: List<Int>,
        val modelX: Int,
        val modelY: Int
    )

    private var targets: List<ObservedTarget> = emptyList()

    fun clear() {
        targets = emptyList()
    }

    fun cacheTargets(entries: List<TargetEntry>) {
        targets = entries.map { entry ->
            ObservedTarget(
                id = entry.id,
                role = entry.role,
                text = entry.text,
                bounds = entry.bounds,
                modelX = entry.modelX,
                modelY = entry.modelY
            )
        }
    }

    fun resolveModelPoint(targetId: String): Pair<Int, Int>? {
        val trimmed = targetId.trim()
        if (trimmed.isBlank()) return null
        val match = targets.firstOrNull { it.matches(trimmed) } ?: return null
        return match.modelX to match.modelY
    }

    fun cacheFromScreenState(stateJson: String) {
        targets = try {
            Json.parseToJsonElement(stateJson).jsonObject["targets"]?.jsonArray
                ?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val bounds = obj["bounds"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.intOrNull }
                        .orEmpty()
                    if (bounds.size != 4) return@mapNotNull null
                    val cx = ((bounds.getOrNull(0) ?: 0) + (bounds.getOrNull(2) ?: 0)) / 2
                    val cy = ((bounds.getOrNull(1) ?: 0) + (bounds.getOrNull(3) ?: 0)) / 2
                    ObservedTarget(
                        id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        role = obj["role"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        bounds = bounds,
                        modelX = cx,
                        modelY = cy
                    )
                }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun describeAt(x: Int, y: Int): String? {
        val target = targets
            .map { target ->
                val distance = if (target.contains(x, y)) {
                    0
                } else {
                    abs(target.cx - x) + abs(target.cy - y)
                }
                target to distance
            }
            .filter { (_, distance) -> distance <= MAX_TARGET_DISTANCE_PX }
            .minByOrNull { (_, distance) -> distance }
            ?.first ?: return null

        val role = target.role.ifBlank { "unknown" }
        val text = target.text.trim().take(MAX_LABEL_TEXT)
        return if (text.isNotBlank()) {
            "target[$role] \"$text\" #${target.id.ifBlank { "?" }}"
        } else {
            "target[$role] #${target.id.ifBlank { "?" }}"
        }
    }

    private data class ObservedTarget(
        val id: String,
        val role: String,
        val text: String,
        val bounds: List<Int>,
        val modelX: Int = ((bounds.getOrNull(0) ?: 0) + (bounds.getOrNull(2) ?: 0)) / 2,
        val modelY: Int = ((bounds.getOrNull(1) ?: 0) + (bounds.getOrNull(3) ?: 0)) / 2
    ) {
        val cx: Int = ((bounds.getOrNull(0) ?: 0) + (bounds.getOrNull(2) ?: 0)) / 2
        val cy: Int = ((bounds.getOrNull(1) ?: 0) + (bounds.getOrNull(3) ?: 0)) / 2

        fun contains(x: Int, y: Int): Boolean {
            val left = bounds.getOrNull(0) ?: return false
            val top = bounds.getOrNull(1) ?: return false
            val right = bounds.getOrNull(2) ?: return false
            val bottom = bounds.getOrNull(3) ?: return false
            return x in left..right && y in top..bottom
        }

        fun matches(query: String): Boolean {
            val q = query.trim().lowercase()
            val selfId = id.trim().lowercase()
            if (selfId == q) return true
            if (selfId.replace("_", "") == q.replace("_", "")) return true
            val suffix = selfId.removePrefix("text_").removePrefix("t")
            if (suffix.isNotBlank() && (q.removePrefix("text_").removePrefix("t") == suffix || q == suffix)) {
                return true
            }
            return false
        }
    }

    private companion object {
        const val MAX_TARGET_DISTANCE_PX = 180
        const val MAX_LABEL_TEXT = 28
    }
}
