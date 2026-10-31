// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWirelessDebuggingSourceContractTest {
    @Test
    fun wirelessDebuggingOpenPrefersTheDeepPageAndFallsBackToDeveloperOptions() {
        val viewModel = projectFile(
            "app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt"
        ).readText()
        val navigator = projectFile(
            "app/src/main/java/com/droidance/settings/SystemSettingsNavigator.kt"
        ).readText()

        assertTrue(viewModel.contains("openWirelessDebuggingSettings()"))
        assertTrue(viewModel.contains("systemSettingsNavigator.openWirelessDebuggingSettings()"))

        val open = navigator.substringAfter("fun openWirelessDebuggingSettings() {")
            .substringBefore("private fun wirelessDebuggingDeepLinkIntent()")
        val deepLink = navigator.substringAfter("private fun wirelessDebuggingDeepLinkIntent(): Intent? {")
            .substringBefore("private fun startActivitySafely")

        // 先试「无线调试」详情页（开关 + 使用配对码配对设备），失败才回退开发者选项。
        assertTrue(open.contains("wirelessDebuggingDeepLinkIntent()"))
        assertTrue(open.contains("startActivitySafely(deepLink)"))
        assertTrue(open.contains("Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS"))
        // 直达页 = Settings 的 SubSettings host + WirelessDebuggingFragment（AOSP 通用类名）。
        assertTrue(navigator.contains("""const val SETTINGS_SUB_SETTINGS = "com.android.settings.SubSettings""""))
        assertTrue(
            navigator.contains(
                """const val WIRELESS_DEBUGGING_FRAGMENT =
            "com.android.settings.development.WirelessDebuggingFragment""""
            )
        )
        assertTrue(navigator.contains("""const val EXTRA_SHOW_FRAGMENT = ":settings:show_fragment""""))
        assertTrue(
            navigator.contains(
                """const val EXTRA_SHOW_FRAGMENT_AS_SUBSETTING = ":settings:show_fragment_as_subsetting""""
            )
        )
        assertTrue(deepLink.contains("SETTINGS_PACKAGE"))
        assertTrue(deepLink.contains("SETTINGS_SUB_SETTINGS"))
        assertTrue(deepLink.contains("WIRELESS_DEBUGGING_FRAGMENT"))
        assertTrue(deepLink.contains("EXTRA_SHOW_FRAGMENT_AS_SUBSETTING"))
        // 启动前必须确认组件已导出且启用，否则 AOSP 原生会直接拒绝第三方。
        assertTrue(deepLink.contains("getActivityInfo("))
        assertTrue(deepLink.contains("PackageManager.MATCH_DISABLED_COMPONENTS"))
        assertTrue(deepLink.contains("info.exported"))
        assertTrue(deepLink.contains("info.enabled"))
        // 启动失败（SecurityException/ActivityNotFound）不能崩，回退开发者选项。
        assertTrue(navigator.contains("runCatching { context.startActivity(intent) }.isSuccess"))
    }

    @Test
    fun wirelessDebuggingDeepLinkNeverHardCodesBusinessCopyIntoKotlin() {
        val navigator = projectFile(
            "app/src/main/java/com/droidance/settings/SystemSettingsNavigator.kt"
        ).readText()
        // 深链只带系统组件与 fragment 类名，不内嵌任何中文字符串作为规则/文案。
        val deepLink = navigator.substringAfter("private fun wirelessDebuggingDeepLinkIntent(): Intent? {")
            .substringBefore("private fun startActivitySafely")
        assertFalse(deepLink.contains("[\u4e00-\u9fa5]".toRegex()))
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
