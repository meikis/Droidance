// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

data class PendingPermissionUiState(
    val requestId: String,
    val permissions: List<String>,
    val reason: String,
    val settingsRequired: Boolean = false
)
