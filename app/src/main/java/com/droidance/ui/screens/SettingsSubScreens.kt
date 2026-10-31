// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidance.R
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.viewmodel.SettingsViewModel

// ── 设备与执行 ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDeviceCapabilitiesScreen(
    onBack: () -> Unit,
    onOpenAirPairing: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RefreshStatusOnResume(viewModel)

    DeviceCapabilitiesScreen(
        state = uiState,
        actions = DeviceCapabilityActions(
            onBack = onBack,
            onOpenAirPairing = onOpenAirPairing,
            onTaskWallMinutesChange = viewModel::setTaskWallMinutes,
            onToggleDeepLink = viewModel::toggleDeepLink
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPermissionsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RefreshStatusOnResume(viewModel)
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshServiceStatus()
    }

    PermissionsScreen(
        state = uiState,
        onBack = onBack,
        actions = PermissionActions(
            onOpenNotificationListener = viewModel::openNotificationListenerSettings,
            onRequestRuntimePermissions = runtimePermissionLauncher::launch,
            onRequestExactAlarmPermission = viewModel::requestExactAlarmPermission,
            onRequestBatteryOptimizationExemption = viewModel::requestBatteryOptimizationExemption
        )
    )
}

/** 从系统设置页返回时自动刷新权限/服务状态，不需要用户手动刷新 */
@Composable
private fun RefreshStatusOnResume(viewModel: SettingsViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) viewModel.refreshServiceStatus()
    }
}

// ── 私有共用组件 ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            DroidanceIcons.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        containerColor = MaterialTheme.colorScheme.background,
        content = content
    )
}
