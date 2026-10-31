// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.skill

import com.droidance.core.knowledge.skill.*

import com.droidance.skill.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillLifecycleSourceContractTest {
    @Test
    fun portablePackagesUseRevisionBoundProgramsAndCurrentTaskAssessments() {
        val engine = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/skill/SkillEngine.kt").readText()
        val archive = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/skill/SkillPackageArchive.kt").readText()
        val registrar = projectFile("app/src/main/java/com/droidance/tools/SkillToolRegistrar.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/SkillsScreen.kt").readText()

        assertTrue(archive.contains("path == \"SKILL.md\""))
        assertTrue(archive.contains("path.startsWith(\"scripts/\") && path.endsWith(\".js\")"))
        assertTrue(archive.contains("path.startsWith(\"references/\") && path.endsWith(\".md\")"))
        assertTrue(engine.contains("fun installSkillPackage("))
        assertTrue(engine.contains("parsed.version > old.skill.version"))
        assertTrue(engine.contains("fun publishDraft("))
        assertTrue(engine.contains("fun recordAssessment("))
        assertTrue(engine.contains("fun deleteSkill("))
        assertTrue(registrar.contains("snapshot.revision == args.required(\"revision\")"))
        assertTrue(registrar.contains("engine.validateParameters(snapshot.skill, parameters)"))
        assertTrue(executor.contains("runProgram = programTool::executeSkill"))
        assertTrue(registrar.contains("SKILL_ASSESSMENT_EVIDENCE_INVALID"))
        assertTrue(registrar.contains("SKILL_PROGRAM_NOT_COMPLETED"))
        assertTrue(registrar.contains("SKILL_CHANGED_DURING_VERIFICATION"))
        assertTrue(registrar.contains("engine.recordAssessment(snapshot, assessment)"))
        assertFalse(registrar.contains("CompletionState"))
        assertFalse(registrar.contains("CompletionResult"))
        assertFalse(registrar.contains("success_summary"))
        assertFalse(registrar.contains("loadScript("))
        assertFalse(projectFile("app/src/main/java/com/droidance/tools").resolve("ScriptToolRegistrar.kt").exists())
        assertFalse(projectFile("core/knowledge/src/main/kotlin/com/droidance").resolve("agent/ScriptExecutor.kt").exists())
        assertTrue(screen.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(screen.contains("viewModel.uninstall(skill)"))
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
