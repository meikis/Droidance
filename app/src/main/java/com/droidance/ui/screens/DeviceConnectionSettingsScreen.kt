// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidance.R
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.ui.theme.AppShapes
import com.droidance.ui.viewmodel.DeviceConnectionSettingsViewModel

@Composable
fun DeviceConnectionSettingsScreen(
    onBack: () -> Unit,
    onOpenWireless: () -> Unit,
    viewModel: DeviceConnectionSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onReturnFromWireless()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val activeName = stringResource(if (state.active == DeviceConnectionMethod.ROOT) R.string.connection_method_root else R.string.connection_method_wireless)
    val selectedConnected = state.selected == state.active && state.connected
    SubScreenScaffold(
        title = stringResource(R.string.air_pairing_title),
        onBack = onBack,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.navigationBarsPadding()) {
                Button(
                    onClick = {
                        if (state.selected == DeviceConnectionMethod.ROOT) viewModel.connectRoot()
                        else viewModel.connectWireless(onOpenWireless)
                    },
                    enabled = !state.busy && !(selectedConnected && state.selected == DeviceConnectionMethod.ROOT),
                    shape = AppShapes.Card,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(when {
                        state.busy -> R.string.connection_connecting
                        selectedConnected && state.selected == DeviceConnectionMethod.ROOT -> R.string.settings_air_state_connected
                        selectedConnected -> R.string.connection_manage_wireless
                        state.selected == DeviceConnectionMethod.ROOT -> R.string.connection_connect_root
                        state.wirelessConnected -> R.string.connection_switch_wireless
                        else -> R.string.connection_continue_wireless
                    }), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingsSectionHeader(stringResource(R.string.connection_current))
            SettingsCardGroup {
                Text(
                    stringResource(if (state.connected) R.string.connection_method_connected else R.string.connection_method_disconnected, activeName),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
            SettingsSectionHeader(stringResource(R.string.connection_method_title))
            SettingsCardGroup {
                ConnectionMethodRow(
                    title = stringResource(R.string.connection_method_wireless),
                    description = stringResource(R.string.connection_method_wireless_desc),
                    selected = state.selected == DeviceConnectionMethod.WIRELESS,
                    active = state.active == DeviceConnectionMethod.WIRELESS && state.connected,
                    enabled = !state.busy,
                    onClick = { viewModel.select(DeviceConnectionMethod.WIRELESS) }
                )
                SettingsInsetDivider(startIndent = 52.dp)
                ConnectionMethodRow(
                    title = stringResource(R.string.connection_method_root),
                    description = stringResource(R.string.connection_method_root_desc),
                    selected = state.selected == DeviceConnectionMethod.ROOT,
                    active = state.active == DeviceConnectionMethod.ROOT && state.connected,
                    enabled = !state.busy,
                    onClick = { viewModel.select(DeviceConnectionMethod.ROOT) }
                )
            }
            state.message?.let { message ->
                Text(stringResource(message), modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionMethodRow(title: String, description: String, selected: Boolean, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (active) Text(stringResource(R.string.connection_method_in_use), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}
