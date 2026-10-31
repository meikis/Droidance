// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSimplificationSourceContractTest {
    @Test
    fun typography_uses_compact_sizes_and_zero_letter_spacing() {
        val typography = projectFile("app/src/main/java/com/droidance/ui/theme/Typography.kt").readText()

        assertTrue(typography.contains("displayLarge = TextStyle("))
        assertTrue(typography.contains("fontSize = 28.sp"))
        assertFalse(typography.contains("(-0.25).sp"))
        assertFalse(typography.contains("0.5.sp"))
        assertFalse(typography.contains("0.25.sp"))
        assertFalse(typography.contains("0.15.sp"))
    }

    @Test
    fun shared_neutral_design_components_exist() {
        val components = projectFile("app/src/main/java/com/droidance/ui/components/DroidanceDesignComponents.kt").readText()

        assertTrue(components.contains("fun DroidancePanel("))
        assertTrue(components.contains("fun DroidanceDialog("))
        assertTrue(components.contains("tonalElevation = 0.dp"))
        assertTrue(components.contains("shadowElevation = 0.dp"))
    }

    @Test
    fun home_empty_state_is_left_aligned_and_compact() {
        val home = projectFile("app/src/main/java/com/droidance/ui/screens/HomeScreen.kt").readText()
        val welcome = home.substringAfter("private fun WelcomeMessage(").substringBefore("\n@")

        assertTrue(welcome.contains("horizontalAlignment = Alignment.Start"))
        assertTrue(welcome.contains("textAlign = TextAlign.Start"))
        assertTrue(welcome.contains("MascotMood.DANCING"))
        assertTrue(welcome.contains("MascotStage("))
        assertFalse(welcome.contains("RobotMascot("))
        assertFalse(welcome.contains("horizontalAlignment = Alignment.CenterHorizontally"))
        assertFalse(welcome.contains("vertical = 32.dp"))
    }

    @Test
    fun overlay_visuals_are_neutral_not_rainbow_or_heart_based() {
        val touch = projectFile("app/src/main/java/com/droidance/ui/overlay/TouchIndicatorView.kt").readText()
        val status = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()

        assertFalse(touch.contains("drawHeart("))
        assertFalse(touch.contains("drawStar("))
        assertFalse(status.contains("hueShift"))
        assertFalse(status.contains("SweepGradient"))
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
