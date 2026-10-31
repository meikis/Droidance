// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenFrameFingerprintTest {
    @Test fun middleOnlyChangeInvalidatesImageCacheIdentity() {
        val frame = ByteArray(8192) { 7 }
        val changed = frame.copyOf().apply { this[4096] = 8 }
        assertEquals(screenFrameFingerprint(frame), screenFrameFingerprint(frame.copyOf()))
        assertNotEquals(screenFrameFingerprint(frame), screenFrameFingerprint(changed))
    }
}
