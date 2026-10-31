// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingRuntimeSourceContractTest {
    @Test
    fun onboardingUsesThemeBackgroundAndCompactInsetGroups() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()

        assertTrue(screen.contains("containerColor = MaterialTheme.colorScheme.background"))
        assertTrue(screen.contains("contentWindowInsets = WindowInsets.safeDrawing"))
        assertTrue(screen.contains("SettingsCardGroup(modifier = Modifier.fillMaxWidth())"))
        assertTrue(screen.contains("SettingsInsetDivider(startIndent = 62.dp, endIndent = 14.dp)"))
        assertTrue(screen.contains(".navigationBarsPadding()"))
        assertTrue(screen.contains(".heightIn(min = 54.dp)"))
        assertTrue(screen.contains("shape = AppShapes.Large"))
        assertTrue(screen.contains("private fun OnboardingLoading()"))
        assertTrue(screen.contains("Column(Modifier.fillMaxWidth())"))
        assertFalse(screen.contains(".padding(horizontal = 20.dp)"))
    }

    @Test
    fun onboardingHeroUsesTheFullMascotInALeftToRightIntroduction() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val hero = screen.substringAfter("private fun OnboardingHero()").substringBefore("private fun OnboardingLoading")

        assertTrue(hero.contains("Row("))
        assertTrue(hero.contains("AnimatedMascot("))
        assertTrue(hero.contains("MascotFraming.FULL"))
        assertTrue(hero.contains("horizontalAlignment = Alignment.Start"))
        assertTrue(hero.contains("textAlign = TextAlign.Start"))
        assertTrue(hero.contains("Modifier.requiredSize(104.dp)"))
        assertFalse(hero.contains("\"Droidance\""))
        assertFalse(hero.contains("RobotMascot("))
    }

    @Test
    fun onboardingShowsConsumerSetupChecklist() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()

        listOf(
            "onboarding_setup_title",
            "onboarding_setup_model",
            "onboarding_setup_air",
            "onboarding_permissions",
            "onboarding_grant_all"
        ).forEach { key ->
            assertTrue("Onboarding should use $key", screen.contains("R.string.$key"))
            assertTrue("Chinese resources should define $key", strings.contains("""name="$key""""))
        }
        assertFalse("Onboarding should not expose Pro/Max upgrade copy", screen.contains("onboarding_upgrade_"))
        assertFalse("Onboarding should not expose Accessibility as a required setup step", screen.contains("R.string.onboarding_setup_accessibility"))
        assertFalse("Chinese resources should not keep the removed setup key", strings.contains("""name="onboarding_setup_accessibility""""))
    }

    @Test
    fun onboardingCanNavigateToModelAndAirPairingSetup() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val navigation = projectFile("app/src/main/java/com/droidance/ui/navigation/AppNavigation.kt").readText()

        assertTrue(screen.contains("onNavigateToProviders"))
        assertTrue(screen.contains("onNavigateToAirPairing"))
        assertTrue(navigation.contains("onNavigateToProviders = { navController.navigate(SubRoutes.PROVIDER_LIST) }"))
        assertTrue(navigation.contains("onNavigateToAirPairing = { navController.navigate(SubRoutes.AIR_PAIRING) }"))
        // 配对不再把用户丢进设置→执行整页
        assertFalse(screen.contains("onNavigateToDeviceCapabilities"))
    }

    @Test
    fun airPairingWizardIsASharedDedicatedPage() {
        val wizard = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()
        val deviceScreen = projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()
        val navigation = projectFile("app/src/main/java/com/droidance/ui/navigation/AppNavigation.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val english = projectFile("app/src/main/res/values/strings.xml").readText()

        listOf(
            "air_pairing_title",
            "air_pairing_intro",
            "air_pairing_step1_title",
            "air_pairing_step1_desc",
            "air_pairing_step2_title",
            "air_pairing_step2_desc",
            "air_pairing_step3_title",
            "air_pairing_step3_desc",
            "air_pairing_start"
        ).forEach { key ->
            assertTrue("Pairing wizard should use $key", wizard.contains("R.string.$key"))
            assertTrue("Chinese resources should define $key", strings.contains("""name="$key""""))
            assertTrue("English resources should define $key", english.contains("""name="$key""""))
        }
        assertTrue(navigation.contains("AIR_PAIRING"))
        assertTrue(navigation.contains("AirPairingScreen(onBack ="))
        assertTrue(wizard.contains("AirPairingStepsCard()"))
        assertFalse(wizard.contains("activeStep"))
        assertFalse(wizard.contains("active ="))
        assertFalse(wizard.contains("air_pairing_current_step"))
        // 设置→执行里的连接只剩一行入口，配对状态机全在向导页
        assertTrue(deviceScreen.contains("actions.onOpenAirPairing"))
        assertFalse(deviceScreen.contains("AirBridgeItem("))
        assertFalse(deviceScreen.contains("onOpenWirelessDebuggingAndStartPairing"))
    }

    @Test
    fun aboutShowsProjectInformationWithoutRuntimeDiagnostics() {
        val deviceScreen = projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()
        val subScreens = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsAboutScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val about = subScreens.substringAfter("fun SettingsAboutScreen(")

        listOf(
            "R.string.settings_air_shell_sidecar",
            "R.string.settings_root_control",
            "R.string.settings_keep_alive"
        ).forEach { key ->
            assertFalse("About should not expose runtime diagnostic row $key", about.contains(key))
            assertFalse("Execution page should no longer render $key", deviceScreen.contains(key))
        }
        assertFalse(about.contains("R.string.settings_diagnostics"))
        assertFalse(strings.contains("name=\"settings_diagnostics\""))
        assertTrue(about.contains("MascotStage("))
        assertTrue(about.contains("R.string.settings_github_repository"))
        assertFalse(about.contains("R.string.settings_about_links"))
        assertFalse(about.contains("R.string.settings_report_issue"))
        assertFalse(about.contains("R.string.settings_version_releases"))
        assertTrue(about.contains("val repositoryUrl = stringResource(R.string.settings_repository_url)"))
        assertTrue(about.contains("text = repositoryUrl"))
        val english = projectFile("app/src/main/res/values/strings.xml").readText()
        listOf(strings, english).forEach { resources ->
            assertTrue(resources.contains("""<string name="settings_repository_url" translatable="false">https://github.com/YbbLab/Droidance</string>"""))
        }
    }

    @Test
    fun runtimePermissionsAreRequestedFromExplainedUiNotActivityStartup() {
        val mainActivity = projectFile("app/src/main/java/com/droidance/MainActivity.kt").readText()
        val onboarding = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val settings = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsSubScreens.kt").readText()

        assertFalse("MainActivity should not show system permission dialogs before onboarding copy is visible", mainActivity.contains("RequestMultiplePermissions"))
        assertFalse("MainActivity should not auto-request runtime permissions on app start", mainActivity.contains("requestRuntimePermissions()"))
        assertTrue("Onboarding should request permissions from its explained Grant all action", onboarding.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        assertTrue("Settings should support one-tap grouped requests", settings.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        assertTrue("Settings should bind grouped permission rows to the launcher", settings.contains("onRequestRuntimePermissions = runtimePermissionLauncher::launch"))
        assertTrue("Settings permission launcher should refresh status after the system sheet returns", settings.contains("viewModel.refreshServiceStatus()"))
    }

    @Test
    fun onboardingDoesNotRenderRuntimeOverviewCard() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val english = projectFile("app/src/main/res/values/strings.xml").readText()

        listOf(
            "onboarding_runtime_summary_title",
            "onboarding_runtime_summary_air",
            "onboarding_runtime_summary_progress",
            "onboarding_runtime_summary_ready",
            "onboarding_runtime_next_model",
            "onboarding_runtime_next_air",
            "onboarding_runtime_next_ready"
        ).forEach { key ->
            assertFalse("Onboarding should not use removed runtime overview key $key", screen.contains("R.string.$key"))
            assertFalse("Chinese resources should not keep removed key $key", strings.contains("""name="$key""""))
            assertFalse("English resources should not keep removed key $key", english.contains("""name="$key""""))
        }

        assertFalse(screen.contains("OnboardingRuntimeSummaryCard("))
        assertTrue(screen.contains("viewModel.completeOnboarding()"))
        assertFalse("Runtime summary should not route users to Accessibility setup", screen.contains("R.string.onboarding_runtime_next_accessibility"))
    }

    @Test
    fun onboardingRefreshesProviderReadinessAfterReturningFromSetupPages() {
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/OnboardingViewModel.kt").readText()
        val refreshBlock = viewModel.substringAfter("fun refreshPermissionStatus()").substringBefore("fun completeOnboarding()")

        assertTrue(refreshBlock.contains("getEnabledConfigs()"))
        assertTrue(refreshBlock.contains("providerReady ="))
        assertTrue(refreshBlock.contains("visionReady ="))
    }

    @Test
    fun deviceCapabilitiesPageDoesNotRenderTopRuntimeOverview() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val english = projectFile("app/src/main/res/values/strings.xml").readText()

        listOf(
            "settings_device_summary_title",
            "settings_next_action",
            "settings_next_action_air",
            "settings_next_action_configure"
        ).forEach { key ->
            assertFalse("Device capability page should not use removed overview key $key", screen.contains("R.string.$key"))
            assertFalse("Chinese resources should not keep removed key $key", strings.contains("""name="$key""""))
            assertFalse("English resources should not keep removed key $key", english.contains("""name="$key""""))
        }
        assertFalse(screen.contains("DeviceCapabilitySummaryCard("))
    }

    @Test
    fun deviceExecutionPageDoesNotDuplicateModelSettings() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()

        assertFalse(screen.contains("R.string.settings_ai_model_provider"))
        assertFalse(screen.contains("R.string.settings_vision_provider"))
        assertFalse(screen.contains("state.imageInputAllowed"))
    }

    @Test
    fun settingsHomeUsesUserFacingDestinationsAndExecutionOwnsPermissions() {
        val settingsHome = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsScreen.kt").readText()
        val navigation = projectFile("app/src/main/java/com/droidance/ui/navigation/AppNavigation.kt").readText()
        val subScreens = listOf("SettingsSubScreens.kt", "SettingsAppearanceScreens.kt", "SettingsDataManagementScreen.kt")
            .joinToString("\n") { projectFile("app/src/main/java/com/droidance/ui/screens/$it").readText() }
        val theme = projectFile("app/src/main/java/com/droidance/ui/theme/Theme.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val english = projectFile("app/src/main/res/values/strings.xml").readText()

        listOf(
            "settings_device_execution",
            "settings_data_controls",
            "settings_preferences",
            "settings_preferences_memory",
            "settings_theme",
            "settings_language",
            "settings_about"
        ).forEach { key ->
            assertTrue("Settings home should use $key", settingsHome.contains("R.string.$key"))
            assertTrue("Chinese resources should define $key", strings.contains("""name="$key""""))
            assertTrue("English resources should define $key", english.contains("""name="$key""""))
        }
        assertTrue(settingsHome.contains("onNavigateToPermissions"))
        assertTrue(settingsHome.contains("R.string.settings_permissions"))
        val settingsRoute = navigation.substringAfter("composable(SubRoutes.SETTINGS) {")
            .substringBefore("composable(SubRoutes.SETTINGS_DEVICE_CAPABILITIES)")
        assertTrue(settingsRoute.contains("onNavigateToPermissions = { navController.navigate(SubRoutes.SETTINGS_PERMISSIONS) }"))
        assertTrue(navigation.contains("onOpenExecutionPreferences"))
        assertTrue(settingsHome.contains("onNavigateToMemory"))
        assertTrue(navigation.contains("SETTINGS_PERMISSIONS"))
        assertTrue(navigation.contains("SETTINGS_THEME"))
        assertTrue(navigation.contains("SETTINGS_LANGUAGE"))
        assertTrue(navigation.contains("SETTINGS_DATA_MANAGEMENT"))
        assertTrue(subScreens.contains("fun SettingsThemeScreen("))
        assertTrue(subScreens.contains("fun SettingsLanguageScreen("))
        assertTrue(subScreens.contains("fun SettingsDataManagementScreen("))
        assertTrue(subScreens.contains("AppThemeMode.entries.forEachIndexed"))
        assertTrue(subScreens.contains("AppLanguage.entries.forEachIndexed"))
        assertTrue(theme.contains("AppThemeMode.SYSTEM -> isSystemInDarkTheme()"))
        assertFalse("Settings home should not render category headings", settingsHome.substringBefore("// ── 共用组件").contains("SettingsSectionHeader("))
        assertFalse(settingsHome.contains("onNavigateToAssistantPreferences"))
        assertFalse(settingsHome.contains("R.string.settings_appearance"))
        assertFalse(settingsHome.contains("R.string.settings_usage"))
        assertFalse(navigation.contains("SETTINGS_APPEARANCE"))
        assertFalse(navigation.contains("SETTINGS_USAGE"))
        assertFalse(navigation.contains("SETTINGS_DATA_CONTROLS"))
        assertFalse(settingsHome.contains("R.string.settings_developer"))
        assertFalse(settingsHome.contains("R.string.settings_memory"))
    }

    @Test
    fun settingsHomeGroupsAssistantAndAppEntries() {
        val settingsHome = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsScreen.kt").readText()
            .substringBefore("// ── 共用组件")

        assertFalse(settingsHome.contains("R.string.settings_group_device"))
        // 按设置首页两组入口的实际顺序排列。
        val order = listOf(
            "settings_ai_models",
            "settings_device_execution",
            "settings_preferences",
            "settings_theme",
            "settings_language",
            "settings_data_controls",
            "settings_about"
        ).map { key -> settingsHome.lastIndexOf("R.string.$key") }
        order.forEach { assertTrue("Settings home lost an entry", it >= 0) }
        assertEquals("Settings home entries are out of group order", order.sorted(), order)
    }

    @Test
    fun dataManagementStartsWithUsageAndEndsWithDataActions() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsDataManagementScreen.kt").readText()
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()
        val repository = projectFile("app/src/main/java/com/droidance/config/TokenUsageRepository.kt").readText()
        val dataManagement = screen.substringAfter("fun SettingsDataManagementScreen(")
            .substringBefore("private fun UsageRangeSelector(")

        listOf("SEVEN_DAYS", "THIRTY_DAYS", "ALL").forEach { range ->
            assertTrue("Usage range should define $range", viewModel.contains(range))
        }
        assertTrue(dataManagement.contains("UsageRangeSelector("))
        assertTrue(dataManagement.contains("R.string.settings_total_tokens"))
        assertTrue(dataManagement.contains("R.string.settings_data_management"))
        assertTrue(dataManagement.contains("R.string.settings_export_debug_package"))
        assertTrue(dataManagement.contains("R.string.settings_export_debug_title"))
        assertTrue(dataManagement.contains("R.string.settings_export_share"))
        assertTrue(dataManagement.contains("R.string.settings_clear_logs"))
        assertTrue(dataManagement.contains("R.string.settings_clear_conversations"))
        assertTrue(dataManagement.contains("SettingsActionItem("))
        assertTrue(dataManagement.indexOf("UsageRangeSelector(") < dataManagement.indexOf("R.string.settings_data_management"))
        assertTrue(dataManagement.indexOf("R.string.settings_export_debug_package") < dataManagement.lastIndexOf("R.string.settings_clear_conversations"))
        assertTrue(dataManagement.lastIndexOf("R.string.settings_clear_logs") < dataManagement.lastIndexOf("R.string.settings_clear_conversations"))
        assertFalse(dataManagement.contains("R.string.settings_total_cost"))
        assertFalse(dataManagement.contains("R.string.settings_usage_source_hint"))
        assertFalse(dataManagement.contains("DestructiveActionRow("))
        assertTrue(viewModel.contains("historyDeletion.delete()"))
        assertFalse(viewModel.contains("ConversationManager"))
        val about = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsAboutScreen.kt").readText()
        assertFalse(about.contains("R.string.settings_export_debug_package"))
        assertTrue(repository.contains("fun getAll(): List<DailyUsage>"))
    }

    @Test
    fun deviceCapabilitiesPageDoesNotRenderAirTwoStepGuide() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val english = projectFile("app/src/main/res/values/strings.xml").readText()

        listOf(
            "settings_air_setup_guide_title",
            "settings_air_setup_guide_desc",
            "settings_air_setup_step_model",
            "settings_air_setup_step_wireless"
        ).forEach { key ->
            assertFalse("Device capability guide should not use removed key $key", screen.contains("R.string.$key"))
            assertFalse("Chinese resources should not keep removed key $key", strings.contains("""name="$key""""))
            assertFalse("English resources should not keep removed key $key", english.contains("""name="$key""""))
        }

        assertFalse(screen.contains("AirSetupGuideCard("))
        assertFalse(screen.contains("AirSetupStepRow("))
        assertFalse("Device capability guide should not ask users to enable Accessibility", screen.contains("R.string.settings_air_setup_step_accessibility"))
    }

    @Test
    fun settingsAndOnboardingDoNotExposeAccessibilityAsRequiredSetup() {
        val onboarding = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val settings = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsSubScreens.kt").readText() +
            projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()
        val settingsHome = projectFile("app/src/main/java/com/droidance/ui/screens/SettingsScreen.kt").readText()
        val viewModel = projectFile("app/src/main/java/com/droidance/ui/viewmodel/SettingsViewModel.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val english = projectFile("app/src/main/res/values/strings.xml").readText()

        listOf(onboarding, settings, settingsHome).forEach { source ->
            assertFalse(source.contains("settings_accessibility_service"))
            assertFalse(source.contains("onboarding_runtime_next_accessibility"))
            assertFalse(source.contains("onboarding_setup_accessibility"))
        }
        assertFalse(settings.contains("openAccessibilitySettings"))
        assertFalse(viewModel.contains("fun openAccessibilitySettings()"))
        listOf(
            "settings_air_setup_step_accessibility",
            "settings_accessibility_unavailable",
            "settings_air_ready_requires",
            "onboarding_runtime_next_accessibility",
            "onboarding_setup_accessibility",
            "onboarding_setup_accessibility_desc"
        ).forEach { key ->
            assertFalse("Chinese resources should not expose removed key $key", strings.contains("""name="$key""""))
            assertFalse("English resources should not expose removed key $key", english.contains("""name="$key""""))
        }
        assertFalse("Settings subtitle should not mention Accessibility", strings.contains("Root · 无障碍"))
        assertFalse("Settings subtitle should not mention Accessibility", english.contains("Root · Accessibility"))
    }

    @Test
    fun productEntryPointsDoNotOfferRuntimeOrSpaceSelection() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt").readText()
        val onboarding = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val composer = projectFile("app/src/main/java/com/droidance/ui/screens/home/ComposerSelectionControls.kt").readText()
        assertFalse(screen.contains("ExecutionSpaceCard("))
        assertFalse(screen.contains("actions.onOpenVirtualSpace"))
        for (source in listOf(screen, onboarding, composer)) {
            assertFalse(source.contains("onUnlockUltra"))
            assertFalse(source.contains("RuntimeMode.ULTRA"))
            assertFalse(source.contains("settings_execution_space_main"))
            assertFalse(source.contains("onSpaceSelected"))
        }
    }

    @Test
    fun airPairingCodeEntrySupportsDialogAndNotification() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/AirPairingScreen.kt").readText()
        val notification = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModeNotificationController.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText() +
            projectFile("core/device/src/main/res/values-zh/strings.xml").readText()
        val english = projectFile("app/src/main/res/values/strings.xml").readText() +
            projectFile("core/device/src/main/res/values/strings.xml").readText()
        val awaitingCodeBlock = screen.substringAfter("private fun AirPairingNotificationRow")

        listOf(
            "settings_air_notification_code_title",
            "settings_air_code_hint",
            "settings_air_notification_keep_dialog",
            "settings_air_notification_channel",
            "settings_air_notification_input_label",
            "settings_air_notification_action",
            "settings_air_notification_title",
            "settings_air_notification_text"
        ).forEach { key ->
            assertTrue("Air notification pairing UI should use $key", screen.contains("R.string.$key") || notification.contains("R.string.$key"))
            assertTrue("Chinese resources should define $key", strings.contains("""name="$key""""))
            assertTrue("English resources should define $key", english.contains("""name="$key""""))
        }

        assertTrue("Air pairing should support in-app dialog", screen.contains("AirPairingCodeDialog"))
        assertTrue("Air pairing should submit code via viewModel", screen.contains("viewModel.submitAirPairingCode("))
        assertFalse("Awaiting-code row should use dialog or notification, not an inline text field", awaitingCodeBlock.contains("OutlinedTextField("))
    }

    @Test
    fun onboardingBottomBarRoutesToTheMissingSetupAndRemovesCheckIcon() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val strings = projectFile("app/src/main/res/values-zh/strings.xml").readText()
        val english = projectFile("app/src/main/res/values/strings.xml").readText()

        val bottomBar = screen.substringAfter("bottomBar = {").substringBefore("contentPadding ->")
        assertFalse("Onboarding start button should not have leading check icon", bottomBar.contains("DroidanceIcons.Check"))
        assertTrue("Onboarding should check core channels readiness", bottomBar.contains("isCoreReady"))
        assertTrue("Onboarding should route to model setup when the provider is missing", bottomBar.contains("onNavigateToProviders()"))
        assertTrue("Onboarding should route to AIR setup when execution is unavailable", bottomBar.contains("onNavigateToAirPairing()"))
        assertFalse("Onboarding should not use the removed core-channel toast", bottomBar.contains("onboarding_core_channels_not_ready"))

        listOf(
            "onboarding_setup_battery_desc",
            "onboarding_notification_access_desc",
            "onboarding_other_permissions_desc"
        ).forEach { key ->
            assertTrue("Chinese resources should define $key", strings.contains("""name="$key""""))
            assertTrue("English resources should define $key", english.contains("""name="$key""""))
        }

        assertTrue(strings.contains("减少系统休眠对后台任务的中断"))
        assertTrue(strings.contains("感知通知消息，自动触发自动化任务"))
        assertTrue(strings.contains("联系人、日历、媒体等，可按需授权"))
    }

    @Test
    fun onboardingBindsBatteryExemptionToGrantedState() {
        val screen = projectFile("app/src/main/java/com/droidance/ui/screens/OnboardingScreen.kt").readText()
        val batteryBlock = screen.substringAfter("onboarding_background_recommended")
            .substringBefore("SectionLabel(stringResource(R.string.onboarding_permissions))")

        assertTrue("Battery recommendation should use hasBatteryExemption for granted state", batteryBlock.contains("granted = hasBatteryExemption"))
        assertFalse("Battery recommendation should not hardcode granted = false", batteryBlock.contains("granted = false"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
