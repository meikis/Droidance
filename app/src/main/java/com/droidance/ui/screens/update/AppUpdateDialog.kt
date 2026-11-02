// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.config.LanguageManager
import com.droidance.ui.components.DroidanceDialog
import com.droidance.ui.components.FlatMarkdown
import com.droidance.update.ReleaseNotes
import com.droidance.update.model.AppReleaseInfo
import com.droidance.update.model.DownloadError
import com.droidance.update.model.DownloadProgressState
import java.io.File
import java.util.Locale

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    downloadState: DownloadProgressState,
    onDismissRequest: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (File) -> Unit
) {
    val later = stringResource(R.string.update_action_cancel)
    val update = stringResource(R.string.update_action_download)
    val downloading = downloadState is DownloadProgressState.Downloading
    val verifying = downloadState is DownloadProgressState.Verifying
    DroidanceDialog(
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.update_dialog_title, release.versionName),
        text = listOfNotNull(
            release.apkSizeBytes.takeIf { it > 0 }?.let(::formatFileSize),
            release.publishedAt.takeIf { it.isNotBlank() }?.take(10)
        ).joinToString(" · "),
        confirmLabel = when {
            downloading -> later
            verifying -> stringResource(R.string.update_action_verifying)
            downloadState is DownloadProgressState.ReadyToInstall -> stringResource(R.string.update_action_install)
            else -> update
        },
        onConfirm = {
            when (downloadState) {
                is DownloadProgressState.Downloading -> onDismissRequest()
                is DownloadProgressState.ReadyToInstall -> onInstall(downloadState.apkFile)
                DownloadProgressState.Verifying -> Unit
                else -> onStartDownload()
            }
        },
        confirmEnabled = !verifying,
        dismissLabel = if (downloading) stringResource(R.string.common_cancel) else later.takeUnless { verifying },
        onDismiss = if (downloading) onCancelDownload else onDismissRequest
    ) {
        val notes = ReleaseNotes.forLanguage(release.releaseNotes, LanguageManager.resolvedTag(LocalContext.current))
        if (notes.isNotBlank()) {
            FlatMarkdown(content = notes)
        }
        if (downloadState is DownloadProgressState.Downloading) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { (downloadState.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    stringResource(R.string.update_action_downloading, downloadState.percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (downloadState is DownloadProgressState.Failed) {
            Text(
                when (downloadState.reason) {
                    DownloadError.CHECKSUM_MISMATCH -> stringResource(R.string.update_download_error_checksum)
                    DownloadError.MISSING_CHECKSUM -> stringResource(R.string.update_download_error_missing_checksum)
                    DownloadError.INVALID_APK -> stringResource(R.string.update_download_error_invalid_apk)
                    DownloadError.NETWORK_ERROR -> stringResource(R.string.update_download_error_network)
                    DownloadError.STORAGE_ERROR -> stringResource(R.string.update_download_error_storage)
                    DownloadError.CANCELED -> stringResource(R.string.common_cancel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
