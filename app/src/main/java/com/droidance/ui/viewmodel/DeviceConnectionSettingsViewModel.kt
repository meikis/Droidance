// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidance.R
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.device.agent.device.DeviceConnectionMethod
import com.droidance.core.device.agent.device.RootConnectionManager
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.core.device.air.AirModePairingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DeviceConnectionSettingsState(
    val selected: DeviceConnectionMethod = DeviceConnectionMethod.WIRELESS,
    val active: DeviceConnectionMethod = DeviceConnectionMethod.WIRELESS,
    val connected: Boolean = false,
    val wirelessConnected: Boolean = false,
    val busy: Boolean = false,
    val message: Int? = null
)

@HiltViewModel
class DeviceConnectionSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val root: RootConnectionManager,
    private val air: AirModePairingManager,
    private val bridge: VirtualSpaceSidecarBridge,
    private val agent: AgentRuntime
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceConnectionSettingsState(selected = root.currentMethod, active = root.currentMethod))
    val state = _state.asStateFlow()
    private var pendingWireless = false

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                if (pendingWireless && air.isConnected) finishWirelessSwitch()
                delay(1_000)
            }
        }
    }

    fun select(method: DeviceConnectionMethod) {
        if (!_state.value.busy) {
            if (method != DeviceConnectionMethod.WIRELESS) pendingWireless = false
            _state.update { it.copy(selected = method, message = null) }
        }
    }

    fun connectRoot() {
        if (taskRunning()) { showTaskRunning(); return }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val authorized = withContext(Dispatchers.IO) { root.authorize() }
            if (!authorized) {
                _state.update { it.copy(busy = false, message = R.string.connection_root_failed) }
                return@launch
            }
            if (taskRunning()) {
                _state.update { it.copy(busy = false, message = R.string.connection_switch_task_running) }
                return@launch
            }
            root.beginRootCandidate()
            val ready = try {
                withContext(Dispatchers.IO) {
                    val metrics = context.resources.displayMetrics
                    bridge.ensureResidentChannel(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1),
                        metrics.densityDpi.coerceAtLeast(1), RuntimeMode.STANDARD)
                }
            } finally { root.endRootCandidate() }
            if (ready && !taskRunning()) {
                root.select(DeviceConnectionMethod.ROOT)
                withContext(Dispatchers.IO) { air.disconnect() }
                pendingWireless = false
                _state.update { it.copy(busy = false, message = null) }
            } else {
                _state.update { it.copy(busy = false, message = if (taskRunning()) R.string.connection_switch_task_running else R.string.connection_root_start_failed) }
            }
            refresh()
        }
    }

    fun connectWireless(openPairing: () -> Unit) {
        if (taskRunning()) { showTaskRunning(); return }
        if (root.currentMethod == DeviceConnectionMethod.WIRELESS && _state.value.connected) {
            openPairing()
            return
        }
        pendingWireless = true
        if (air.isConnected) viewModelScope.launch { finishWirelessSwitch() } else openPairing()
    }

    fun onReturnFromWireless() {
        if (!pendingWireless) return
        if (air.isConnected) {
            viewModelScope.launch { finishWirelessSwitch() }
            return
        }
        pendingWireless = false
        if (root.currentMethod == DeviceConnectionMethod.ROOT) {
            _state.update { it.copy(busy = true) }
            viewModelScope.launch {
                withContext(Dispatchers.IO) { air.disconnect() }
                _state.update { it.copy(busy = false) }
                refresh()
            }
        }
    }

    private suspend fun finishWirelessSwitch() {
        if (!pendingWireless || _state.value.busy) return
        if (taskRunning()) { pendingWireless = false; showTaskRunning(); return }
        _state.update { it.copy(busy = true) }
        val shellReady = withContext(Dispatchers.IO) { air.shell("id -u", 2_000L).trim() == "2000" }
        if (shellReady && !taskRunning()) {
            root.select(DeviceConnectionMethod.WIRELESS)
            pendingWireless = false
        } else if (taskRunning()) {
            pendingWireless = false
            showTaskRunning()
        }
        _state.update { it.copy(busy = false) }
        refresh()
    }

    private suspend fun refresh() {
        val active = root.currentMethod
        val wireless = air.isConnected
        val connected = withContext(Dispatchers.IO) {
            if (active == DeviceConnectionMethod.ROOT) bridge.isResidentBinderAlive()
            else wireless || (air.isPreviouslyPaired && !air.isPairingRequired && bridge.isResidentBinderAlive())
        }
        _state.update { it.copy(active = active, connected = connected, wirelessConnected = wireless) }
    }

    private fun taskRunning(): Boolean = agent.state.value !in setOf(AgentState.IDLE, AgentState.ERROR)
    private fun showTaskRunning() { _state.update { it.copy(message = R.string.connection_switch_task_running) } }
}
