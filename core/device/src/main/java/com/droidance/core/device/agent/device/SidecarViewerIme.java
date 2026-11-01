// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.content.Context;
import android.os.IBinder;
import android.view.WindowInsets;
import android.view.Display;
import java.lang.reflect.Method;

/** Owns the platform IME policy for one viewer lifetime; it is not an input method service. */
final class SidecarViewerIme implements AutoCloseable {
    static final int LOCAL = 0;
    static final int HIDE = 2;

    interface Platform {
        void setPolicy(int displayId, int policy) throws Exception;
        int bottomInset(int displayId) throws Exception;
        void focusDisplay(int displayId) throws Exception;
        void hideIme() throws Exception;
    }

    private final int displayId;
    private final Platform platform;
    private String owner;
    private IBinder lifetime;
    private IBinder.DeathRecipient deathRecipient;
    private boolean active;

    SidecarViewerIme(int displayId) throws Exception {
        this(displayId, new WindowPlatform());
    }

    SidecarViewerIme(int displayId, Platform platform) throws Exception {
        this.displayId = displayId;
        this.platform = platform;
        platform.setPolicy(displayId, HIDE);
    }

    synchronized void setEnabled(boolean enabled, String requestedOwner, IBinder requestedLifetime) throws Exception {
        if (requestedOwner == null || requestedOwner.isEmpty()) throw new IllegalArgumentException("Viewer owner missing");
        if (!enabled) {
            if (requestedOwner.equals(owner)) close();
            return;
        }
        if (requestedLifetime == null || !requestedLifetime.isBinderAlive()) {
            throw new IllegalArgumentException("Viewer lifetime unavailable");
        }
        if (active && requestedOwner.equals(owner) && requestedLifetime.equals(lifetime)) return;
        close();
        IBinder.DeathRecipient recipient = () -> releaseAfterDeath(requestedOwner);
        requestedLifetime.linkToDeath(recipient, 0);
        owner = requestedOwner;
        lifetime = requestedLifetime;
        deathRecipient = recipient;
        try {
            platform.setPolicy(displayId, LOCAL);
            platform.focusDisplay(displayId);
            active = true;
        } catch (Exception error) {
            try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    synchronized int bottomInset() throws Exception {
        return active ? platform.bottomInset(displayId) : 0;
    }

    synchronized void focusEditor() throws Exception {
        if (active) platform.focusDisplay(displayId);
    }

    private synchronized void releaseAfterDeath(String requestedOwner) {
        if (!requestedOwner.equals(owner)) return;
        try { close(); }
        catch (Exception error) { android.util.Log.w("ViewerIme", "Failed to hide disconnected viewer IME", error); }
    }

    @Override public synchronized void close() throws Exception {
        // Restore policy before relinquishing ownership, so a failed cleanup can be retried.
        platform.setPolicy(displayId, HIDE);
        active = false;
        if (owner != null) {
            platform.hideIme();
            platform.focusDisplay(Display.DEFAULT_DISPLAY);
        }
        if (lifetime != null && deathRecipient != null) {
            try { lifetime.unlinkToDeath(deathRecipient, 0); }
            catch (java.util.NoSuchElementException ignored) { /* Owner already died. */ }
        }
        owner = null;
        lifetime = null;
        deathRecipient = null;
    }

    private static final class WindowPlatform implements Platform {
        private final Object windows;
        private final Method setPolicy;
        private final boolean booleanPolicy;
        WindowPlatform() throws Exception {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, Context.WINDOW_SERVICE);
            if (binder == null) throw new IllegalStateException("Window service unavailable");
            Class<?> windowClass = Class.forName("android.view.IWindowManager");
            windows = Class.forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Method displayPolicy = null;
            try {
                displayPolicy = windowClass.getMethod("setDisplayImePolicy", int.class, int.class);
            } catch (NoSuchMethodException booleanOnly) {
                displayPolicy = windowClass.getMethod("setShouldShowIme", int.class, boolean.class);
            }
            setPolicy = displayPolicy;
            booleanPolicy = displayPolicy.getParameterTypes()[1] == boolean.class;
        }

        @Override public void setPolicy(int displayId, int policy) throws Exception {
            if (booleanPolicy) setPolicy.invoke(windows, displayId, policy == LOCAL);
            else setPolicy.invoke(windows, displayId, policy);
        }

        @Override public void hideIme() throws Exception {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, Context.INPUT_METHOD_SERVICE);
            Object input = Class.forName("com.android.internal.view.IInputMethodManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            // HIDE updates the display policy cache. Explicitly close the existing IME
            // window as well; otherwise its old client may stay shown after viewer exit.
            Class<?> inputClass = Class.forName("com.android.internal.view.IInputMethodManager");
            try {
                inputClass.getMethod("hideSoftInputFromServerForTest").invoke(input);
            } catch (NoSuchMethodException olderPlatform) {
                // A missing cleanup call must not fail the policy change and drop the viewer mirror.
                try { inputClass.getMethod("removeImeSurface").invoke(input); }
                catch (ReflectiveOperationException unavailable) {
                    android.util.Log.w("ViewerIme", "IME window cleanup unavailable", unavailable);
                }
            }
        }

        @Override public void focusDisplay(int displayId) throws Exception {
            Class<?> taskManagerClass = Class.forName("android.app.IActivityTaskManager");
            Object tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            java.util.List<?> roots = SidecarDisplayContent.rootTasks(displayId);
            // WM returns roots in z-order. Focus the current visible task without relaunching it.
            for (Object root : roots) {
                if (!root.getClass().getField("visible").getBoolean(root)) continue;
                int taskId = SidecarDisplayContent.topTaskId(root);
                taskManagerClass.getMethod("setFocusedTask", int.class).invoke(tasks, taskId);
                return;
            }
            throw new IllegalStateException("No visible task on viewer focus display " + displayId);
        }

        @Override public int bottomInset(int displayId) throws Exception {
            Class<?> stateClass = Class.forName("android.view.InsetsState");
            Object state = stateClass.getConstructor().newInstance();
            Class.forName("android.view.IWindowManager")
                    .getMethod("getWindowInsets", int.class, IBinder.class, stateClass)
                    .invoke(windows, displayId, null, state);
            android.graphics.Rect bounds = (android.graphics.Rect) stateClass.getMethod("getDisplayFrame").invoke(state);
            android.graphics.Insets insets = (android.graphics.Insets) stateClass
                    .getMethod("calculateInsets", android.graphics.Rect.class, int.class, boolean.class)
                    .invoke(state, bounds, WindowInsets.Type.ime(), false);
            return insets.bottom;
        }
    }
}
