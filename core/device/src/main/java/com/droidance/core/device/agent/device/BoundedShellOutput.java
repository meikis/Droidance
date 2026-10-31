// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Drains process pipes completely while keeping Binder responses below the transaction limit. */
public final class BoundedShellOutput {
    public static final String TRUNCATED = "[SHELL_OUTPUT_TRUNCATED]";
    public static final int MAX_BYTES = 96 * 1024;
    public static final class Result {
        public final String text;
        public final long originalBytes;
        public final boolean truncated;
        Result(String text, long originalBytes, boolean truncated) {
            this.text=text; this.originalBytes=originalBytes; this.truncated=truncated;
        }
    }

    private BoundedShellOutput() {}

    public static Result read(InputStream input) throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            int keep = Math.min(count, MAX_BYTES - retained.size());
            retained.write(buffer, 0, keep);
            total += count;
        }
        byte[] bytes = retained.toByteArray();
        int end = bytes.length;
        if (total > end && end > 0) {
            int start = end - 1;
            while (start > 0 && (bytes[start] & 0xc0) == 0x80) start--;
            int lead = bytes[start] & 0xff;
            int length = lead < 0x80 ? 1 : lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
            if (start + length > end) end = start;
        }
        String text = new String(bytes, 0, end, StandardCharsets.UTF_8);
        return new Result(text, total, total > MAX_BYTES);
    }
}
