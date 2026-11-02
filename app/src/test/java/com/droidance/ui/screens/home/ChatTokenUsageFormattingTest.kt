// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTokenUsageFormattingTest {
    @Test fun labelsUseTheSettingsThresholdsAndKeepUnknownDifferentFromReportedZero() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("999", tokenCountLabel(999, true, true))
            assertEquals("1.0K", tokenCountLabel(1_000, true, true))
            assertEquals("12.8K", tokenCountLabel(12_800, true, true))
            assertEquals("1.00M", tokenCountLabel(1_000_000, true, true))
            assertEquals("1.23M+", tokenCountLabel(1_234_567, true, false))
            assertEquals("—", tokenCountLabel(0, false, false))
            assertEquals("0", tokenCountLabel(0, true, true))
        } finally { Locale.setDefault(previous) }
    }
}
