// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

class ThemePaletteTest {
    @Test
    fun canvas_tokens_keep_soft_light_and_neutral_dark_backgrounds() {
        assertEquals(0xFFEFEFEC.toInt(), DroidanceLightBackground.toArgb())
        assertEquals(0xFF101010.toInt(), DroidanceBackground.toArgb())
    }

    @Test
    fun light_surfaces_step_up_in_small_increments_without_pure_white() {
        assertTrue(LightCanvas.luminance() < LightCard.luminance())
        assertTrue(LightCard.luminance() < LightFloating.luminance())
        assertTrue(LightFloating.luminance() < Neutral0.luminance())
        assertTrue(LightContainerHighest.luminance() < LightContainerHigh.luminance())
        assertTrue(LightContainerHigh.luminance() < LightContainer.luminance())
        assertTrue(LightContainer.luminance() < LightContainerLow.luminance())
    }

    @Test
    fun accent_and_semantic_colors_remain_distinct_from_monochrome_surfaces() {
        assertEquals(0xFF0A84FF.toInt(), DroidancePrimary.toArgb())
        assertEquals(0xFF0A84FF.toInt(), DroidanceInfo.toArgb())
        assertEquals(0xFF34C759.toInt(), DroidanceSuccess.toArgb())
        assertEquals(0xFFFF9F0A.toInt(), DroidanceWarning.toArgb())
        assertEquals(0xFFFF3B30.toInt(), DroidanceError.toArgb())
    }

    @Test
    fun dark_theme_foregrounds_stay_bright_enough_for_black_surfaces() {
        assertTrue(DroidanceOnBackground.red > 0.9f)
        assertTrue(DroidanceOnBackground.red < Neutral50.red)
        assertEquals(DroidanceOnBackground, DroidanceOnSurface)
    }

    @Test
    fun text_selection_uses_accent_blue_not_the_inherited_near_black_primary() {
        val theme = projectFile("app/src/main/java/com/droidance/ui/theme/Theme.kt").readText()

        // 真因：Compose 没覆盖 LocalTextSelectionColors 时默认继承 colorScheme.primary；浅色主题
        // primary=石墨黑(Neutral900，见上方 LightColorScheme)，选中文字会像涂了一块深黑色。
        // AccentBlue 的注释本就写明"选中"是其用途之一，这里必须显式覆盖成蓝色高亮。
        assertTrue(theme.contains("LocalTextSelectionColors provides selectionColors"))
        assertTrue(theme.contains("handleColor = AccentBlue"))
        assertTrue(theme.contains("backgroundColor = AccentBlue.copy(alpha = 0.35f)"))
    }

    @Test
    fun switches_use_the_active_theme_primary_colors() {
        val source = projectFile("app/src/main/java/com/droidance/ui/components/DroidanceDesignComponents.kt").readText()

        assertTrue(source.contains("checkedTrackColor = MaterialTheme.colorScheme.primary"))
        assertTrue(source.contains("checkedThumbColor = MaterialTheme.colorScheme.onPrimary"))
        assertFalse(source.contains("checkedTrackColor = DroidancePrimary"))
    }

    @Test
    fun device_capabilities_do_not_bypass_the_active_theme_palette() {
        val source = projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()

        assertTrue(source.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertFalse(source.contains("DroidancePrimary"))
        assertFalse(source.contains("Color.Blue"))
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
