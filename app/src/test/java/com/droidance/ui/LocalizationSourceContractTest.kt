// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalizationSourceContractTest {
    private val localizedSources = listOf(
        "src/main/java/com/droidance/ui/screens/HomeScreen.kt",
        "src/main/java/com/droidance/ui/screens/AutomationsScreen.kt",
        "src/main/java/com/droidance/ui/screens/SkillsScreen.kt",
        "src/main/java/com/droidance/ui/screens/SettingsScreen.kt",
        "src/main/java/com/droidance/ui/screens/SettingsSubScreens.kt",
        "src/main/java/com/droidance/ui/screens/SettingsAppearanceScreens.kt",
        "src/main/java/com/droidance/ui/screens/SettingsDataManagementScreen.kt",
        "src/main/java/com/droidance/ui/screens/SettingsUsageChart.kt",
        "src/main/java/com/droidance/ui/screens/SettingsAboutScreen.kt",
        "src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt",
        "src/main/java/com/droidance/ui/screens/ProviderListScreen.kt",
        "src/main/java/com/droidance/ui/screens/ProviderEditScreen.kt",
        "src/main/java/com/droidance/ui/screens/AgentsScreen.kt",
        "src/main/java/com/droidance/ui/screens/MemoryScreen.kt",
        "src/main/java/com/droidance/ui/screens/OnboardingScreen.kt",
        "src/main/java/com/droidance/ui/navigation/AppDrawerContent.kt",
        "src/main/java/com/droidance/ui/screens/home/ChatComposer.kt",
        "src/main/java/com/droidance/ui/screens/home/ComposerSelectionControls.kt",
        "src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt",
        "src/main/java/com/droidance/ui/components/ExecutionActionPresentation.kt"
    )

    private val hardcodedVisibleText = Regex(
        "(?:Text|contentDescription\\s*=|placeholder\\s*=\\s*\\{\\s*Text)\\s*\\(?\\s*\"[^\"\\n]*[\\p{IsHan}][^\"\\n]*"
    )

    @Test
    fun main_paths_do_not_embed_visible_chinese_text_directly_in_compose() {
        localizedSources.forEach { path ->
            val source = File(path).readText()
            assertFalse(path, hardcodedVisibleText.containsMatchIn(source))
        }
    }

    @Test
    fun chinese_and_english_resources_keep_matching_keys() {
        listOf(File("src/main/res"), File("../core/device/src/main/res")).forEach { resources ->
            val zhKeys = extractKeys(File(resources, "values-zh/strings.xml").readText())
            val enKeys = extractKeys(File(resources, "values/strings.xml").readText())
            assertEquals(resources.path, zhKeys, enKeys)
        }
    }

    @Test
    fun foreground_service_visible_text_comes_from_localized_resources() {
        val source = File("src/main/java/com/droidance/daemon/TaskExecutionService.kt").readText()
        listOf(
            "\"任务完成提醒\"",
            "\"当 Droidance 完成自动化任务时通知您\"",
            "\"正在执行任务...\"",
            "\"待命中 — 随时可用\"",
            "\"Droidance 常驻服务\"",
            "\"保持 Droidance 在后台运行，防止被系统杀掉\""
        ).forEach { forbidden ->
            assertFalse("TaskExecutionService must not hardcode visible text: $forbidden", source.contains(forbidden))
        }
        listOf(
            "ContextCompat.getContextForLanguage",
            "R.string.task_done_channel_name",
            "R.string.task_done_channel_description",
            "R.string.task_service_running_status",
            "R.string.task_service_standby_status",
            "R.string.task_service_channel_name",
            "R.string.task_service_channel_description"
        ).forEach { required ->
            assert(source.contains(required)) { "TaskExecutionService should use $required" }
        }
    }

    @Test
    fun automation_status_updates_use_localized_resources() {
        val path = "src/main/java/com/droidance/automation/AutomationCoordinator.kt"
        val source = File(path).readText()
        assertFalse(path, source.contains("\"正在执行: "))
        assertFalse(path, source.contains("\"待命中 — 随时可用\""))
        assert(source.contains("TaskExecutionService.startExecution")) {
            "$path should use TaskExecutionService.startExecution"
        }
        assert(source.contains("TaskExecutionService.stopExecution")) {
            "$path should use TaskExecutionService.stopExecution"
        }
    }

    @Test
    fun simplified_chinese_language_labels_are_not_mixed_with_english() {
        val zh = File("src/main/res/values-zh/strings.xml").readText()
        assertFalse(zh.contains("语言 Language"))
        assertFalse(zh.contains("Follow system"))
        assertFalse(zh.contains("中文 · English"))
    }

    private fun extractKeys(xml: String): Set<String> {
        return Regex("<(?:string|plurals) name=\"([^\"]+)\"")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toSet()
    }
}
