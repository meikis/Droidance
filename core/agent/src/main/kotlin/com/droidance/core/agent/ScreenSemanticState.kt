// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

/**
 * Compact semantic state for the CUA loop.
 *
 * It is intentionally generic: page and route keys are derived from runtime
 * signals, never from app-specific business rules or model-authored control
 * metadata.
 */
data class ScreenSemanticState(
    val foregroundPackage: String?,
    val foregroundActivityOrWindow: String?,
    val screenHash: Long?,
    val visibleTextDigest: String?,
    val pageKey: String,
    val modalKey: String? = null,
    val inputFocusKey: String? = null,
    val goalStage: String? = null,
    val pageClass: String? = null,
    val evidenceKey: String? = null
) {
    fun hasStateChangeComparedTo(before: ScreenSemanticState?): Boolean {
        if (before == null) return false
        return foregroundPackage != before.foregroundPackage ||
            foregroundActivityOrWindow != before.foregroundActivityOrWindow ||
            pageKey != before.pageKey ||
            modalKey != before.modalKey ||
            inputFocusKey != before.inputFocusKey ||
            visibleTextDigest != before.visibleTextDigest
    }

    /** Only explicit goal-stage or evidence transitions count as user-goal progress. */
    fun hasGoalProgressComparedTo(before: ScreenSemanticState?): Boolean {
        if (before == null) return false
        val beforeStage = before.goalStage.normalizedOrNull()
        val afterStage = goalStage.normalizedOrNull()
        if (beforeStage != null && afterStage != null && beforeStage != afterStage) return true

        val beforeEvidence = before.evidenceKey.normalizedOrNull()
        val afterEvidence = evidenceKey.normalizedOrNull()
        if (afterEvidence != null && beforeEvidence != afterEvidence) return true

        return false
    }

    fun routeKey(
        actionIntent: String?,
        targetKey: String?,
        normalizedTextIntent: String?
    ): String {
        return listOfNotNull(
            foregroundPackage.normalizedOrNull()?.let { "pkg=$it" },
            "page=$pageKey",
            goalStage.normalizedOrNull()?.let { "stage=$it" },
            actionIntent.normalizedOrNull()?.let { "intent=$it" },
            targetKey.normalizedOrNull()?.let { "target=$it" },
            normalizedTextIntent.normalizedOrNull()?.let { "text=$it" }
        ).joinToString("|")
    }

    companion object {
        fun from(
            foregroundPackage: String?,
            foregroundActivityOrWindow: String?,
            screenHash: Long?,
            screenStateText: String?
        ): ScreenSemanticState {
            val foreground = foregroundPackage.normalizedOrNull()
            val activity = foregroundActivityOrWindow.normalizedOrNull()
            val textDigest = screenStateText.visibleTextDigest()
            val pageKey = when {
                activity != null -> "activity:$activity"
                foreground != null && textDigest != null -> "text:$foreground:$textDigest"
                foreground != null -> "foreground:$foreground"
                textDigest != null -> "text:unknown:$textDigest"
                else -> "screen:unknown"
            }
            return ScreenSemanticState(
                foregroundPackage = foreground,
                foregroundActivityOrWindow = activity,
                screenHash = screenHash,
                visibleTextDigest = textDigest,
                pageKey = pageKey
            )
        }
    }
}

private fun String?.visibleTextDigest(): String? = digestOrNull("txt")

private fun String?.digestOrNull(prefix: String): String? {
    val normalized = normalizedOrNull() ?: return null
    return "$prefix:${Integer.toHexString(normalized.hashCode())}"
}

private fun String?.normalizedOrNull(): String? {
    return this
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(160)
        ?.takeIf { it.isNotBlank() }
}
