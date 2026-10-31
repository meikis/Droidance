// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

/** Shell encoding only; route selection and verification belong to the calling Agent. */
object ExplicitIntentCommand {
    fun build(displayId: Int, flags: Int, uri: String?, action: String?, component: String?,
              packageName: String?, options: ExplicitIntentOptions, escape: (String) -> String): String = buildString {
        append("am start --display $displayId -f 0x${flags.toUInt().toString(16)}")
        val effectiveAction = action?.takeIf(String::isNotBlank)
            ?: uri?.takeIf(String::isNotBlank)?.let { "android.intent.action.VIEW" }
        effectiveAction?.let { append(" -a ${escape(it)}") }
        uri?.takeIf(String::isNotBlank)?.let { append(" -d ${escape(it)}") }
        component?.takeIf(String::isNotBlank)?.let { append(" -n ${escape(it)}") }
        // Package scoping does not force a non-exported component. Never drop a supplied target.
        packageName?.takeIf(String::isNotBlank)?.let { append(" -p ${escape(it)}") }
        append(options.toShellArguments(escape))
    }
}
