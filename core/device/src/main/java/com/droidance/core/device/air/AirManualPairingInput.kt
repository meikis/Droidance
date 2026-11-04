// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

/** The notification fallback accepts the local pairing port followed by the six-digit code. */
data class AirManualPairingInput(val port: Int, val code: String) {
    companion object {
        fun parse(input: String): AirManualPairingInput? {
            val parts = input.trim().split(Regex("\\s+"))
            if (parts.size != 2) return null
            if (parts[0].any { it !in '0'..'9' }) return null
            val port = parts[0].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val code = parts[1].takeIf { it.length == 6 && it.all { digit -> digit in '0'..'9' } }
                ?: return null
            return AirManualPairingInput(port, code)
        }
    }
}
