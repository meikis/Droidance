// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkUpdateVerifier @Inject constructor(@ApplicationContext private val context: Context) {
    @Suppress("DEPRECATION")
    fun isValidUpdate(file: File): Boolean = runCatching {
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        if (candidate.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (!isSupportedVersionUpgrade(installed.longVersionCode, installed.versionName.orEmpty(),
                candidate.longVersionCode, candidate.versionName.orEmpty(),
                candidate.applicationInfo?.minSdkVersion ?: return false, android.os.Build.VERSION.SDK_INT)) return false
        val installedSigners = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        val candidateSigners = candidate.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        // Require the current signing identity. Android's installer verifies the APK again.
        sameSigningIdentity(installedSigners, candidateSigners)
    }.getOrDefault(false)
}

internal fun isSupportedVersionUpgrade(installedCode: Long, installedVersion: String,
    candidateCode: Long, candidateVersion: String, minimumSdk: Int, deviceSdk: Int): Boolean =
    candidateCode > installedCode && minimumSdk <= deviceSdk &&
        VersionComparator.isNewer(candidateVersion, installedVersion)

internal fun sameSigningIdentity(installed: List<ByteArray>, candidate: List<ByteArray>): Boolean =
    installed.isNotEmpty() && installed.size == candidate.size &&
        installed.all { expected -> candidate.any { it.contentEquals(expected) } } &&
        candidate.all { actual -> installed.any { it.contentEquals(actual) } }
