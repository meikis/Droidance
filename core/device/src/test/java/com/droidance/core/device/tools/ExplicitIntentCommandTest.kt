// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import org.junit.Assert.*
import org.junit.Test

class ExplicitIntentCommandTest {
    private fun escape(value: String) = "'" + value.replace("'", "'\\''") + "'"

    @Test fun scopedUriKeepsPackageAndDisplayWithoutInventingComponent() {
        val command = ExplicitIntentCommand.build(4, 0x10000000, "example://search?q=a&b=c", null,
            null, "org.example.target", ExplicitIntentOptions.EMPTY, ::escape)
        assertTrue(command.startsWith("am start --display 4"))
        assertTrue(command.contains(" -p 'org.example.target'"))
        assertTrue(command.contains(" -a 'android.intent.action.VIEW'"))
        assertTrue(command.contains(" -d 'example://search?q=a&b=c'"))
        assertFalse(command.contains(" -n "))
    }

    @Test fun typedNativeExtrasArePreservedWithoutShellInterpretation() {
        val command = ExplicitIntentCommand.build(0, 0x10000000, null, "example.ACTION", null,
            null, ExplicitIntentOptions(stringExtras = mapOf("label" to "a'b $(id)"),
                intExtras = mapOf("hour" to 7), booleanExtras = mapOf("skip" to false)), ::escape)
        assertTrue(command.contains(" --ei 'hour' 7"))
        assertTrue(command.contains(" --ez 'skip' false"))
        assertTrue(command.contains(" --es 'label' 'a'\\''b $(id)'"))
        assertFalse(command.contains(" -p "))
        assertFalse(command.contains(" -d "))
    }
}
