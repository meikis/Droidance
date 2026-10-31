// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

/** Compiles Skill JavaScript with the execution runtime's grammar without executing it. */
fun interface SkillScriptValidator {
    fun validate(path: String, source: String)
}
