// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidance.R
import com.droidance.ui.components.GlassAlertDialog
import com.droidance.ui.viewmodel.UpdateViewModel

/** App-wide update dialogs, so a release found on any screen can be acted on. */
@Composable
fun AppUpdateHost(viewModel: UpdateViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val release by viewModel.availableRelease.collectAsStateWithLifecycle()
    val dialogVisible by viewModel.dialogVisible.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val permissionRequired by viewModel.installPermissionRequired.collectAsStateWithLifecycle()

    release?.takeIf { dialogVisible }?.let { available ->
        AppUpdateDialog(
            release = available,
            downloadState = downloadState,
            onDismissRequest = viewModel::dismissDialog,
            onStartDownload = { viewModel.startDownload(available) },
            onCancelDownload = viewModel::cancelDownload,
            onInstall = viewModel::installApk
        )
    }

    if (permissionRequired) {
        GlassAlertDialog(
            onDismissRequest = { viewModel.dismissInstallPermission(keepPendingInstall = false) },
            title = stringResource(R.string.update_permission_dialog_title),
            text = stringResource(R.string.update_permission_dialog_desc),
            confirmLabel = stringResource(R.string.update_permission_dialog_confirm),
            onConfirm = {
                viewModel.dismissInstallPermission(keepPendingInstall = true)
                runCatching { context.startActivity(viewModel.getManageUnknownAppSourcesIntent()) }
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismiss = { viewModel.dismissInstallPermission(keepPendingInstall = false) }
        )
    }
}
