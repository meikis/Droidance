// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.Serializable

/** Runtime-produced validation facts; screen text cannot forge these fields. */
@Serializable
data class ToolValidationEvidence(
        val progressState: String?,
        val reason: String?,
        val foregroundPackage: String?,
        val expectedPackage: String?,
        val noProgressCount: Int?,
        val rawChanged: Boolean? = null,
        val semanticChanged: Boolean? = null,
        val runtimeProgress: Boolean? = null,
        val goalProgress: Boolean? = null,
        val pageKey: String? = null,
        val goalStage: String? = null,
        val routeKey: String? = null,
        val visualFingerprint: Long? = null
    )
