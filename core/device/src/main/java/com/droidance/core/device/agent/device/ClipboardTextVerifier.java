// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.function.LongConsumer;

/**
 * Deterministic clipboard write verifier for AIR text input.
 *
 * <p>On some devices (e.g. OPPO Android 16) a background clipboard write returns a
 * success exit code without actually replacing the primary clip, so a following paste
 * inserts the previous clipboard content instead of the target text. The sidecar must
 * therefore read the primary clip back and compare it exactly against the target before
 * pasting. This class holds that exact comparison plus a non-sensitive fingerprint used
 * for logging (length + short SHA-256), so plaintext is never written to logs.
 */
public final class ClipboardTextVerifier {

    private ClipboardTextVerifier() {
    }

    /**
     * Exact match between the intended text and the clipboard read-back. No trimming or
     * normalization: a read-back that differs only by surrounding whitespace, is empty,
     * still holds the old text, or differs by any Unicode content is a mismatch.
     */
    public static boolean matches(String target, String readback) {
        if (target == null || readback == null) {
            return false;
        }
        return target.equals(readback);
    }

    /** Poll one write's readback; never rewrite the payload or replay an input event. */
    public static String awaitMatch(String target, Supplier<String> read, LongSupplier clock,
                                    LongConsumer pause, long timeoutMs) {
        long deadline = clock.getAsLong() + Math.max(0L, timeoutMs);
        String value = read.get();
        while (!matches(target, value)) {
            long remaining = deadline - clock.getAsLong();
            if (remaining <= 0) break;
            pause.accept(Math.min(25L, remaining));
            value = read.get();
        }
        return value;
    }

    /** Non-sensitive fingerprint for logs: UTF-16 length plus a short SHA-256 prefix. */
    public static String fingerprint(String text) {
        if (text == null) {
            return "len=0,sha256=null";
        }
        return "len=" + text.length() + ",sha256=" + shortSha256(text);
    }

    private static String shortSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
