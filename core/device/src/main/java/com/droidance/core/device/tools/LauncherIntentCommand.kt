// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

/** Encodes a Home-style app entry on the frozen display without resetting the app. */
object LauncherIntentCommand {
    fun build(displayId: Int, flags: Int, component: String?, packageName: String,
              escape: (String) -> String): String = buildString {
        append("am start --display $displayId -f 0x${flags.toUInt().toString(16)}")
        append(" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        if (!component.isNullOrBlank()) append(" -n ${escape(component)}")
        if (packageName.isNotBlank()) append(" -p ${escape(packageName)}")
    }
}
