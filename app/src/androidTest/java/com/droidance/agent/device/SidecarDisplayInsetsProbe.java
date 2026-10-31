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
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Shell-side acceptance: client-delivered Insets, geometry updates, new tasks and real taps. */
public final class SidecarDisplayInsetsProbe {
    private static final String PACKAGE = "com.droidance.debug";
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(SidecarShellContext.create());
        new Thread(() -> {
            int exit = 1;
            try {
                if (!service.start(1080, 2376, 480)) throw new AssertionError(service.getLastError());
                int display = service.getDisplayId();
                Bundle geometry = geometry(120, 48);
                requireConfigured(service.configureDisplayInsets(display, geometry));
                if (service.configureDisplayInsets(0, geometry).getBoolean("ok")) {
                    throw new AssertionError("Main display accepted virtual Insets");
                }
                launch(display, "first");
                JSONObject first = awaitReport(display, "first", 120, 48);
                if (first.getInt("contentWidth") != 1080 || first.getInt("contentHeight") != 2376) {
                    throw new AssertionError("Content was resized: " + first);
                }
                if (first.getInt("cutoutTop") != 120) throw new AssertionError("Missing cutout Insets: " + first);
                System.out.println("FIRST_CLIENT=" + first);
                SystemClock.sleep(250);
                if (!service.tap(display, first.getInt("buttonX"), first.getInt("buttonY"))) {
                    throw new AssertionError("Tap injection failed");
                }
                System.out.println("TAP_RECEIPT=" + service.getLastInputEvidence());
                awaitField(display, "taps", 1);
                System.out.println("CLIENT_INSETS_AND_TAP_PASS " + readReport(display));

                requireConfigured(service.configureDisplayInsets(display, geometry(160, 64)));
                awaitReport(display, "first", 160, 64);
                System.out.println("GEOMETRY_UPDATE_PASS");

                launch(display, "second");
                JSONObject second = awaitReport(display, "second", 160, 64);
                System.out.println("NEW_TASK_INSETS_PASS " + second);

                Bundle landscape = new Bundle();
                landscape.putInt("width", 2376); landscape.putInt("height", 1080); landscape.putInt("rotation", 1);
                landscape.putIntArray("status", new int[]{0, 48, 0, 0});
                landscape.putIntArray("navigation", new int[]{0, 0, 48, 0});
                landscape.putIntArray("cutout", new int[]{120, 0, 0, 0});
                requireConfigured(service.configureDisplayInsets(display, landscape));
                awaitReport(display, "second", 106, 0);
                System.out.println("LETTERBOX_HOST_GEOMETRY_PASS");
                requireConfigured(service.configureDisplayInsets(display, geometry(120, 48)));
                awaitReport(display, "second", 120, 48);

                Bundle invalid = geometry(120, 48); invalid.putInt("width", 0);
                if (service.configureDisplayInsets(display, invalid).getBoolean("ok")) {
                    throw new AssertionError("Invalid geometry accepted");
                }
                awaitReport(display, "second", 120, 48);
                requireConfigured(service.configureDisplayInsets(display, geometry(120, 48)));
                System.out.println("INVALID_GEOMETRY_REJECTED_PASS");

                SystemClock.sleep(300);
                JSONObject beforeIme = readReport(display);
                if (!service.tap(display, beforeIme.getInt("editorX"), beforeIme.getInt("editorY"))) {
                    throw new AssertionError("Editor tap failed");
                }
                SystemClock.sleep(300);
                service.tap(display, beforeIme.getInt("editorX"), beforeIme.getInt("editorY"));
                long deadline = SystemClock.uptimeMillis() + 6000;
                JSONObject ime = beforeIme;
                while (SystemClock.uptimeMillis() < deadline) {
                    ime = readReport(display);
                    if (ime.optInt("imeBottom") > 0) break;
                    SystemClock.sleep(100);
                }
                if (ime.getInt("navBottom") != 48 || ime.getInt("statusTop") != 120) {
                    throw new AssertionError("System bar geometry changed during IME request: " + ime);
                }
                if (ime.optInt("imeBottom") > 0) {
                    System.out.println("IME_SEPARATION_PASS " + ime);
                } else {
                    // Establish the same-fixture baseline before calling this an environment skip.
                    requireConfigured(service.configureDisplayInsets(display, geometry(0, 0)));
                    JSONObject baseline = awaitReport(display, "second", 0, 0);
                    SystemClock.sleep(300);
                    service.tap(display, baseline.getInt("editorX"), baseline.getInt("editorY"));
                    SystemClock.sleep(300);
                    service.tap(display, baseline.getInt("editorX"), baseline.getInt("editorY"));
                    SystemClock.sleep(3000);
                    baseline = readReport(display);
                    if (baseline.optInt("imeBottom") > 0) {
                        throw new AssertionError("IME worked without Insets but failed with them: " + baseline);
                    }
                    System.out.println("IME_UNVERIFIED: keyboard absent with and without supplied Insets " + baseline);
                    requireConfigured(service.configureDisplayInsets(display, geometry(120, 48)));
                    awaitReport(display, "second", 120, 48);
                }
                if (ime.optInt("imeBottom") > 0) service.key(display, 4);
                SystemClock.sleep(300);
                Files.write(Paths.get("/data/local/tmp/droidance-insets-accepted.png"), service.capturePng(display));
                System.out.println("SIDECAR_DISPLAY_INSETS_PASS " + service.getStatus());
                exit = 0;
            } catch (Throwable error) { error.printStackTrace(); }
            finally {
                try {
                    Files.write(Paths.get("/data/local/tmp/droidance-insets-probe-final.png"), service.capturePng(service.getDisplayId()));
                    Files.write(Paths.get("/data/local/tmp/droidance-insets-probe-ime.txt"), command("dumpsys", "input_method").getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
                service.shutdown(); System.exit(exit);
            }
        }, "display-insets-probe").start();
        Looper.loop();
    }

    private static Bundle geometry(int top, int bottom) {
        Bundle bundle = new Bundle();
        bundle.putInt("width", 1080); bundle.putInt("height", 2376); bundle.putInt("rotation", 0);
        bundle.putIntArray("status", new int[]{0, top, 0, 0});
        bundle.putIntArray("navigation", new int[]{0, 0, 0, bottom});
        bundle.putIntArray("cutout", new int[]{0, top, 0, 0});
        return bundle;
    }
    private static void requireConfigured(Bundle result) {
        if (!result.getBoolean("ok")) throw new AssertionError(result);
    }
    private static void launch(int display, String instance) throws Exception {
        String output = command("am", "start", "--display", Integer.toString(display), "-f", "0x18000000",
                "-n", PACKAGE + "/com.droidance.debug.DisplayInsetsProbeActivity", "--es", "instance", instance);
        if (output.contains("Error")) throw new AssertionError(output);
    }
    private static JSONObject awaitReport(int display, String instance, int top, int bottom) throws Exception {
        JSONObject report = null;
        long deadline = SystemClock.uptimeMillis() + 6500;
        do {
            try {
                report = readReport(display);
                if (report.optBoolean("focused") && report.optString("instance").equals(instance) && report.optInt("statusTop") == top
                        && report.optInt("navBottom") == bottom && report.optInt("buttonY") == top + 80) return report;
            } catch (Exception ignored) { }
            SystemClock.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected " + instance + " top=" + top + " bottom=" + bottom + ": " + report);
    }
    private static void awaitField(int display, String name, int value) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 3000;
        do {
            if (readReport(display).optInt(name) == value) return;
            SystemClock.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Missing " + name + "=" + value + ": " + readReport(display));
    }
    private static JSONObject readReport(int display) throws Exception {
        return new JSONObject(command("run-as", PACKAGE, "cat", "files/display-insets-" + display + ".json"));
    }
    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }
}
