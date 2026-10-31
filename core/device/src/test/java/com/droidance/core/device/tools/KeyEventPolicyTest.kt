// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyEventPolicyTest {
    @Test
    fun resolvesNamedAndPrefixedKeyCodes() {
        assertEquals(66, KeyEventPolicy.resolveKeyCode("ENTER"))
        assertEquals(66, KeyEventPolicy.resolveKeyCode("KEYCODE_ENTER"))
        assertEquals(187, KeyEventPolicy.resolveKeyCode("RECENTS"))
        assertEquals(120, KeyEventPolicy.resolveKeyCode("SCREENSHOT"))
        assertEquals(84, KeyEventPolicy.resolveKeyCode("SEARCH"))
        assertEquals(84, KeyEventPolicy.resolveKeyCode("KEYCODE_SEARCH"))
    }

    @Test
    fun digitNamesResolveToDigitKeysNotRawCodes() {
        assertEquals(7, KeyEventPolicy.resolveKeyCode("KEYCODE_0"))
        assertEquals(11, KeyEventPolicy.resolveKeyCode("KEYCODE_4"))
        assertEquals(16, KeyEventPolicy.resolveKeyCode("9"))
        assertEquals(148, KeyEventPolicy.resolveKeyCode("KEYCODE_NUMPAD_4"))
        assertEquals(29, KeyEventPolicy.resolveKeyCode("KEYCODE_A"))
    }

    @Test
    fun resolvesArithmeticAndPunctuationKeys() {
        assertEquals(17, KeyEventPolicy.resolveKeyCode("KEYCODE_STAR"))
        assertEquals(56, KeyEventPolicy.resolveKeyCode("KEYCODE_PERIOD"))
        assertEquals(69, KeyEventPolicy.resolveKeyCode("KEYCODE_MINUS"))
        assertEquals(70, KeyEventPolicy.resolveKeyCode("KEYCODE_EQUALS"))
        assertEquals(155, KeyEventPolicy.resolveKeyCode("NUMPAD_MULTIPLY"))
    }

    @Test
    fun rejectsBlankUnknownAndNumericKeyCodes() {
        assertNull(KeyEventPolicy.resolveKeyCode(null))
        assertNull(KeyEventPolicy.resolveKeyCode(" "))
        assertNull(KeyEventPolicy.resolveKeyCode("NO_SUCH_KEY"))
        assertNull(KeyEventPolicy.resolveKeyCode("66"))
        assertNull(KeyEventPolicy.resolveKeyCode("KEYCODE_66"))
        assertNull(KeyEventPolicy.resolveKeyCode("999"))
    }
}
