// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

/** Exact immutable source package; a run never reloads files after a concurrent edit. */
data class SkillExecutionSnapshot internal constructor(
    val skill: SkillInfo,
    val files: Map<String, String>,
    val revision: String
)
