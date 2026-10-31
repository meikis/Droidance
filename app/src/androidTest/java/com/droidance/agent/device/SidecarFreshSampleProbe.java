// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device;

import com.droidance.core.agent.device.*;
import com.droidance.core.knowledge.agent.device.*;
import com.droidance.core.device.agent.device.*;

import com.droidance.agent.device.*;

import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Shell-side acceptance probe for the newly bounded sidecar contracts.
 * It owns only a disposable virtual display and the app-owned InputProbeActivity.
 */
public final class SidecarFreshSampleProbe {
    private SidecarFreshSampleProbe() {}

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(
                SidecarShellContext.create());
        new Thread(() -> {
            try {
                if (!service.start(1080, 2376, 480)) {
                    throw new AssertionError(service.getLastError());
                }
                int display = service.getDisplayId();
                if (!service.ensureDisplayInteractive(display)) {
                    throw new AssertionError(service.getLastError());
                }
                Bundle launch = service.execShellCommand(
                        "am start --display " + display +
                                " -n com.droidance.debug/com.droidance.core.device.debug.InputProbeActivity",
                        15000,
                        java.util.UUID.randomUUID().toString());
                if (launch == null || launch.getInt("exit_code", -1) != 0 ||
                        launch.getString("stdout", "").contains("Error:")) {
                    throw new AssertionError("probe launch failed: " + launch);
                }
                SystemClock.sleep(1200);

                int[] coordinates = new int[]{540, 1188, 12, 12, 1067, 2363};
                long firstCallAt = SystemClock.elapsedRealtime();
                Bundle first = requireSample(service.captureFreshSample(display, coordinates));
                assertSample(first, display, coordinates.length / 2, firstCallAt);
                SystemClock.sleep(80);
                long secondCallAt = SystemClock.elapsedRealtime();
                Bundle second = requireSample(service.captureFreshSample(display, coordinates));
                assertSample(second, display, coordinates.length / 2, secondCallAt);
                if (second.getLong("capturedElapsedMs") <= first.getLong("capturedElapsedMs")) {
                    throw new AssertionError("fresh sample did not advance elapsed time");
                }
                if (service.captureFreshSample(display + 10000, coordinates) != null) {
                    throw new AssertionError("invalid display unexpectedly returned a fresh sample");
                }

                String cancelToken = "sidecar-cancel-probe";
                final Bundle[] canceled = new Bundle[1];
                Thread focusWait = new Thread(() -> canceled[0] = service.awaitInputFocus(
                        display, 1500, cancelToken), "sidecar-focus-cancel-probe");
                focusWait.start();
                SystemClock.sleep(100);
                service.cancelInputFocus(cancelToken);
                focusWait.join(3000);
                if (focusWait.isAlive() || canceled[0] == null ||
                        !"INPUT_FOCUS_CANCELED".equals(canceled[0].getString("diagnostic"))) {
                    throw new AssertionError("focus cancellation failed: " + canceled[0]);
                }

                if (!service.tap(display, 540, 1188)) {
                    throw new AssertionError("probe focus tap failed: " + service.getLastError());
                }
                Bundle readiness = service.awaitInputFocus(
                        display, 1500, "sidecar-fresh-sample-probe");
                String editor = readiness.getString("target");
                String diagnostic = readiness.getString("diagnostic");
                long elapsedMs = readiness.getLong("elapsedMs", -1L);
                if (!"INPUT_CONNECTION_READY".equals(diagnostic) ||
                        editor == null || editor.trim().isEmpty() || elapsedMs < 0 || elapsedMs > 5000) {
                    throw new AssertionError("editable focus readiness failed: " + readiness);
                }
                System.out.println(
                        "SIDECAR_FRESH_SAMPLE_PASS display=" + display +
                                " size=" + first.getInt("width") + "x" + first.getInt("height") +
                                " colors=" + first.getIntArray("colors").length +
                                " first_elapsed=" + first.getLong("capturedElapsedMs") +
                                " second_elapsed=" + second.getLong("capturedElapsedMs") +
                                " editor=" + editor + " focus_elapsed=" + elapsedMs);
                service.destroy();
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                service.destroy();
                System.exit(1);
            }
        }, "sidecar-fresh-sample-probe").start();
        Looper.loop();
    }

    private static Bundle requireSample(Bundle sample) {
        if (sample == null) throw new AssertionError("captureFreshSample returned null");
        return sample;
    }

    private static void assertSample(Bundle sample, int expectedDisplay, int expectedColors,
                                     long callStartedAt) {
        int[] colors = sample.getIntArray("colors");
        int width = sample.getInt("width");
        int height = sample.getInt("height");
        int display = sample.getInt("displayId", -1);
        int rotation = sample.getInt("rotation", -1);
        long capturedAtMs = sample.getLong("capturedAtMs", -1L);
        long capturedElapsedMs = sample.getLong("capturedElapsedMs", -1L);
        if (colors == null || colors.length != expectedColors || width <= 0 || height <= 0 ||
                display != expectedDisplay ||
                (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) ||
                capturedAtMs <= 0 || capturedElapsedMs < callStartedAt) {
            throw new AssertionError("invalid fresh sample: " + sample);
        }
    }
}
