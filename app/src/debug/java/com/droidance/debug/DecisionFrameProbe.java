// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug;

import com.droidance.core.agent.debug.*;
import com.droidance.core.device.debug.*;

import com.droidance.debug.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import com.droidance.core.device.agent.device.NativeScreenFrame;
import com.droidance.core.device.agent.device.VirtualSpaceSidecarService;
import java.util.Arrays;

/** Run with app_process as shell; owns and destroys only its isolated test display. */
public final class DecisionFrameProbe {
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(
                com.droidance.core.device.agent.device.SidecarShellContext.create());
        Thread worker = new Thread(() -> {
            try {
                if (!service.start(1080, 2376, 480)) throw new AssertionError(service.getLastError());
                int display = service.getDisplayId();
                String component = "com.droidance.debug/com.droidance.debug.ObservationProbeActivity";
                if (!service.ensureDisplayInteractive(display)) throw new AssertionError(service.getLastError());
                service.execShellCommand("am start --display " + display + " -n " + component, 15000, java.util.UUID.randomUUID().toString());
                awaitColor(service, display, Color.RED);
                Bundle firstFacts;
                try (NativeScreenFrame first = service.captureFreshFrame(display, 8)) {
                    SystemClock.sleep(150);
                    try (NativeScreenFrame second = service.captureFreshFrame(display, 8)) {
                        if (first == null || second == null ||
                                second.getMetadata().getLong("frameAtMs") <= first.getMetadata().getLong("frameAtMs")) {
                            throw new AssertionError("A static screen must allow a new synchronous capture");
                        }
                        firstFacts = first.getMetadata();
                        assertReferenceSampleMatchesFreshSample(service, display, first);
                    }
                }
                for (int i = 0; i < 4; i++) {
                    if (!service.tap(display, 540, 1188)) throw new AssertionError(service.getLastError());
                    awaitColor(service, display, i % 2 == 0 ? Color.BLUE : Color.RED);
                }
                System.out.println("PASS display=" + display + " static_fresh_captures=2 delayed_transitions=4" +
                        " native_png_size=" + firstFacts.getInt("referenceWidth") + "x" + firstFacts.getInt("referenceHeight") +
                        " preview_jpeg_size=" + previewSize(service, display));
                service.destroy();
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                service.destroy();
                System.exit(1);
            }
        }, "decision-frame-probe");
        worker.start();
        Looper.loop();
    }

    private static void assertReferenceSampleMatchesFreshSample(
            VirtualSpaceSidecarService service, int display, NativeScreenFrame frame) throws Exception {
        Bundle facts = frame.getMetadata();
        int rawWidth = facts.getInt("referenceWidth", -1);
        int rawHeight = facts.getInt("referenceHeight", -1);
        if (rawWidth != 1080 || rawHeight != 2376) {
            throw new AssertionError("Unexpected raw reference size: " + rawWidth + "x" + rawHeight);
        }
        int[] referenceColors = facts.getIntArray("referenceColors");
        int[] referenceCoordinates = facts.getIntArray("referenceCoordinates");
        if (referenceColors == null || referenceCoordinates == null ||
                referenceCoordinates.length != referenceColors.length * 2) {
            throw new AssertionError("Missing frame pixel reference arrays");
        }
        byte[] png = frame.readEncodedImage();
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) throw new AssertionError("Native PNG cannot be decoded");
        try {
            if (bitmap.getWidth() != rawWidth || bitmap.getHeight() != rawHeight)
                throw new AssertionError("Native PNG dimensions differ from raw reference");
            for (int index = 0; index < referenceColors.length; index++) {
                if (bitmap.getPixel(referenceCoordinates[index * 2], referenceCoordinates[index * 2 + 1]) != referenceColors[index])
                    throw new AssertionError("Native PNG pixel differs from its capture reference");
            }
        } finally { bitmap.recycle(); }
        Bundle sample = service.captureFreshSample(display, referenceCoordinates);
        int[] freshColors = sample == null ? null : sample.getIntArray("colors");
        if (sample == null || sample.getInt("width", -1) != rawWidth ||
                sample.getInt("height", -1) != rawHeight ||
                !Arrays.equals(referenceColors, freshColors)) {
            throw new AssertionError("Fresh raw samples differ from frame reference: " + sample);
        }
    }

    private static String previewSize(VirtualSpaceSidecarService service, int display) {
        byte[] jpeg = service.capturePreviewJpeg(display);
        Bitmap bitmap = jpeg == null ? null : BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) return "unknown";
        try {
            if (bitmap.getWidth() != 720 || bitmap.getHeight() != 1584)
                throw new AssertionError("Mirror preview must remain 720x1584");
            return bitmap.getWidth() + "x" + bitmap.getHeight();
        } finally {
            bitmap.recycle();
        }
    }

    private static void awaitColor(VirtualSpaceSidecarService service, int display, int expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            try (NativeScreenFrame frame = service.captureFreshFrame(display, 8)) {
                if (frame != null) {
                    String component = frame.getMetadata().getString("foregroundComponent", "");
                    if (!component.contains("ObservationProbeActivity")) throw new AssertionError(component);
                    byte[] png = frame.readEncodedImage();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bitmap == null) throw new AssertionError("Native PNG cannot be decoded");
                    int color;
                    try { color = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2); }
                    finally { bitmap.recycle(); }
                    if (Math.abs(Color.red(color) - Color.red(expected)) < 10
                            && Math.abs(Color.blue(color) - Color.blue(expected)) < 10) return;
                }
            }
            SystemClock.sleep(120);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Expected color " + expected + ": " + service.getLastError());
    }
}
