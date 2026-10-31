// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device;

import com.droidance.core.agent.device.*;
import com.droidance.core.knowledge.agent.device.*;
import com.droidance.core.device.agent.device.*;

import com.droidance.agent.device.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.util.List;

/** Shell app_process probe. Owns a separate, unregistered sidecar and only its test display/tasks. */
public final class VirtualDisplayContentProbe {
    private static final String ACTIVITY = "com.droidance.debug.ObservationProbeActivity";

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        Context context = SidecarShellContext.create();
        String packageName = args.length == 0 ? "com.droidance.debug" : args[0];
        VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(context);
        Thread worker = new Thread(() -> {
            int exitCode = 1;
            int ownedDisplay = -1;
            try {
                ownedDisplay = start(service);
                expect(service, ownedDisplay, "initial", VirtualDisplayContentState.EMPTY);

                String component = new ComponentName(packageName, ACTIVITY).flattenToString();
                int launchFlags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
                android.os.Bundle launch = service.execShellCommand(
                        "am start --display " + ownedDisplay + " -f " + launchFlags + " -n " + shellQuote(component),
                        15000, java.util.UUID.randomUUID().toString());
                if (launch == null || launch.getInt("exit_code", -1) != 0) {
                    throw new AssertionError("Probe activity launch failed: " + launch);
                }
                awaitContent(service, ownedDisplay);
                expect(service, ownedDisplay, "launched", VirtualDisplayContentState.CONTENT_PRESENT);

                removeProbeTasks(ownedDisplay, packageName);
                if (!service.shutdown()) throw new AssertionError("shutdown failed");
                expect(service, ownedDisplay, "shutdown", VirtualDisplayContentState.UNAVAILABLE);

                int previousDisplay = ownedDisplay;
                ownedDisplay = start(service);
                expect(service, ownedDisplay, "restarted", VirtualDisplayContentState.EMPTY);
                expect(service, previousDisplay, "stale_display", VirtualDisplayContentState.UNAVAILABLE);

                // Simulate framework removal while the service still holds its VirtualDisplay object.
                Field displayField = VirtualSpaceSidecarService.class.getDeclaredField("display");
                displayField.setAccessible(true);
                ((VirtualDisplay) displayField.get(service)).release();
                awaitInvalidDisplay(service);
                expect(service, ownedDisplay, "externally_removed", VirtualDisplayContentState.UNAVAILABLE);
                ownedDisplay = start(service);
                expect(service, ownedDisplay, "recreated_invalid_display", VirtualDisplayContentState.EMPTY);
                exitCode = 0;
            } catch (Throwable error) {
                error.printStackTrace();
            } finally {
                try {
                    if (service.getDisplayId() > 0) removeProbeTasks(service.getDisplayId(), packageName);
                } catch (Throwable error) {
                    error.printStackTrace();
                    exitCode = 1;
                }
                service.shutdown();
                System.out.println(exitCode == 0 ? "VIRTUAL_DISPLAY_CONTENT_PROBE_PASS" : "VIRTUAL_DISPLAY_CONTENT_PROBE_FAILED");
                System.exit(exitCode);
            }
        }, "virtual-display-content-probe");
        worker.start();
        Looper.loop();
    }

    private static int start(VirtualSpaceSidecarService service) {
        if (!service.start(720, 1280, 320)) throw new AssertionError(service.getLastError());
        int displayId = service.getDisplayId();
        if (displayId <= 0) throw new AssertionError("Valid isolated virtual display required");
        return displayId;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static VirtualDisplayContentState state(Bundle result, int displayId) {
        return VirtualDisplayContentState.Companion.fromFacts(
                displayId, result.getInt("displayId", -1), result.getBoolean("displayValid", false),
                result.getBoolean("querySucceeded", false),
                result.containsKey("activityCount") ? result.getInt("activityCount") : null);
    }

    private static void expect(VirtualSpaceSidecarService service, int displayId, String phase,
                               VirtualDisplayContentState expected) {
        Bundle result = service.inspectDisplayContent(displayId);
        VirtualDisplayContentState actual = state(result, displayId);
        if (actual != expected) throw new AssertionError(phase + " expected=" + expected + " actual=" + actual + " facts=" + result);
        System.out.println("PASS phase=" + phase + " state=" + actual + " requestedDisplayId=" + displayId +
                " displayId=" + result.getInt("displayId", -1) + " activityCount=" + result.getInt("activityCount", -1) +
                " querySucceeded=" + result.getBoolean("querySucceeded", false));
    }

    private static void awaitContent(VirtualSpaceSidecarService service, int displayId) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            if (state(service.inspectDisplayContent(displayId), displayId) == VirtualDisplayContentState.CONTENT_PRESENT) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Probe activity did not populate its display");
    }

    private static void awaitInvalidDisplay(VirtualSpaceSidecarService service) {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (service.getDisplayId() >= 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
        if (service.getDisplayId() >= 0) throw new AssertionError("Released display remains valid");
    }

    private static void removeProbeTasks(int displayId, String packageName) throws Exception {
        if (displayId <= 0) throw new IllegalArgumentException("Probe cleanup cannot target the main display");
        Class<?> managerType = Class.forName("android.app.IActivityTaskManager");
        Object manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Object result = managerType.getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(manager, displayId);
        if (!(result instanceof List<?>)) throw new IllegalStateException("Probe cleanup inventory unavailable");
        for (Object task : (List<?>) result) {
            if (task.getClass().getField("displayId").getInt(task) != displayId) throw new IllegalStateException("Probe cleanup display mismatch");
            int count = task.getClass().getField("numActivities").getInt(task);
            if (count == 0) continue;
            ComponentName top = (ComponentName) task.getClass().getField("topActivity").get(task);
            if (top == null || !packageName.equals(top.getPackageName()) || !ACTIVITY.equals(top.getClassName())) {
                throw new IllegalStateException("Unexpected activity on isolated probe display: " + top);
            }
            int taskId = task.getClass().getField("taskId").getInt(task);
            if (!Boolean.TRUE.equals(managerType.getMethod("removeTask", int.class).invoke(manager, taskId))) {
                throw new IllegalStateException("Probe task removal failed: " + taskId);
            }
        }
    }
}
