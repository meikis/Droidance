// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.droidance.core.device.permission.PermissionBroker

/** Owns the normal Android runtime-permission dialog while the Agent coroutine is paused. */
@Composable
fun RuntimePermissionRequestHost(permissionBroker: PermissionBroker) {
    val pending by permissionBroker.pendingRequest.collectAsStateWithLifecycle()
    val systemPromptRequested by permissionBroker.systemDialogPromptRequested.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostContext = androidx.compose.ui.platform.LocalContext.current
    val activity = generateSequence(hostContext) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var launchedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val requestId = launchedRequestId
        val request = permissionBroker.pendingRequest.value?.takeIf { it.requestId == requestId }
        if (requestId != null) {
            val settingsRequired = grants.isEmpty() || (request != null && activity != null &&
                permissionBroker.missingPermissions(request.permissions).any { permission ->
                    !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                })
            permissionBroker.resolve(requestId, settingsRequired)
        }
        launchedRequestId = null
    }

    LaunchedEffect(isResumed, pending?.requestId) {
        if (isResumed) pending?.let { permissionBroker.recheckGrantedPermissions(it.requestId) }
    }

    LaunchedEffect(systemPromptRequested, pending?.requestId, isResumed, launchedRequestId) {
        if (!isResumed) return@LaunchedEffect
        val requestId = systemPromptRequested ?: return@LaunchedEffect
        if (launchedRequestId != null) return@LaunchedEffect
        val request = pending?.takeIf { it.requestId == requestId && !it.settingsRequired } ?: return@LaunchedEffect
        launchedRequestId = request.requestId
        permissionBroker.markLaunched(request.requestId)
        permissionBroker.consumeSystemDialogRequest(request.requestId)
        runCatching { launcher.launch(request.permissions.toTypedArray()) }
            .onFailure {
                launchedRequestId = null
                permissionBroker.launchFailed(request.requestId)
            }
    }
}
