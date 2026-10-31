// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

/** Android dumpsys may report a service failure in text while returning exit code zero. */
object SystemQueryDiagnostic {
    fun code(stdout: String, stderr: String): String? {
        val lines = (stdout + "\n" + stderr).lineSequence().map(String::trim)
        for (line in lines) {
            when {
                line.startsWith("Permission Denial:", true) ||
                    line.startsWith("Permission denied", true) ||
                    line.startsWith("java.lang.SecurityException:") -> return "SYSTEM_QUERY_PERMISSION_DENIED"
                line.startsWith("Can't find service:", true) ||
                    line.startsWith("Service not found:", true) ||
                    line.startsWith("Unknown command:", true) -> return "SYSTEM_QUERY_SERVICE_UNAVAILABLE"
                line.startsWith("DUMP TIMEOUT", true) ||
                    line.startsWith("*** SERVICE DUMP TIMEOUT", true) -> return "SYSTEM_QUERY_TIMEOUT"
            }
        }
        return null
    }
}
