// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationUiSourceContractTest {
    @Test
    fun drawerTaskDestinationRendersTheNewAutomationScreen() {
        val navigation = File("src/main/java/com/droidance/ui/navigation/AppNavigation.kt").readText()
        val destination = navigation.substringAfter("composable(SubRoutes.TASKS)")
            .substringBefore("// ====== 技能")

        assertTrue(destination.contains("AutomationsScreen("))
        assertFalse(destination.contains("TasksScreen("))
    }

    @Test
    fun visibleAutomationCreationHasNoLegacyScriptOrImmediateTaskBinding() {
        val screen = File("src/main/java/com/droidance/ui/screens/AutomationsScreen.kt").readText()
        val scheduleFields = File("src/main/java/com/droidance/ui/screens/AutomationScheduleFields.kt").readText()
        val skills = File("src/main/java/com/droidance/ui/screens/SkillsScreen.kt").readText()

        assertTrue(screen.contains("generateDraft(prompt)"))
        assertTrue(scheduleFields.contains("AutomationSchedule("))
        assertFalse(screen.contains("ScriptExecutor"))
        assertFalse(screen.contains("TaskType.IMMEDIATE"))
        assertFalse(skills.contains("TasksViewModel"))
    }
}
