// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsVisualHierarchySourceContractTest {
    private val settingsSource = File("src/main/java/com/droidance/ui/screens/SettingsScreen.kt").readText()
    private val chineseStrings = File("src/main/res/values-zh/strings.xml").readText()

    @Test
    fun settings_home_uses_short_labels_and_semantic_icons() {
        listOf(
            "DroidanceIcons.Bot",
            "DroidanceIcons.Bolt",
            "DroidanceIcons.Sliders",
            "DroidanceIcons.Shield",
            "DroidanceIcons.Palette",
            "DroidanceIcons.Languages",
            "DroidanceIcons.Storage",
            "DroidanceIcons.Info"
        ).forEach { icon -> assertTrue("Missing settings icon $icon", settingsSource.contains(icon)) }

        listOf("模型", "执行", "权限", "偏好", "主题", "语言", "数据")
            .forEach { label -> assertTrue("Missing short label $label", chineseStrings.contains(">$label<")) }

        val home = settingsSource.substringBefore("// ── 共用组件")
        listOf("settings_permissions", "settings_theme", "settings_language", "settings_data_controls", "settings_preferences_memory")
            .forEach { key -> assertTrue("Settings home should expose $key", home.contains("R.string.$key")) }
        val generalGroup = home.substringAfter("SettingsGroup(stringResource(R.string.settings_group_app))")
        assertTrue(generalGroup.indexOf("R.string.settings_permissions)") < generalGroup.indexOf("R.string.settings_theme)"))
        assertTrue(generalGroup.contains("overview.hasPendingPermissions"))
        assertTrue(generalGroup.contains("onClick = onNavigateToPermissions"))
        assertFalse(home.contains("R.string.settings_appearance"))
        assertFalse(home.contains("R.string.settings_usage"))
    }

    @Test
    fun settings_surfaces_use_neutral_icons_and_inset_edges() {
        val navItem = settingsSource.substringAfter("internal fun SettingsNavItem(")
            .substringBefore("internal fun SettingsPillButton(")

        assertTrue(settingsSource.contains("MaterialTheme.colorScheme.outline"))
        assertTrue(settingsSource.contains("end = endIndent"))
        assertTrue(settingsSource.contains("thickness = 0.5.dp"))
        assertTrue(settingsSource.contains("MaterialTheme.colorScheme.outlineVariant"))
        assertFalse(settingsSource.contains("border: BorderStroke?"))
        assertFalse(settingsSource.contains("border = border"))
        assertTrue(settingsSource.contains("defaultElevation = 0.dp"))
        assertTrue(navItem.contains("tint = MaterialTheme.colorScheme.onSurface"))
        assertFalse(navItem.contains("tint = MaterialTheme.colorScheme.onSurface.copy(alpha"))
        assertFalse(navItem.contains("colorScheme.tertiary"))
    }

    @Test
    fun immediate_actions_use_one_full_row_click_target_without_navigation_chevrons() {
        val actionItem = settingsSource.substringAfter("internal fun SettingsActionItem(")
            .substringBefore("internal fun SettingsSwitchItem(")

        assertTrue(actionItem.contains(".clickable(enabled = enabled, onClick = onClick)"))
        assertTrue(actionItem.contains("SettingsPillLabel(actionText)"))
        assertFalse(actionItem.contains("SettingsPillButton("))
        assertFalse(actionItem.contains("ChevronRight"))
    }
}
