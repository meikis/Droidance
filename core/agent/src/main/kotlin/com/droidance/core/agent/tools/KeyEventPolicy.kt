// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/**
 * Resolves Android KeyEvent names, with or without the KEYCODE_ prefix. Names only: a raw
 * numeric code would make "KEYCODE_4" (digit four) indistinguishable from code 4 (BACK).
 */
object KeyEventPolicy {
    private val keyCodes: Map<String, Int> = buildMap {
        put("HOME", 3)
        put("BACK", 4)
        put("RECENTS", 187)
        put("APP_SWITCH", 187)
        put("POWER", 26)
        put("VOLUME_UP", 24)
        put("VOLUME_DOWN", 25)
        put("SCREENSHOT", 120)
        put("NOTIFICATION", 83)
        put("SEARCH", 84)
        put("MENU", 82)
        put("ENTER", 66)
        put("DEL", 67)
        put("FORWARD_DEL", 112)
        put("ESCAPE", 111)
        put("TAB", 61)
        put("SPACE", 62)
        put("PASTE", 279)
        put("COPY", 278)
        put("CUT", 277)
        put("DPAD_UP", 19)
        put("DPAD_DOWN", 20)
        put("DPAD_LEFT", 21)
        put("DPAD_RIGHT", 22)
        put("DPAD_CENTER", 23)
        put("PAGE_UP", 92)
        put("PAGE_DOWN", 93)
        put("MOVE_HOME", 122)
        put("MOVE_END", 123)
        put("STAR", 17)
        put("POUND", 18)
        put("COMMA", 55)
        put("PERIOD", 56)
        put("MINUS", 69)
        put("EQUALS", 70)
        put("SLASH", 76)
        put("AT", 77)
        put("PLUS", 81)
        put("NUMPAD_DIVIDE", 154)
        put("NUMPAD_MULTIPLY", 155)
        put("NUMPAD_SUBTRACT", 156)
        put("NUMPAD_ADD", 157)
        put("NUMPAD_DOT", 158)
        put("NUMPAD_COMMA", 159)
        put("NUMPAD_ENTER", 160)
        put("NUMPAD_EQUALS", 161)
        for (digit in 0..9) {
            put("$digit", 7 + digit)
            put("NUMPAD_$digit", 144 + digit)
        }
        for (letter in 'A'..'Z') put("$letter", 29 + (letter - 'A'))
    }

    fun resolveKeyCode(rawKey: String?): Int? {
        val normalized = rawKey
            ?.trim()
            ?.uppercase()
            ?.removePrefix("KEYCODE_")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return keyCodes[normalized]
    }
}
