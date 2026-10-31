// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

/** Storage tests observe validation calls; compiler behavior is covered by the runtime tests. */
internal class RecordingSkillScriptValidator : SkillScriptValidator {
    val calls = mutableListOf<Pair<String, String>>()

    override fun validate(path: String, source: String) {
        calls += path to source
    }
}
