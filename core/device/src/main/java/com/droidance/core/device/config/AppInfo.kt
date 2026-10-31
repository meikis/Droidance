// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.config

import com.droidance.core.agent.config.*

import com.droidance.core.device.config.*

import kotlinx.serialization.Serializable

@Serializable
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false,
    val launchTarget: String? = null,
    val lastUpdateTime: Long? = null,
    val labelVerified: Boolean = true,
    val launcherVerified: Boolean = true,
    val enabled: Boolean? = null
)
