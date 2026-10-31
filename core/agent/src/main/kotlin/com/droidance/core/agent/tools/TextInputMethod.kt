// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/** Platform delivery primitives selected by the same Agent, never an automatic retry plan. */
enum class TextInputMethod(val wireName: String) {
    SHORTCUT("shortcut"),
    PASTE_KEY("paste_key"),
    IME("ime");

    companion object {
        fun parse(value: String): TextInputMethod? = entries.firstOrNull { it.wireName == value }
    }
}
