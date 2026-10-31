// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsManagementSourceContractTest {
    @Test
    fun skillsUiSupportsEditingTogglingAndMetadataWithoutAdvancedAgentSettings() {
        val engine = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/skill/SkillEngine.kt").readText()
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SkillsViewModel.kt").readText()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/SkillsScreen.kt").readText()

        assertTrue(engine.contains("fun updateSkill("))
        assertTrue(engine.contains("fun setSkillEnabled("))
        assertTrue(viewModel.contains("fun startEditing("))
        assertTrue(viewModel.contains("fun saveEditing("))
        assertTrue(viewModel.contains("fun setAutoInvocation("))
        assertTrue(screen.contains("viewModel.startEditing(skill) }, enabled = !skill.isReadOnly"))
        assertTrue(screen.contains("SkillEditor(editor, viewModel, working, snackbar)"))
        assertTrue(screen.contains("onValueChange = viewModel::updateEditingBody"))
        assertTrue(screen.contains("onClick = viewModel::saveEditing"))
        assertTrue(screen.contains("SkillDetailPage("))
        assertTrue(screen.contains("onSelectSkill(selectedSkill)"))
        assertTrue(screen.contains("R.string.skill_source_system"))
        assertTrue(screen.contains("R.string.skill_assessment_failed"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
