// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DeviceScreenFrameTest {
    @Test fun nativeEvidenceIdentityAndEncodingAreReusedWithoutChangingBytes() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val frame = DeviceScreenFrame(bytes, "test", 7)
        assertSame(frame.base64(), frame.base64())
        assertSame(frame.sha256, frame.sha256)
        assertEquals(bytes.contentHashCode().toLong(), frame.contentHash)
        assertArrayEquals(bytes, Base64.getDecoder().decode(frame.base64()))
        assertEquals(screenFrameFingerprint(bytes), frame.sha256)
    }

    @Test fun aChangeInTheMiddleOfANewFrameChangesItsOriginalIdentity() {
        val before = ByteArray(5000) { 80 }
        val after = before.copyOf().also { it[2500] = 90 }
        val first = DeviceScreenFrame(before, "test", 7)
        val next = DeviceScreenFrame(after, "test", 7)
        assertNotEquals(first.sha256, next.sha256)
        assertNotEquals(first.base64(), next.base64())
        assertArrayEquals(before, Base64.getDecoder().decode(first.base64()))
    }
}
