// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.LocalActionEvidence.ProgressState

object LocalProgressClassifier {
    fun classify(
        toolName: String,
        expectedPackage: String?,
        beforeForegroundPackage: String?,
        foregroundPackage: String?,
        launchedUnverified: Boolean,
        beforeHash: Long?,
        afterHash: Long?,
        textChanged: Boolean,
        beforeSemanticState: ScreenSemanticState? = null,
        afterSemanticState: ScreenSemanticState? = null
    ): ProgressState {
        if (!expectedPackage.isNullOrBlank() && foregroundPackage != null && foregroundPackage != expectedPackage) {
            return ProgressState.ROUTE_FAILED
        }
        if (!expectedPackage.isNullOrBlank() && foregroundPackage == expectedPackage && launchedUnverified) {
            val screenChanged = beforeHash != null && afterHash != null && beforeHash != afterHash
            if (!screenChanged && !textChanged) {
                // A route can legitimately land on an already-rendered page (for example
                // relaunching an app whose target page is still visible).  The matching
                // foreground package proves that we did not land in the wrong app, but it
                // does not prove the page semantics.  Keep this as an unverified launch so
                // the caller performs an observation instead of sending recovery clicks
                // that can drift away from an otherwise valid page.
                return ProgressState.ROUTE_LAUNCHED_UNVERIFIED
            }
            return ProgressState.ROUTE_LAUNCHED_UNVERIFIED
        }
        if (!expectedPackage.isNullOrBlank() && foregroundPackage == expectedPackage) {
            return ProgressState.ROUTE_VERIFIED
        }
        if (expectedPackage.isNullOrBlank() && launchedUnverified) {
            // Explicit platform Intents may resolve to OEM Settings packages that Droidance
            // cannot predict in advance. An accepted launch without a target package remains
            // unverified until the next grounded observation; a static frame is not a failure.
            return ProgressState.ROUTE_LAUNCHED_UNVERIFIED
        }
        if (!beforeForegroundPackage.isNullOrBlank() &&
            !foregroundPackage.isNullOrBlank() &&
            beforeForegroundPackage != foregroundPackage
        ) {
            return ProgressState.CONTEXT_CHANGED
        }
        if (
            afterSemanticState?.hasGoalProgressComparedTo(beforeSemanticState) == true
        ) {
            return ProgressState.GOAL_PROGRESS
        }
        if (afterSemanticState?.hasStateChangeComparedTo(beforeSemanticState) == true) {
            return ProgressState.STATE_CHANGED
        }
        if (textChanged) return ProgressState.STATE_CHANGED

        val screenChanged = beforeHash != null && afterHash != null && beforeHash != afterHash
        if (screenChanged) {
            return ProgressState.RAW_CHANGED
        }
        if (beforeHash != null && afterHash != null) return ProgressState.UNCHANGED
        return ProgressState.UNKNOWN
    }
}
