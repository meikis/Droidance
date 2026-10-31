// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.WindowInsets;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Supplies host system-bar safe areas to all root tasks on our display. No surfaces, screenshots,
 * app names or global TaskOrganizer ownership are involved. The owner Binder also makes WM remove
 * our sources if this process dies. All framework transactions run on one dedicated thread.
 */
final class SidecarDisplayInsets implements AutoCloseable {
    private static final String TAG = "DroidanceInsets";
    private static final String LISTENER_DESCRIPTOR = "android.app.ITaskStackListener";
    private static final int[] TYPES = {WindowInsets.Type.statusBars(),
            WindowInsets.Type.navigationBars(), WindowInsets.Type.displayCutout()};
    private final Context context;
    private final int displayId;
    private final Binder owner = new Binder();
    private final HandlerThread thread = new HandlerThread("DisplayInsets");
    private final Handler handler;
    private final Map<IBinder, AppliedTask> applied = new HashMap<>();
    private final Runnable reconcile = this::reconcileSafely;
    private volatile boolean closed;
    private volatile String evidence = "not_configured";
    private Bundle geometry;
    private Object activityManager, organizer, listener;
    private Class<?> transactionClass, taskManagerClass, listenerClass;
    private Method getTasks, addSource, removeSource, applyTransaction;

