// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassComponentsSourceContractTest {
    private val source = File("src/main/java/com/droidance/ui/components/GlassComponents.kt").readText()

    @Test
    fun glass_alert_dialog_delegates_to_neutral_dialog() {
        assertTrue(source.contains("fun GlassAlertDialog("))
        assertTrue(source.contains("DroidanceDialog("))
        assertFalse(source.contains("innerRimBrush"))
        assertFalse(source.contains("drawRect(innerRimBrush)"))
    }
}
