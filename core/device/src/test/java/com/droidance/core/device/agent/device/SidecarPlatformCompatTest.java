// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import org.junit.Test;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SidecarPlatformCompatTest {
    private static final int TRUSTED = 1 << 10, OWN_GROUP = 1 << 11, ALWAYS_UNLOCKED = 1 << 12, OWN_FOCUS = 1 << 14;
    private static final int RESTRICTED = TRUSTED | OWN_GROUP | ALWAYS_UNLOCKED | OWN_FOCUS;

    public static class BufferOnlyListener {
        final Consumer<Object> consumer;
        public BufferOnlyListener(Consumer<Object> consumer) { this.consumer = consumer; }
    }

    public static class StatusListener {
        final ObjIntConsumer<Object> consumer;
        public StatusListener(ObjIntConsumer<Object> consumer) { this.consumer = consumer; }
    }

    @Test public void shellDisplaysOnlyRequestFlagsItsPlatformGrants() {
        assertEquals(0, SidecarPlatformCompat.virtualDisplayFlags(30) & RESTRICTED);
        assertEquals(0, SidecarPlatformCompat.virtualDisplayFlags(32) & RESTRICTED);
        assertEquals(TRUSTED | OWN_GROUP | ALWAYS_UNLOCKED, SidecarPlatformCompat.virtualDisplayFlags(33) & RESTRICTED);
        assertEquals(RESTRICTED, SidecarPlatformCompat.virtualDisplayFlags(34) & RESTRICTED);
        assertEquals(RESTRICTED, SidecarPlatformCompat.virtualDisplayFlags(36) & RESTRICTED);
    }

    @Test public void bufferOnlyListenerReportsSuccessAndFailureAsStatus() throws Exception {
        Object[] screenshot = new Object[1];
        int[] status = new int[1];
        BufferOnlyListener listener = (BufferOnlyListener) SidecarPlatformCompat.newCaptureListener(
                BufferOnlyListener.class, (value, code) -> { screenshot[0] = value; status[0] = code; });
        Object frame = new Object();
        listener.consumer.accept(frame);
        assertSame(frame, screenshot[0]);
        assertEquals(0, status[0]);
        listener.consumer.accept(null);
        assertNull(screenshot[0]);
        assertEquals(-1, status[0]);
    }

    @Test public void statusListenerReceivesThePlatformStatus() throws Exception {
        int[] status = new int[1];
        StatusListener listener = (StatusListener) SidecarPlatformCompat.newCaptureListener(
                StatusListener.class, (value, code) -> status[0] = code);
        listener.consumer.accept(new Object(), 7);
        assertEquals(7, status[0]);
    }
}
