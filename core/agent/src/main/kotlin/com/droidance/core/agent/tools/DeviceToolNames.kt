// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/** Canonical names of the model-visible device primitives. */
object DeviceToolNames {
    const val ANDROID_READ = "android_read"
    const val ANDROID_ACT = "android_act"
    const val SHELL = "shell"
    const val SCREEN_ACT = "screen_act"

    val deviceToolNames: Set<String> = linkedSetOf(ANDROID_READ, ANDROID_ACT, SHELL, SCREEN_ACT)
}
