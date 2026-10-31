// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesScreenSourceContractTest {
    @Test
    fun deviceCapabilitiesUseGroupedSectionsAndThemeSemanticColors() {
        val source = projectFile(
            "app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt"
        ).readText()

        listOf(
            "ReadinessSection",
            "PermissionSection",
            "ExecutionSettingsSection"
        ).forEach { section ->
            assertTrue("Device capabilities should define $section", source.contains("fun $section"))
        }
        assertTrue(source.contains("fun DeviceCapabilitiesScreen("))
        val executionPage = source.substringAfter("internal fun DeviceCapabilitiesScreen(")
            .substringBefore("internal fun PermissionsScreen(")
        assertTrue(executionPage.indexOf("ExecutionSettingsSection(state, actions)") >
            executionPage.indexOf("ReadinessSection(state, actions)"))
        assertFalse("Permissions now belong on Settings home", executionPage.contains("R.string.settings_permissions"))
        assertFalse("Execution options must always be visible", executionPage.contains("advancedExpanded"))
        assertFalse(source.contains("R.string.settings_section_advanced"))
        assertTrue(source.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertFalse(source.contains("DroidancePrimary"))
        assertFalse(source.contains("Color.Blue"))
    }

    @Test
    fun deviceCapabilityActionsAreConsumedAndBoundToExistingHandlers() {
        val screen = projectFile(
            "app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt"
        ).readText()
        val bindings = projectFile(
            "app/src/main/java/com/droidance/ui/screens/SettingsSubScreens.kt"
        ).readText()

        listOf(
            "onBack",
            "onOpenAirPairing",
            "onTaskWallMinutesChange",
            "onToggleDeepLink"
        ).forEach { action ->
            assertTrue("Device capability page should consume $action", screen.contains("actions.$action"))
        }
        listOf(
            "onOpenNotificationListener",
            "onRequestRuntimePermissions",
            "onRequestExactAlarmPermission",
            "onRequestBatteryOptimizationExemption"
        ).forEach { action ->
            assertTrue("Permissions page should consume $action", screen.contains("actions.$action"))
        }
        assertFalse("Model configuration belongs on Settings home", screen.contains("onNavigateToProviders"))

        listOf(
            "onBack = onBack",
            "onOpenAirPairing = onOpenAirPairing",
            "onOpenNotificationListener = viewModel::openNotificationListenerSettings",
            "onRequestRuntimePermissions = runtimePermissionLauncher::launch",
            "onRequestExactAlarmPermission = viewModel::requestExactAlarmPermission",
            "onRequestBatteryOptimizationExemption = viewModel::requestBatteryOptimizationExemption",
            "onTaskWallMinutesChange = viewModel::setTaskWallMinutes",
            "onToggleDeepLink = viewModel::toggleDeepLink"
        ).forEach { binding ->
            assertTrue("Device capability wrapper should keep binding $binding", bindings.contains(binding))
        }
        assertFalse("About should not keep a hidden keep-alive action", bindings.contains("onAction = viewModel::toggleKeepAliveService"))
        assertFalse("About should not keep a hidden Air sidecar refresh action", bindings.contains("onAction = viewModel::refreshAirShellSidecar"))
        assertTrue(bindings.contains("ActivityResultContracts.RequestMultiplePermissions()"))
    }

    @Test
    fun permissionsPageGroupsUserGrantableCapabilitiesAndHidesSystemDetails() {
        val screen = projectFile(
            "app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt"
        ).readText()

        listOf(
            "settings_permission_request_all",
            "settings_permission_group_notifications",
            "settings_permission_group_contacts",
            "settings_permission_group_messages_calls",
            "settings_permission_group_calendar",
            "settings_permission_group_media",
            "settings_permission_exact_alarm",
            "settings_permission_battery_optimization"
        ).forEach { key ->
            assertTrue("Permissions page should show $key", screen.contains("R.string.$key"))
        }
        listOf(
            "settings_permission_secure_settings",
            "settings_permission_internet",
            "settings_permission_network_state",
            "settings_permission_foreground_service",
            "settings_permission_foreground_service_special_use",
            "settings_permission_boot_completed",
            "settings_permission_wifi_state",
            "settings_permission_change_wifi",
            "settings_permission_wifi_multicast",
            "settings_permission_query_packages",
            "settings_permission_set_alarm",
            "settings_permission_vibrate",
            "settings_permission_wake_lock"
        ).forEach { key ->
            assertFalse("Permissions page should hide system detail $key", screen.contains("R.string.$key"))
        }
        assertTrue(screen.contains("actions.onRequestRuntimePermissions(allRuntimePermissions)"))
    }

    @Test
    fun runtimeModeRows_doNotExpose_connectionDetailsOr_requirementDiagnostics() {
        val screen = projectFile(
            "app/src/main/java/com/droidance/ui/screens/DeviceCapabilitiesScreen.kt"
        ).readText()
        val runtimeModeRow = screen.substringAfter("private fun RuntimeModeSection(")
            .substringBefore("\n@Composable\nprivate fun ExecutionSpaceSection(")

        assertFalse(runtimeModeRow.contains("R.string.settings_connection_details"))
        assertFalse(runtimeModeRow.contains("missingRequirements"))
        assertFalse(runtimeModeRow.contains("showDetails"))
        assertFalse(runtimeModeRow.contains("messageRes()"))
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
