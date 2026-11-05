// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFieldColors
import com.droidance.ui.components.droidanceSwitchColors
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppShapes
import com.droidance.ui.theme.LocalAppIsDark
import com.droidance.ui.theme.Neutral300
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.droidance.R
import com.droidance.core.device.air.AirPairingNotificationAvailability
import com.droidance.core.device.air.AirModePairingManager.PairingState
import com.droidance.core.device.air.messageRes
import com.droidance.core.device.air.AirManualPairingInput
import com.droidance.core.device.air.AirPairingFailure
import com.droidance.ui.components.DroidanceDialog
import com.droidance.ui.viewmodel.SettingsViewModel

/**
 * 无线调试配对向导：初始化和设置→执行共用同一页。
 * 一页只做一件事——把「开发者选项 → 无线调试 → 通知栏填码」三步讲清楚，并把状态和唯一主操作摆在固定位置。
 */
@Composable
fun AirPairingScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pairingState = state.airBridgeState
    val connected = state.airDeviceConnected
    val inProgress = pairingState in setOf(
        PairingState.DISCOVERING,
        PairingState.AWAITING_CODE,
        PairingState.PAIRING,
        PairingState.CONNECTING
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshServiceStatus()
                viewModel.resumePendingAirPairingAfterNotificationAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onAirPairingNotificationPermissionResult(granted)
    }
    val notificationRequired = !state.airPreviouslyPaired || state.airPairingRequired
    val notificationBlocked = notificationRequired &&
        state.airPairingNotificationAvailability != AirPairingNotificationAvailability.AVAILABLE
    val resolveNotificationBlock: () -> Unit = {
        val availability = viewModel.prepareAirPairingAfterNotificationAccess(
            repair = state.airPairingRequired
        )
        if (
            availability ==
            AirPairingNotificationAvailability.PERMISSION_REQUIRED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (availability != AirPairingNotificationAvailability.AVAILABLE) {
            viewModel.openAirPairingNotificationSettings()
        }
    }

    var showPairingDialog by remember { mutableStateOf(false) }
    var showManualPairingDialog by remember { mutableStateOf(false) }
    var showConnectionPortDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pairingState, connected) {
        if (pairingState == PairingState.AWAITING_CODE && !showManualPairingDialog) {
            showPairingDialog = true
        } else if (pairingState != PairingState.DISCOVERING) {
            showPairingDialog = false
        }
        if (pairingState !in setOf(PairingState.IDLE, PairingState.DISCOVERING, PairingState.AWAITING_CODE)) {
            showManualPairingDialog = false
        }
        if (connected) showConnectionPortDialog = false
    }
    if (showManualPairingDialog) {
        AirManualPairingDialog(onDismiss = { showManualPairingDialog = false },
            onSubmit = { viewModel.submitAirPairingCode(it.code, it.port) })
    }
    if (showConnectionPortDialog) {
        AirConnectionPortDialog(onDismiss = { showConnectionPortDialog = false },
            onSubmit = viewModel::connectAirPairingPort)
    }

    if (showPairingDialog && pairingState == PairingState.AWAITING_CODE) {
        AirPairingCodeDialog(
            onDismiss = { showPairingDialog = false },
            onSubmit = { code ->
                viewModel.submitAirPairingCode(code)
            }
        )
    }

    SubScreenScaffold(
        title = stringResource(R.string.air_pairing_title),
        onBack = onBack,
        bottomBar = {
            if (connected) {
                AirPairingConnectedStatus()
            } else {
                AirPairingPrimaryAction(
                    label = when {
                        notificationBlocked -> stringResource(R.string.settings_air_enable_notifications)
                        inProgress -> stringResource(R.string.common_cancel)
                        state.airPairingRequired -> stringResource(R.string.settings_air_repair)
                        state.airPreviouslyPaired -> stringResource(R.string.settings_air_reconnect)
                        else -> stringResource(R.string.air_pairing_start)
                    },
                    filled = !inProgress,
                    onClick = when {
                        notificationBlocked -> resolveNotificationBlock
                        inProgress -> viewModel::stopAirPairing
                        state.airPairingRequired -> viewModel::startRequiredAirPairing
                        state.airPreviouslyPaired -> viewModel::reconnectAirPairing
                        else -> viewModel::openWirelessDebuggingAndStartAirPairing
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.air_pairing_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp).padding(top = 4.dp, bottom = 14.dp)
                )
            }
            item { AirPairingStatusCard(state = state, viewModel = viewModel,
                onOpenPairingDialog = { showPairingDialog = true },
                onOpenManualPairing = { showPairingDialog = false; showManualPairingDialog = true },
                onOpenConnectionPort = { showConnectionPortDialog = true }) }
            item {
                SettingsCardGroup(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.air_adb_persistence), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.air_adb_persistence_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = state.airPersistenceEnabled,
                            onCheckedChange = viewModel::setAdbPersistence,
                            enabled = !state.airPersistenceChanging,
                            colors = droidanceSwitchColors().copy(
                                uncheckedThumbColor = if (LocalAppIsDark.current) Neutral300 else MaterialTheme.colorScheme.surface
                            ))
                    }
                    Text(
                        stringResource(R.string.air_adb_persistence_exposure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
                    )
                    if (state.airPersistenceEnabled && state.airUsbDebuggingEnabled != true) {
                        SettingsInsetDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.air_adb_persistence_usb_title),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.air_adb_persistence_usb_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            val openDeveloperOptions = stringResource(R.string.air_adb_persistence_open_developer_options)
                            Surface(
                                onClick = viewModel::openDeveloperOptions,
                                modifier = Modifier.semantics { contentDescription = openDeveloperOptions },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, settingsHairline())
                            ) {
                                Text(
                                    stringResource(R.string.air_adb_persistence_go),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (!connected) item { SettingsSectionHeader(stringResource(R.string.air_pairing_steps)) }
            if (!connected) item {
                AirPairingStepsCard()
                Text(stringResource(R.string.air_pairing_before_leave),
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 状态卡：与权限行同一形态（图标块 + 标题 + 状态副标题），进行中的提示挂在同一张卡里 */
@Composable
private fun AirPairingStatusCard(
    state: com.droidance.ui.viewmodel.SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenPairingDialog: () -> Unit,
    onOpenManualPairing: () -> Unit,
    onOpenConnectionPort: () -> Unit
) {
    val pairingState = state.airBridgeState
    val connected = state.airDeviceConnected
    val stateText = when (pairingState) {
        PairingState.IDLE ->
            when {
                connected -> stringResource(R.string.settings_air_state_connected)
                state.airPairingRequired -> stringResource(R.string.settings_air_state_pairing_required)
                state.airPreviouslyPaired -> stringResource(R.string.settings_air_state_paired)
                else -> stringResource(R.string.settings_air_state_unpaired)
            }
        PairingState.DISCOVERING -> stringResource(R.string.settings_air_state_discovering)
        PairingState.AWAITING_CODE -> stringResource(R.string.settings_air_state_awaiting_code)
        PairingState.PAIRING -> stringResource(R.string.settings_air_state_pairing)
        PairingState.CONNECTING -> stringResource(R.string.settings_air_state_reconnecting)
        PairingState.CONNECTED -> stringResource(R.string.settings_air_state_connected)
    }

    Column {
        SettingsCardGroup {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsIconTile(DroidanceIcons.Wifi)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_air_bridge_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                // 进行中留一条回到系统无线调试页的通路——用户切回 App 后不必取消重来
                when {
                    (pairingState == PairingState.IDLE || pairingState == PairingState.CONNECTED) &&
                        state.airPreviouslyPaired -> {
                        Spacer(Modifier.width(12.dp))
                        SettingsPillButton(
                            text = stringResource(R.string.settings_air_reset_pairing),
                            onClick = { if (!state.airPersistenceChanging) viewModel.resetAirPairing() }
                        )
                    }
                    pairingState != PairingState.IDLE && !connected -> {
                        Spacer(Modifier.width(12.dp))
                        SettingsPillButton(
                            text = stringResource(R.string.settings_air_open_wireless_debugging),
                            onClick = viewModel::openWirelessDebuggingSettings
                        )
                    }
                }
            }

            when (pairingState) {
                PairingState.DISCOVERING -> {
                    SettingsInsetDivider()
                    AirPairingProgressRow(stringResource(R.string.settings_air_discovering_hint))
                }
                PairingState.PAIRING -> {
                    SettingsInsetDivider()
                    AirPairingProgressRow(stringResource(R.string.settings_air_pairing_hint))
                }
                PairingState.CONNECTING -> {
                    SettingsInsetDivider()
                    AirPairingProgressRow(stringResource(R.string.settings_air_reconnecting_hint))
                }
                // 配对码可在应用内弹窗输入，也可从通知栏输入
                PairingState.AWAITING_CODE -> {
                    SettingsInsetDivider()
                    AirPairingNotificationRow(onOpenDialog = onOpenPairingDialog)
                }
                PairingState.CONNECTED, PairingState.IDLE -> Unit
            }

            if (connected) {
                SettingsInsetDivider()
                Text(
                    stringResource(R.string.air_pairing_connected_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }

        val failure = state.airBridgeFailure
        if (failure != null && (!connected || failure == com.droidance.core.device.air.AirPairingFailure.LOCAL_PORT_CLOSE_FAILED)) {
            Text(
                stringResource(failure.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 32.dp).padding(top = 8.dp)
            )
        }
        val pairingInputAllowed = pairingState in setOf(PairingState.IDLE, PairingState.DISCOVERING, PairingState.AWAITING_CODE)
        val notificationInputBlocked = state.airPairingNotificationAvailability != AirPairingNotificationAvailability.AVAILABLE
        if (!connected && pairingInputAllowed && (state.airManualPairingAvailable ||
                (notificationInputBlocked && (!state.airPreviouslyPaired || state.airPairingRequired)))) {
            TextButton(onClick = onOpenManualPairing, modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(com.droidance.core.device.R.string.settings_air_manual_pairing_action))
            }
        }
        if (!connected && state.airPreviouslyPaired && !state.airPairingRequired &&
            failure in setOf(AirPairingFailure.ADB_ENDPOINT_NOT_FOUND, AirPairingFailure.ADB_CONNECTION_FAILED)) {
            TextButton(onClick = onOpenConnectionPort, modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.air_connection_manual_action))
            }
        }
    }
}

@Composable
private fun AirPairingProgressRow(hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun AirPairingCodeDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val isValid = code.length == 6 && code.all { it in '0'..'9' }
    DroidanceDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.air_pairing_dialog_title),
        text = stringResource(R.string.air_pairing_dialog_desc),
        confirmLabel = stringResource(R.string.common_confirm),
        confirmEnabled = isValid,
        onConfirm = {
            if (isValid) {
                onSubmit(code.trim())
                onDismiss()
            }
        },
        dismissLabel = stringResource(R.string.common_cancel),
        onDismiss = onDismiss
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { input ->
                val digits = input.filter { it in '0'..'9' }.take(6)
                code = digits
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(stringResource(R.string.air_pairing_code_placeholder)) },
            colors = droidanceFieldColors(),
            shape = AppShapes.Control
        )
    }
}

@Composable
internal fun AirManualPairingDialog(onDismiss: () -> Unit, onSubmit: (AirManualPairingInput) -> Unit) {
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val input = AirManualPairingInput.parse("$port $code")
    DroidanceDialog(onDismissRequest = onDismiss,
        title = stringResource(com.droidance.core.device.R.string.settings_air_manual_pairing_action),
        text = stringResource(R.string.air_pairing_manual_description),
        confirmLabel = stringResource(R.string.common_confirm), confirmEnabled = input != null,
        onConfirm = { input?.let { onSubmit(it); onDismiss() } },
        dismissLabel = stringResource(R.string.common_cancel), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AirPortField(port, { port = it }, R.string.air_pairing_port)
            OutlinedTextField(value = code,
                onValueChange = { code = it.filter { digit -> digit in '0'..'9' }.take(6) },
                label = { Text(stringResource(R.string.air_pairing_code_placeholder)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = droidanceFieldColors(), shape = AppShapes.Control)
        }
    }
}

@Composable
internal fun AirConnectionPortDialog(onDismiss: () -> Unit, onSubmit: (Int) -> Unit) {
    var port by remember { mutableStateOf("") }
    val validPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    DroidanceDialog(onDismissRequest = onDismiss,
        title = stringResource(R.string.air_connection_manual_action),
        text = stringResource(R.string.air_connection_manual_description),
        confirmLabel = stringResource(R.string.common_confirm), confirmEnabled = validPort != null,
        onConfirm = { validPort?.let { onSubmit(it); onDismiss() } },
        dismissLabel = stringResource(R.string.common_cancel), onDismiss = onDismiss) {
        AirPortField(port, { port = it }, R.string.air_connection_port)
    }
}

@Composable
private fun AirPortField(value: String, onValueChange: (String) -> Unit, @androidx.annotation.StringRes label: Int) {
    OutlinedTextField(value = value,
        onValueChange = { onValueChange(it.filter { digit -> digit in '0'..'9' }.take(5)) },
        label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = droidanceFieldColors(), shape = AppShapes.Control)
}

@Composable
private fun AirPairingNotificationRow(onOpenDialog: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            DroidanceIcons.Notifications,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.settings_air_notification_code_title),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(R.string.settings_air_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
            )
            Text(
                stringResource(R.string.settings_air_notification_keep_dialog),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        SettingsPillButton(
            text = stringResource(R.string.air_pairing_enter_code_action),
            onClick = onOpenDialog
        )
    }
}

@Composable
private fun AirPairingStepsCard() {
    SettingsCardGroup {
        AirPairingStepRow(
            index = 1,
            title = stringResource(R.string.air_pairing_step1_title),
            desc = stringResource(R.string.air_pairing_step1_desc)
        )
        SettingsInsetDivider(startIndent = 58.dp)
        AirPairingStepRow(
            index = 2,
            title = stringResource(R.string.air_pairing_step2_title),
            desc = stringResource(R.string.air_pairing_step2_desc)
        )
        SettingsInsetDivider(startIndent = 58.dp)
        AirPairingStepRow(
            index = 3,
            title = stringResource(R.string.air_pairing_step3_title),
            desc = stringResource(R.string.air_pairing_step3_desc)
        )
    }
}

@Composable
private fun AirPairingStepRow(index: Int, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                index.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 已连接时仅展示结果，不提供会破坏当前连接的操作。 */
@Composable
private fun AirPairingConnectedStatus() {
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(48.dp),
            shape = AppShapes.Card,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, settingsHairline())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = DroidanceIcons.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_air_state_connected),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** 底部唯一主操作：推进动作用石墨黑实心，取消操作用中性描边。 */
@Composable
private fun AirPairingPrimaryAction(
    label: String,
    filled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(48.dp),
            shape = AppShapes.Card,
            color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            border = if (filled) null else BorderStroke(1.dp, settingsHairline())
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
