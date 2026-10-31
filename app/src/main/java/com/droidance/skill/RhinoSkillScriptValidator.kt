// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.skill

import com.droidance.core.device.program.ProgramEngine
import com.droidance.core.knowledge.skill.SkillScriptValidator
import javax.inject.Inject

/** Uses the same compiler as program execution; no host capabilities or script code are run. */
class RhinoSkillScriptValidator @Inject constructor() : SkillScriptValidator {
    override fun validate(path: String, source: String) {
        try {
            ProgramEngine().validateSyntax(source, path)
        } catch (error: Exception) {
            throw IllegalArgumentException("SKILL_SCRIPT_SYNTAX_INVALID: ${ProgramEngine.formatError(error)}", error)
        }
    }
}
