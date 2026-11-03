// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import com.droidance.core.device.air.AirModePairingManager
import javax.inject.Inject
import javax.inject.Singleton

enum class ShellIdentity {
    DEFAULT,
    SHELL
}

@Singleton
class PrivilegedShellRunner @Inject constructor(
    private val airModePairingManager: AirModePairingManager
) {
    fun isAvailable(identity: ShellIdentity = ShellIdentity.SHELL): Boolean =
        when (identity) {
            ShellIdentity.DEFAULT,
            ShellIdentity.SHELL -> isAdbShellReady()
        }

    fun currentAdbShellUid(): Int? {
        if (RuntimeProcessRole.isOwner) return ADB_SHELL_UID
        if (!airModePairingManager.isConnected) return null
        return airModePairingManager
            .shell("id -u", ADB_UID_PROBE_TIMEOUT_MS)
            .trim()
            .takeUnless { it.startsWith("[AIR_") }
            ?.toIntOrNull()
    }

    fun isAdbShellUid(): Boolean =
        currentAdbShellUid() == ADB_SHELL_UID

    fun isAdbShellReady(): Boolean =
        RuntimeProcessRole.isOwner || (airModePairingManager.isConnected && isAdbShellUid())

    fun describeShellUnavailable(): String {
        val uid = currentAdbShellUid()
        return when {
            !airModePairingManager.isConnected ->
                "Air ADB shell is disconnected; wireless debugging pairing is required"
            uid != ADB_SHELL_UID ->
                "Air ADB uid=${uid ?: "unknown"}; required shell uid=$ADB_SHELL_UID"
            else -> "Air ADB shell is unavailable"
        }
    }

    companion object {
        const val ADB_SHELL_UID = 2000
        private const val ADB_UID_PROBE_TIMEOUT_MS = 2_000L
    }
}