    SidecarDisplayInsets(Context context, int displayId) {
        if (displayId <= Display.DEFAULT_DISPLAY) throw new IllegalArgumentException("Virtual display required");
        this.context = context;
        this.displayId = displayId;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    Bundle configure(Bundle request) {
        Bundle copy = new Bundle(request);
        FutureTask<Bundle> call = new FutureTask<>(() -> {
            if (closed) throw new IllegalStateException("Insets controller closed");
            // Validate every type before making any WM change.
            for (String key : new String[]{"status", "navigation", "cutout"}) {
                DisplayInsetsGeometry.map(copy.getIntArray(key), copy.getInt("width"),
                        copy.getInt("height"), copy.getInt("width"), copy.getInt("height"));
            }
            initialize();
            geometry = copy;
            reconcileNow();
            schedule(1000);
            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            result.putString("detail", evidence);
            return result;
        });
        handler.post(call);
        try {
            return call.get(2000, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            call.cancel(false);
            evidence = "unavailable: " + describe(error);
            Log.w(TAG, evidence);
            Bundle result = new Bundle();
            result.putBoolean("ok", false);
            result.putString("detail", evidence);
            return result;
        }
    }

    String evidence() { return evidence; }

    private void initialize() throws Exception {
        if (listener != null) return;
        taskManagerClass = Class.forName("android.app.IActivityTaskManager");
        activityManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        getTasks = taskManagerClass.getMethod("getAllRootTaskInfosOnDisplay", int.class);
        transactionClass = Class.forName("android.window.WindowContainerTransaction");
        Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        // Resolve the platform's actual signature. Unsupported APIs are reported, never replaced
        // with image padding or guessed Binder transaction numbers.
        for (Method method : transactionClass.getMethods()) {
            if (method.getName().equals("addInsetsSource")) addSource = method;
        }
        if (addSource == null || (addSource.getParameterCount() != 6 && addSource.getParameterCount() != 7)) {
            throw new NoSuchMethodException("WindowContainerTransaction.addInsetsSource");
        }
        removeSource = transactionClass.getMethod("removeInsetsSource", tokenClass, IBinder.class, int.class, int.class);
        Class<?> organizerClass = Class.forName("android.window.WindowOrganizer");
        organizer = organizerClass.getConstructor().newInstance();
        applyTransaction = organizerClass.getMethod("applyTransaction", transactionClass);
        listenerClass = Class.forName(LISTENER_DESCRIPTOR);
        Binder callback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(LISTENER_DESCRIPTOR);
                    return true;
                }
                if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                    data.enforceInterface(LISTENER_DESCRIPTOR);
                    // Every task-stack notification is only an invalidation; query authoritative
                    // task tokens on our display, not the callback's version-dependent payload.
                    schedule(32);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        Object candidate = Class.forName(LISTENER_DESCRIPTOR + "$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, callback);
        taskManagerClass.getMethod("registerTaskStackListener", listenerClass).invoke(activityManager, candidate);
        listener = candidate;
    }

    private void schedule(long delayMs) {
        if (closed) return;
        handler.removeCallbacks(reconcile);
        handler.postDelayed(reconcile, delayMs);
    }

    private void reconcileSafely() {
        if (closed || geometry == null) return;
        try { reconcileNow(); }
        catch (Exception error) {
            String next = "unavailable: " + describe(error);
            if (!next.equals(evidence)) Log.w(TAG, next);
            evidence = next;
        } finally { schedule(1000); }
    }

    private void reconcileNow() throws Exception {
        if (closed) return;
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) throw new IllegalStateException("Virtual display removed");
        Point size = new Point();
        display.getRealSize(size);
        int[][] sides = new int[3][];
        String[] keys = {"status", "navigation", "cutout"};
        for (int i = 0; i < sides.length; i++) {
            sides[i] = DisplayInsetsGeometry.map(geometry.getIntArray(keys[i]), geometry.getInt("width"),
                    geometry.getInt("height"), size.x, size.y);
        }
        String signature = size.x + "x" + size.y + ":" + Arrays.deepToString(sides);
        List<?> tasks = (List<?>) getTasks.invoke(activityManager, displayId);
        Map<IBinder, AppliedTask> next = new HashMap<>();
        Object transaction = transactionClass.getConstructor().newInstance();
        boolean changed = false;
        for (Object task : tasks) {
            Object token = task.getClass().getField("token").get(task);
            IBinder binder = (IBinder) token.getClass().getMethod("asBinder").invoke(token);
            AppliedTask prior = applied.get(binder);
            if (prior == null || !signature.equals(prior.signature)) {
                for (int type = 0; type < TYPES.length; type++) {
                    for (int side = 0; side < 4; side++) {
                        // Indices stay stable across updates and rotations, including zero edges.
                        int index = type * 4 + side;
                        Rect frame = edgeFrame(side, sides[type][side], size.x, size.y);
                        if (addSource.getParameterCount() == 7) {
                            addSource.invoke(transaction, token, owner, index, TYPES[type], frame, null, 0);
                        } else {
                            addSource.invoke(transaction, token, owner, index, TYPES[type], frame, 0);
                        }
                    }
                }
                changed = true;
            }
            next.put(binder, new AppliedTask(token, signature));
        }
        for (Map.Entry<IBinder, AppliedTask> entry : applied.entrySet()) {
            // Tasks moved off our display must not carry our insets onto the main display.
            if (!next.containsKey(entry.getKey())) {
                removeAll(transaction, entry.getValue().token);
                changed = true;
            }
        }
        if (changed && !closed) applyTransaction.invoke(organizer, transaction);
        applied.clear();
        applied.putAll(next);
        evidence = "configured tasks=" + applied.size() + " geometry=" + signature;
    }

    private static Rect edgeFrame(int side, int amount, int width, int height) {
        if (amount == 0) return new Rect();
        switch (side) {
            case 0: return new Rect(0, 0, amount, height);
            case 1: return new Rect(0, 0, width, amount);
            case 2: return new Rect(width - amount, 0, width, height);
            default: return new Rect(0, height - amount, width, height);
        }
    }

    private void removeAll(Object transaction, Object token) throws Exception {
        for (int type = 0; type < TYPES.length; type++) {
            for (int side = 0; side < 4; side++) {
                removeSource.invoke(transaction, token, owner, type * 4 + side, TYPES[type]);
            }
        }
    }

    @Override public void close() {
        closed = true;
        handler.removeCallbacks(reconcile);
        handler.post(() -> {
            try {
                if (listener != null) taskManagerClass.getMethod("unregisterTaskStackListener", listenerClass)
                        .invoke(activityManager, listener);
                if (transactionClass != null && organizer != null && removeSource != null) {
                    Object transaction = transactionClass.getConstructor().newInstance();
                    for (AppliedTask task : applied.values()) removeAll(transaction, task.token);
                    applyTransaction.invoke(organizer, transaction);
                }
            } catch (Exception error) { Log.w(TAG, "Insets cleanup: " + describe(error)); }
            finally { applied.clear(); thread.quitSafely(); }
        });
    }

    private static String describe(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private static final class AppliedTask {
        final Object token;
        final String signature;
        AppliedTask(Object token, String signature) { this.token = token; this.signature = signature; }
    }
}
