// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

object DisplayForegroundParser {
    private val displayHeaderPatterns = listOf(
        Regex("""^\s*Display\s+#?(\d+)\b"""),
        Regex("""^\s*Display:\s*mDisplayId=(\d+)\b"""),
        Regex("""^\s*DisplayContent\{[^}]*\bdisplayId=(\d+)\b""")
    )
    private val componentPattern = Regex(
        """([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/(\.?[A-Za-z0-9_$]+(?:\.[A-Za-z0-9_$]+)*)"""
    )

    @JvmStatic
    fun parse(windowDump: String, displayId: Int): String? {
        var insideRequestedDisplay = false
        for (line in windowDump.lineSequence()) {
            displayIdFromHeader(line)?.let { currentDisplayId ->
                if (insideRequestedDisplay && currentDisplayId != displayId) return null
                insideRequestedDisplay = currentDisplayId == displayId
                return@let
            }
            if (!insideRequestedDisplay) continue
            componentPattern.find(line)?.let { return it.value }
        }
        return null
    }

    private fun displayIdFromHeader(line: String): Int? =
        displayHeaderPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
}
