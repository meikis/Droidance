// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device;

import com.droidance.core.agent.device.*;
import com.droidance.core.knowledge.agent.device.*;
import com.droidance.core.device.agent.device.*;

import com.droidance.agent.device.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ClipboardTextVerifierTest {

    @Test
    public void delayedClipboardVisibilityIsPolledWithoutChangingThePayload() {
        AtomicLong clock = new AtomicLong(0L);
        AtomicInteger reads = new AtomicInteger();
        String actual = ClipboardTextVerifier.awaitMatch("target",
            () -> reads.incrementAndGet() < 3 ? "previous" : "target",
            clock::get, clock::addAndGet, 350L);

        assertEquals("target", actual);
        assertEquals(3, reads.get());
        assertEquals(50L, clock.get());
    }

    @Test
    public void unchangedClipboardStopsAtTheDeadlineAndRemainsAMismatch() {
        AtomicLong clock = new AtomicLong(0L);
        AtomicInteger reads = new AtomicInteger();
        String actual = ClipboardTextVerifier.awaitMatch("target", () -> {
            reads.incrementAndGet();
            return "previous";
        }, clock::get, clock::addAndGet, 60L);

        assertEquals("previous", actual);
        assertFalse(ClipboardTextVerifier.matches("target", actual));
        assertEquals(4, reads.get());
        assertEquals(60L, clock.get());
    }

    @Test
    public void anImmediateMatchNeedsNoWaitAndZeroBudgetReadsOnce() {
        AtomicLong clock = new AtomicLong(0L);
        assertEquals("target", ClipboardTextVerifier.awaitMatch("target", () -> "target",
            clock::get, clock::addAndGet, 350L));
        assertEquals(0L, clock.get());
        assertEquals(null, ClipboardTextVerifier.awaitMatch("target", () -> null,
            clock::get, clock::addAndGet, 0L));
        assertEquals(0L, clock.get());
    }

    @Test
    public void exactMatchIsVerified() {
        assertTrue(ClipboardTextVerifier.matches("耳机", "耳机"));
        assertTrue(ClipboardTextVerifier.matches("wireless earbuds", "wireless earbuds"));
    }

    @Test
    public void emptyReadbackIsRejected() {
        assertFalse(ClipboardTextVerifier.matches("耳机", ""));
    }

    @Test
    public void staleClipboardContentIsRejected() {
        assertFalse(ClipboardTextVerifier.matches("耳机", "上一次复制的很长的一整段旧剪贴板内容"));
    }

    @Test
    public void whitespaceOnlyDifferenceIsRejected() {
        assertFalse(ClipboardTextVerifier.matches("耳机", " 耳机 "));
        assertFalse(ClipboardTextVerifier.matches("耳机", "耳机\n"));
    }

    @Test
    public void differentUnicodeContentIsRejected() {
        assertFalse(ClipboardTextVerifier.matches("耳机", "耳機"));
        assertFalse(ClipboardTextVerifier.matches("cafe", "café"));
    }

    @Test
    public void nullOperandsAreRejected() {
        assertFalse(ClipboardTextVerifier.matches("耳机", null));
        assertFalse(ClipboardTextVerifier.matches(null, "耳机"));
        assertFalse(ClipboardTextVerifier.matches(null, null));
    }

    @Test
    public void fingerprintRecordsLengthAndHashWithoutPlaintext() {
        String secret = "验证码123456";
        String fingerprint = ClipboardTextVerifier.fingerprint(secret);

        assertTrue(fingerprint.contains("len=" + secret.length()));
        assertTrue(fingerprint.contains("sha256="));
        assertFalse("fingerprint must not leak the plaintext", fingerprint.contains(secret));
    }

    @Test
    public void fingerprintIsStableAndDistinguishesDifferentText() {
        assertEquals(ClipboardTextVerifier.fingerprint("耳机"), ClipboardTextVerifier.fingerprint("耳机"));
        assertFalse(
            ClipboardTextVerifier.fingerprint("耳机").equals(ClipboardTextVerifier.fingerprint("耳環"))
        );
    }
}
