// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device;

import com.droidance.core.agent.device.*;
import com.droidance.core.knowledge.agent.device.*;
import com.droidance.core.device.agent.device.*;

import com.droidance.agent.device.*;

import android.content.Context;
import android.os.Looper;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Real sidecar gestures against the dedicated debug target only. No model or external app. */
public final class MascotInputProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Context context = SidecarShellContext.create();
        byte[] hand = Files.readAllBytes(Paths.get(args[0]));
        boolean virtual = args.length > 1 && args[1].equals("virtual");
        VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(context);
        new Thread(() -> {
            try {
                int display = 0;
                if (virtual) {
                    if (!service.start(1080, 1920, 420)) throw new IllegalStateException("probe display creation failed: " + service.getLastError());
                    display = service.getDisplayId();
                }
                Process launch = new ProcessBuilder("am", "start", "--display", Integer.toString(display),
                    "--activity-multiple-task", "-n", "com.droidance.debug/com.droidance.debug.MascotGestureProbeActivity")
                    .redirectErrorStream(true).start();
                java.io.ByteArrayOutputStream launchBytes = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream input = launch.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) launchBytes.write(buffer, 0, count);
                }
                String launchOutput = new String(launchBytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                if (launch.waitFor() != 0 || launchOutput.contains("Error:"))
                    throw new IllegalStateException("probe target launch failed: " + launchOutput);
                Thread.sleep(900);
                if (!service.getForegroundComponent(display).contains("MascotGestureProbeActivity"))
                    throw new IllegalStateException("probe target is not foreground");
                long tap = CursorMotion.nowMs();
                if (!virtual) require(service.updateRuntimeCursorBitmap(true, 1, 500, 900, 500, 900, 0, tap, 0, true, hand));
                require(service.tap(display, 500, 900));
                long tapEnd = CursorMotion.nowMs();
                if (!virtual) require(service.updateRuntimeCursorBitmap(true, 1, 500, 900, 500, 900, 0, tap, tapEnd, true, hand));
                Thread.sleep(3000);
                if (!virtual) screenshot("/data/local/tmp/droidance-v3-pointer-tap-idle.png");
                long swipe = CursorMotion.nowMs();
                if (!virtual) require(service.updateRuntimeCursorBitmap(true, 2, 500, 1200, 500, 650, 600, swipe, 0, true, hand));
                if (!virtual) new Thread(() -> {
                    try { Thread.sleep(250); screenshot("/data/local/tmp/droidance-v3-pointer-swipe.png"); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }).start();
                require(service.swipe(display, 500, 1200, 500, 650, 600));
                long swipeEnd = CursorMotion.nowMs();
                if (!virtual) require(service.updateRuntimeCursorBitmap(true, 2, 500, 1200, 500, 650, 600, swipe, swipeEnd, true, hand));
                Thread.sleep(3000);
                if (!virtual) {
                    screenshot("/data/local/tmp/droidance-v3-pointer-swipe-idle.png");
                    require(service.setRuntimeSurfaceSuppressed(true));
                    screenshot("/data/local/tmp/droidance-v3-pointer-capture-clean.png");
                    require(service.setRuntimeSurfaceSuppressed(false));
                    screenshot("/data/local/tmp/droidance-v3-pointer-capture-restored.png");
                }
                System.out.println("INPUT_PROBE display=" + display + " tapStart=" + tap + " tapEnd=" + tapEnd +
                    " swipeStart=" + swipe + " swipeEnd=" + swipeEnd);
                service.updateRuntimeCursorBitmap(false, 0, 0, 0, 0, 0, 0, 0, 0, false, new byte[0]);
                service.shutdown();
                System.out.println("INPUT_PROBE_COMPLETE");
                System.exit(0);
            } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        }, "MascotInputProbe").start();
        Looper.loop();
    }

    private static void screenshot(String path) throws Exception {
        Process process = new ProcessBuilder("screencap", "-p", path).start();
        if (process.waitFor() != 0) throw new IllegalStateException("screenshot failed");
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("sidecar operation failed");
    }
}
