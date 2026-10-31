// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.droidance.core.device.tools.AdbShellExecutor
import com.droidance.update.model.InstallResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apkVerifier: ApkUpdateVerifier
) {
    fun canRequestPackageInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun createManageUnknownAppSourcesIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun install(file: File): InstallResult {
        if (!file.exists()) {
            return InstallResult.Failed("APK file not found: ${file.absolutePath}")
        }
        if (!apkVerifier.isValidUpdate(file)) {
            return InstallResult.Failed("APK package or signing identity does not match the installed application")
        }

        // 普通用户系统安装器覆盖安装
        if (!canRequestPackageInstalls()) {
            return InstallResult.PermissionRequired
        }

        return try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.PromptLaunched
        } catch (e: Exception) {
            InstallResult.Failed(e.message ?: "Failed to start package installer")
        }
    }
}
