// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import com.droidance.settings.GeneralSettingsRepository
import com.droidance.update.AppUpdateManager
import com.droidance.update.model.AppReleaseInfo
import com.droidance.update.model.DownloadProgressState
import com.droidance.update.model.UpdateCheckState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val appUpdateManager: AppUpdateManager,
    private val generalSettingsRepository: GeneralSettingsRepository
) : ViewModel() {

    val checkState: StateFlow<UpdateCheckState> = appUpdateManager.checkState
    val downloadState: StateFlow<DownloadProgressState> = appUpdateManager.downloadState
    val availableRelease: StateFlow<AppReleaseInfo?> = appUpdateManager.availableRelease
    val dialogVisible: StateFlow<Boolean> = appUpdateManager.dialogVisible
    val installPermissionRequired: StateFlow<Boolean> = appUpdateManager.installPermissionRequired
    val autoCheckUpdate: StateFlow<Boolean> = generalSettingsRepository.autoCheckUpdate

    fun checkForUpdates() = appUpdateManager.checkForUpdates(force = true)

    fun showDialog() = appUpdateManager.showDialog()

    fun dismissDialog() = appUpdateManager.dismissDialog()

    fun startDownload(release: AppReleaseInfo) = appUpdateManager.startDownload(release)

    fun cancelDownload() = appUpdateManager.cancelDownload()

    fun installApk(file: File) = appUpdateManager.installDownloaded(file)

    fun dismissInstallPermission(keepPendingInstall: Boolean) =
        appUpdateManager.dismissInstallPermission(keepPendingInstall)

    fun getManageUnknownAppSourcesIntent(): Intent =
        appUpdateManager.getManageUnknownAppSourcesIntent()

    fun setAutoCheckUpdate(enabled: Boolean) {
        generalSettingsRepository.setAutoCheckUpdate(enabled)
    }
}
