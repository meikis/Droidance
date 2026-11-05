// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Identity of the APK loaded by this shell process, including in-place updates. */
final class SidecarPackageIdentity {
    enum State { CURRENT, CHANGED, UNAVAILABLE }

    final String packageName;
    final String sourcePath;
    final long updatedAt;

    private SidecarPackageIdentity(String packageName, String sourcePath, long updatedAt) {
        this.packageName = packageName;
        this.sourcePath = sourcePath;
        this.updatedAt = updatedAt;
    }

    static SidecarPackageIdentity capture(Context context, String packageName)
            throws PackageManager.NameNotFoundException {
        PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
        if (info.applicationInfo == null || info.applicationInfo.sourceDir == null) {
            throw new IllegalStateException("Installed package source unavailable");
        }
        return new SidecarPackageIdentity(packageName, info.applicationInfo.sourceDir, info.lastUpdateTime);
    }

    State state(Context context) {
        try {
            SidecarPackageIdentity installed = capture(context, packageName);
            return sourcePath.equals(installed.sourcePath) && updatedAt == installed.updatedAt
                    ? State.CURRENT : State.CHANGED;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return State.UNAVAILABLE;
        }
    }
}
