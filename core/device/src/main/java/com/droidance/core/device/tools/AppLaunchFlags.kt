// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import android.content.Intent
import com.droidance.core.agent.device.AppLaunchMode

/** Android flags for the selected mode; independent of task or launch history. */
val AppLaunchMode.intentFlags: Int
    get() = when (this) {
        AppLaunchMode.RESUME -> Intent.FLAG_ACTIVITY_NEW_TASK
        AppLaunchMode.RESET_TASK -> Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        AppLaunchMode.FRESH -> Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    }
