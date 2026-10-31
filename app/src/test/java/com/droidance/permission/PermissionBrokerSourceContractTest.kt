// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.permission

import com.droidance.core.device.permission.*

import com.droidance.permission.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionBrokerSourceContractTest {
    @Test
    fun permissionRequestIsPersistedAndHostedByNormalAndroidDialog() {
        val broker = source("core/device/src/main/java/com/droidance/core/device/permission/PermissionBroker.kt")
        val host = source("app/src/main/java/com/droidance/ui/components/RuntimePermissionRequestHost.kt")
        val activity = source("app/src/main/java/com/droidance/MainActivity.kt")

        assertTrue(broker.contains("getSharedPreferences(PREFS_NAME"))
        assertTrue(broker.contains("persist(request)"))
        assertTrue(broker.contains("if (timeoutMillis > 0L)"))
        assertTrue(broker.contains("withTimeoutOrNull(timeoutMillis)"))
        assertTrue(broker.contains("else {\n                waiter.await()"))
        assertTrue(broker.contains("fun platformStatus(permission: String)"))
        assertTrue(broker.contains("takeIf { it.permissions == missing && it.turnId == turnId && it.callId == callId }"))
        assertTrue(broker.contains("fun resolve(requestId: String, settingsRequired: Boolean)"))
        val hostLaunch = broker.substringAfter("private suspend fun showPermissionHost")
            .substringBefore("private fun persist")
        assertTrue(hostLaunch.indexOf("launchPermissionHost(request.requestId)") < hostLaunch.indexOf("_pendingRequest.value = request"))
        assertTrue(host.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        assertTrue(host.contains("Lifecycle.State.RESUMED"))
        assertTrue(host.contains("rememberSaveable"))
        assertTrue(host.contains("shouldShowRequestPermissionRationale"))
        assertTrue(host.contains("permissionBroker.resolve(requestId, settingsRequired)"))
        assertTrue(host.contains("if (!isResumed) return@LaunchedEffect"))
        assertTrue(activity.contains("RuntimePermissionRequestHost(permissionBroker)"))
        assertFalse(activity.contains("ActivityResultContracts.RequestMultiplePermissions"))
    }

    @Test
    fun permissionDialogOnlyLaunchesAfterExplicitUserConsent() {
        val broker = source("core/device/src/main/java/com/droidance/core/device/permission/PermissionBroker.kt")
        val host = source("app/src/main/java/com/droidance/ui/components/RuntimePermissionRequestHost.kt")
        val content = source("app/src/main/java/com/droidance/ui/screens/home/ChatMessageContent.kt")
        val viewModel = source("app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt")

        assertTrue(broker.contains("val systemDialogPromptRequested: StateFlow<String?>"))
        assertTrue(broker.contains("fun requestSystemPermissionDialog(requestId: String)"))
        assertTrue(broker.contains("fun consumeSystemDialogRequest(requestId: String)"))
        assertTrue(host.contains("val systemPromptRequested by permissionBroker.systemDialogPromptRequested"))
        assertTrue(host.contains("val requestId = systemPromptRequested ?: return@LaunchedEffect"))
        assertTrue(content.contains("private fun PermissionActionBlock("))
        assertTrue(content.contains("pendingPermission: PendingPermissionUiState"))
        assertTrue(content.contains("onRequestPermission: () -> Unit"))
        assertTrue(viewModel.contains("val pendingPermissionRequest: StateFlow<PendingPermissionUiState?>"))
        assertTrue(viewModel.contains("fun requestRuntimePermission()"))
    }

    private fun source(path: String): String = projectFile(path).readText()

    private fun projectFile(path: String): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            File(current, path).takeIf(File::exists)?.let { return it }
            current = current.parentFile ?: error("Cannot locate $path")
        }
    }
}
