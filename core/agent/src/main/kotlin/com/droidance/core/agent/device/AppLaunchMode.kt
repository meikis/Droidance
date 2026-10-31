// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.device

import com.droidance.core.agent.device.*

/** Generic task-stack policy chosen by the Operator for an app launch. */
enum class AppLaunchMode(val wireName: String) {
    RESUME("resume"),
    RESET_TASK("reset_task"),
    FRESH("fresh");

    companion object {
        fun parse(raw: String?): AppLaunchMode? = entries.firstOrNull {
            it.wireName == raw?.trim()?.lowercase()
        }
    }
}
