// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.skill

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoSkillScriptValidatorTest {
    @Test(timeout = 2000)
    fun sourceValidationDoesNotRunEffectsOrCheckRuntimeGlobals() {
        RhinoSkillScriptValidator().validate("scripts/workflow.js", """
            throw new Error('must not execute');
            while (true) {}
            android.call('screen_act', {});
            workspace.write('result.txt', 'must not write');
        """.trimIndent())
    }

    @Test
    fun invalidSourceReportsTheOriginalPackageFileAndLine() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RhinoSkillScriptValidator().validate("scripts/nested/workflow.js", "var valid=1;\nvar broken=;")
        }
        assertTrue(error.message.orEmpty().contains("SKILL_SCRIPT_SYNTAX_INVALID"))
        assertTrue(error.message.orEmpty().contains("scripts/nested/workflow.js:2"))
        assertTrue(error.message.orEmpty().contains("var broken=;"))
    }
}
