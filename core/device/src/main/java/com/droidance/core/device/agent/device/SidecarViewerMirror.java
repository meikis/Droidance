// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import java.util.concurrent.Callable;

/** A compositor mirror. The VirtualDisplay's producer surface and frame reader never change. */
final class SidecarViewerMirror implements AutoCloseable {
    private final Callable<AutoCloseable> acquireWakeLease;
    private AutoCloseable wakeLease;
    private SurfaceControl mirror;
    private String owner;
    private IBinder lifetime;
    private IBinder.DeathRecipient deathRecipient;
    private volatile boolean attached;

    SidecarViewerMirror(Callable<AutoCloseable> acquireWakeLease) {
        this.acquireWakeLease = acquireWakeLease;
    }

    synchronized void attach(int displayId, SurfaceControl parent, int displayWidth, int displayHeight,
            int viewWidth, int viewHeight, String requestedOwner, IBinder requestedLifetime) throws Exception {
        if (parent == null || !parent.isValid() || viewWidth <= 0 || viewHeight <= 0
                || requestedOwner == null || requestedOwner.isEmpty() || requestedLifetime == null
                || !requestedLifetime.isBinderAlive()) {
            throw new IllegalArgumentException("Invalid viewer mirror target");
        }
        if (mirror == null || !mirror.isValid() || !requestedOwner.equals(owner)) {
            close();
            Class<?> managerClass = Class.forName("android.view.IWindowManager");
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "window");
            Object manager = Class.forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            SurfaceControl candidate = SurfaceControl.class.getDeclaredConstructor().newInstance();
            try {
                // Acquire before publishing the mirror; a timed execution lock may already have expired.
                wakeLease = acquireWakeLease.call();
                boolean created = (boolean) managerClass.getMethod("mirrorDisplay", int.class,
                        SurfaceControl.class).invoke(manager, displayId, candidate);
                if (!created || !candidate.isValid()) throw new IllegalStateException("Display mirror unavailable");
                mirror = candidate;
                owner = requestedOwner;
                lifetime = requestedLifetime;
                deathRecipient = () -> detach(requestedOwner);
                lifetime.linkToDeath(deathRecipient, 0);
            } catch (Exception error) {
                close();
                throw error;
            } finally {
                if (mirror != candidate) candidate.release();
            }
        }
        float scale = Math.min(viewWidth / (float) displayWidth, viewHeight / (float) displayHeight);
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.reparent(mirror, parent)
                    .setLayer(mirror, 1)
                    .setCrop(mirror, new Rect(0, 0, displayWidth, displayHeight))
                    .setScale(mirror, scale, scale)
                    .setPosition(mirror, (viewWidth - displayWidth * scale) / 2f,
                            (viewHeight - displayHeight * scale) / 2f)
                    .setVisibility(mirror, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                transaction.setFrameRate(mirror, 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            }
            transaction.apply();
            attached = true;
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    synchronized boolean detach(String requestedOwner) {
        // A late callback from an old Activity cannot remove the replacement viewer's mirror.
        boolean matched = requestedOwner != null && requestedOwner.equals(owner);
        if (matched) close();
        return matched;
    }

    boolean isAttached() { return attached; }

    synchronized boolean isOwnedBy(String requestedOwner) {
        return attached && requestedOwner != null && requestedOwner.equals(owner);
    }

    @Override public synchronized void close() {
        attached = false;
        if (wakeLease != null) {
            try { wakeLease.close(); }
            catch (Exception error) { Log.w("ViewerMirror", "Failed to release viewer wake lock", error); }
            wakeLease = null;
        }
        if (lifetime != null && deathRecipient != null) {
            try { lifetime.unlinkToDeath(deathRecipient, 0); }
            catch (java.util.NoSuchElementException ignored) { /* Registration failed or owner died. */ }
            lifetime = null;
            deathRecipient = null;
        }
        if (mirror != null) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.reparent(mirror, null).setVisibility(mirror, false).apply();
            } finally {
                mirror.release();
                mirror = null;
                owner = null;
            }
        }
    }
}
