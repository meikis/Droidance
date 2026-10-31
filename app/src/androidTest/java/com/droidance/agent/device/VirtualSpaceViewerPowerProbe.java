// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device;

import com.droidance.core.agent.device.*;
import com.droidance.core.knowledge.agent.device.*;
import com.droidance.core.device.agent.device.*;

import com.droidance.agent.device.*;

import android.content.Context;
import android.graphics.Color;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Shell-side regression: recover an asleep retained display without relaunching its activity. */
public final class VirtualSpaceViewerPowerProbe {
    private VirtualSpaceViewerPowerProbe() {}

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        Context context = SidecarShellContext.create();
        VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(context);
        new Thread(() -> {
            SurfaceControl parent = null;
            int result = 1;
            try {
                require(service.start(1080, 2376, 480), service.getLastError());
                int displayId = service.getDisplayId();
                Bundle launch = service.execShellCommand("am start --display " + displayId
                        + " -f 0x18000000 -n com.droidance.debug/com.droidance.debug.TakeoverNavigationProbeActivity",
                        8000, java.util.UUID.randomUUID().toString());
                require(launch != null && launch.getInt("exit_code", -1) == 0,
                        "Probe activity launch failed: " + launch);
                awaitBlueFrame(service, displayId);
                String originalTasks = taskIdentity(displayId);
                Object power = context.getSystemService(Context.POWER_SERVICE);
                Method interactive = power.getClass().getMethod("isInteractive", int.class);
                Method sleep = power.getClass().getMethod("goToSleep", int.class, long.class, int.class, int.class);
                releaseExecutionLock(service);
                sleep.invoke(power, displayId, SystemClock.uptimeMillis(), 0, 0);
                awaitInteractive(power, interactive, displayId, false);
                // The production reuse window must have elapsed before reopening the viewer.
                SystemClock.sleep(900);
                System.out.println("VIEWER_POWER_ASLEEP display=" + displayId);

                parent = new SurfaceControl.Builder().setName("DroidanceViewerPowerProbe")
                        .setBufferSize(1080, 2376).build();
                require(service.attachViewerMirror(displayId, copy(parent), 1080, 2376, "old", new Binder()),
                        service.getLastError());
                awaitInteractive(power, interactive, displayId, true);
                awaitBlueFrame(service, displayId);
                require(originalTasks.equals(taskIdentity(displayId)), "Reopening changed the retained activity");

                // Remove the execution lock again: only the visible mirror's lease may keep this display awake.
                releaseExecutionLock(service);
                require(viewerWakeHeld(service), "Visible viewer has no display wake lock");
                require(service.attachViewerMirror(displayId, copy(parent), 1080, 2376, "replacement", new Binder()),
                        service.getLastError());
                service.detachViewerMirror(displayId, "old");
                require(viewerWakeHeld(service), "Stale viewer cleanup released its replacement's wake lock");
                service.detachViewerMirror(displayId, "replacement");
                require(!viewerWakeHeld(service), "Collapsing the viewer leaked its wake lock");

                require(service.attachViewerMirror(displayId, copy(parent), 1080, 2376, "death", new Binder()),
                        service.getLastError());
                Object mirror = field(service, "viewerMirror");
                ((IBinder.DeathRecipient) field(mirror, "deathRecipient")).binderDied();
                require(!viewerWakeHeld(service), "Viewer Binder death leaked its wake lock");

                require(service.attachViewerMirror(displayId, copy(parent), 1080, 2376, "shutdown", new Binder()),
                        service.getLastError());
                service.shutdown();
                require(!viewerWakeHeld(service), "Display shutdown leaked the viewer wake lock");
                System.out.println("VIEWER_POWER_PASS display=" + displayId
                        + " retained_activity=true restored_pixels=true visible_lease=true"
                        + " stale_owner_safe=true collapse_released=true death_released=true shutdown_released=true");
                result = 0;
            } catch (Throwable error) {
                error.printStackTrace();
            } finally {
                service.shutdown();
                if (parent != null) parent.release();
                System.exit(result);
            }
        }, "viewer-power-probe").start();
        Looper.loop();
    }

    private static SurfaceControl copy(SurfaceControl surface) throws Exception {
        return SurfaceControl.class.getConstructor(SurfaceControl.class, String.class)
                .newInstance(surface, "viewer-power-parent");
    }

    private static void releaseExecutionLock(VirtualSpaceSidecarService service) throws Exception {
        Method release = VirtualSpaceSidecarService.class.getDeclaredMethod("releaseDisplayWakeLock");
        release.setAccessible(true);
        release.invoke(service);
    }

    private static boolean viewerWakeHeld(VirtualSpaceSidecarService service) throws Exception {
        Object mirror = field(service, "viewerMirror");
        boolean leased = field(mirror, "wakeLease") != null;
        Bundle result = service.execShellCommand("dumpsys power", 8000, java.util.UUID.randomUUID().toString());
        String locks = result.getString("stdout", "").split("Suspend Blockers:", 2)[0];
        boolean held = locks.contains("Droidance:VirtualSpaceViewer");
        require(leased == held, "Viewer lease and system wake lock disagree: leased=" + leased + " held=" + held);
        return leased;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String taskIdentity(int displayId) throws Exception {
        Object manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Iterable<?> tasks = (Iterable<?>) Class.forName("android.app.IActivityTaskManager")
                .getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(manager, displayId);
        StringBuilder identity = new StringBuilder();
        for (Object task : tasks) {
            identity.append(task.getClass().getField("taskId").get(task)).append(':')
                    .append(task.getClass().getField("topActivity").get(task)).append(';');
        }
        return identity.toString();
    }

    private static void awaitInteractive(Object power, Method method, int displayId, boolean expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while ((boolean) method.invoke(power, displayId) != expected) {
            require(SystemClock.uptimeMillis() < deadline, "Display interactive state did not become " + expected);
            SystemClock.sleep(50);
        }
    }

    private static void awaitBlueFrame(VirtualSpaceSidecarService service, int displayId) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (true) {
            Bundle frame = service.captureFreshSample(displayId, new int[]{100, 1000});
            int[] colors = frame == null ? null : frame.getIntArray("colors");
            if (colors != null && colors.length == 1 && colors[0] == Color.rgb(25, 70, 180)) return;
            require(SystemClock.uptimeMillis() < deadline, "Retained source pixels did not return");
            SystemClock.sleep(50);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
