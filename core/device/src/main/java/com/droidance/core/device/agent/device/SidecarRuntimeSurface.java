// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Paired shell system surfaces: the app renders the island bitmap, and the sidecar draws the shared
 * edge light and hand. Only the island's fixed-size input child receives touch, preventing a
 * decorative full-screen surface from becoming an input target. No app overlay window is used.
 */
final class SidecarRuntimeSurface {
    static final int ACTION_NONE = 0;
    static final int ACTION_TAP = 1;
    static final int ACTION_SWIPE = 2;

    private static final String TAG = "DroidanceRuntimeSurface";
    private static final long MAIN_THREAD_TIMEOUT_MS = 3_000L;
    private static final int INDICATOR_LAYER = Integer.MAX_VALUE;
    private static final int CURSOR_LAYER = Integer.MAX_VALUE - 1;
    private static final int EDGE_LAYER = Integer.MAX_VALUE - 2;
    private static final Method DISPLAY_LAYER_STACK_METHOD = hiddenMethod(
            Display.class,
            "getLayerStack"
    );
    private static final Method SET_LAYER_STACK_METHOD = hiddenMethod(
            SurfaceControl.Transaction.class,
            "setLayerStack",
            SurfaceControl.class,
            int.class
    );

    private final DisplayManager displayManager;
    private final Display indicatorDisplay;
    private final int indicatorDisplayLayerStack;
    private final Handler mainHandler;
    private final float density;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private SurfaceLayer indicatorLayer;
    private SurfaceLayer cursorLayer;
    private SurfaceLayer edgeLayer;
    private AuroraEdgePainter edgePainter;
    private boolean edgeVisible;
    private boolean edgeMotionEnabled;
    private float edgeDensity;
    private float[] edgeCornerRadii;
    private long edgeStartedAtMs;
    private final Runnable edgeFrame = this::animateEdge;
    private Bitmap islandBitmap;
    private Bitmap pointerBitmap;
    private int cursorAnimationToken;
    private Display cursorDisplay;
    private int cursorDisplayLayerStack;
    private int inputDispatchDepth;

    private boolean suppressed;
    private boolean indicatorVisible;
    private int indicatorTopOffsetPx;
    private boolean cursorVisible;
    private int actionType = ACTION_NONE;
    private int startX;
    private int startY;
    private int endX;
    private int endY;
    private int durationMs;
    private long startedAtMs;
    private long finishedAtMs;
    private final RobotHandPainter handPainter = new RobotHandPainter();
    private boolean motionEnabled;

    private boolean hasCursor;
    private float currentX;
    private float currentY;
    private boolean hasPosition;
    private float fromX;
    private float fromY;
    private long travelDurationMs;

    SidecarRuntimeSurface(Context context, Runnable onIndicatorTap, Runnable onStop) {
        this.displayManager = context.getSystemService(DisplayManager.class);
        if (displayManager == null) throw new IllegalStateException("DisplayManager unavailable");
        this.indicatorDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (indicatorDisplay == null) throw new IllegalStateException("Default display unavailable");
        this.indicatorDisplayLayerStack = resolveDisplayLayerStack(indicatorDisplay);
        this.mainHandler = new Handler(Looper.getMainLooper());
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        this.density = metrics.density;
        Log.i(TAG, "created uid=" + android.os.Process.myUid() +
                " display=" + indicatorDisplay.getDisplayId() +
                " layerStack=" + indicatorDisplayLayerStack);
    }

    /** 冻结的旧事务只处理隐藏；协议 15 起显示必须携带 App 渲染的位图。 */
    synchronized boolean updateIndicator(boolean visible, String label, String actionLabel, boolean paused) {
        return updateIsland(visible, label, "", actionLabel, "", paused);
    }

    /** 冻结的旧事务只处理隐藏；协议 15 起显示必须携带 App 渲染的位图。 */
    synchronized boolean updateIsland(
            boolean visible,
            String progressText,
            String currentActionText,
            String takeoverLabel,
            String stopLabel,
            boolean paused
    ) {
        if (visible) return false;
        indicatorVisible = false;
        return runOnMainSync(() -> {
            removeIndicator();
            return true;
        });
    }

    synchronized boolean updateIslandBitmap(boolean visible, byte[] png, int topOffsetPx) {
        Bitmap decoded = null;
        if (visible) decoded = decodeBitmap(png, "runtime island");
        Bitmap previous = islandBitmap;
        if (decoded != null) islandBitmap = decoded;
        indicatorVisible = visible;
        boolean applied = runOnMainSync(() -> {
            if (indicatorLayer == null || islandBitmap == null ||
                    indicatorLayer.width != islandBitmap.getWidth() || indicatorLayer.height != islandBitmap.getHeight()) {
                indicatorTopOffsetPx = Math.max(0, topOffsetPx);
            }
            return applyIndicator();
        });
        if (previous != null && previous != islandBitmap) previous.recycle();
        return applied;
    }

    /** 冻结的旧事务只处理隐藏；协议 15 起显示必须携带指针位图。 */
    synchronized boolean updateCursor(
            boolean visible,
            int actionType,
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMs
    ) {
        if (visible) return false;
        cursorVisible = false;
        return runOnMainSync(() -> {
            removeCursor();
            return true;
        });
    }

    synchronized boolean updateCursorBitmap(
            int targetDisplayId,
            boolean visible,
            int actionType,
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMs,
            long startedAtMs,
            long finishedAtMs,
            boolean motionEnabled,
            byte[] png
    ) {
        if (!visible) {
            cursorVisible = false;
            return runOnMainSync(() -> {
                removeCursor();
                return true;
            });
        }
        if (visible && pointerBitmap == null) pointerBitmap = decodeBitmap(png, "runtime pointer");
        Display targetDisplay = displayManager.getDisplay(targetDisplayId);
        if (targetDisplay == null) {
            throw new IllegalStateException("cursor target display unavailable: " + targetDisplayId);
        }
        int targetLayerStack = resolveDisplayLayerStack(targetDisplay);
        float targetX = startX;
        float targetY = startY;
        if (!hasPosition) {
            fromX = targetX;
            fromY = targetY;
            currentX = targetX;
            currentY = targetY;
            hasPosition = true;
            travelDurationMs = 0L;
        } else {
            fromX = currentX;
            fromY = currentY;
            float dist = (float) Math.hypot(targetX - fromX, targetY - fromY);
            travelDurationMs = (dist > 15f && motionEnabled) ? CursorMotion.calculateTravelDuration(dist) : 0L;
        }
        this.cursorVisible = visible;
        this.actionType = actionType;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.durationMs = durationMs;
        this.motionEnabled = motionEnabled;
        this.startedAtMs = startedAtMs;
        this.finishedAtMs = finishedAtMs;
        return runOnMainSync(() -> {
            if (cursorDisplay == null || cursorDisplay.getDisplayId() != targetDisplayId) {
                removeCursor();
                cursorDisplay = targetDisplay;
                cursorDisplayLayerStack = targetLayerStack;
                Log.i(TAG, "cursor target display=" + targetDisplayId +
                        " layerStack=" + cursorDisplayLayerStack);
            }
            return applyCursor();
        });
    }

    synchronized boolean setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        return runOnMainSync(() -> {
            if (suppressed) {
                hideIndicator();
                hideCursor();
                hideEdge();
                return true;
            }
            boolean indicatorApplied = applyIndicator();
            boolean cursorApplied = applyCursor();
            boolean edgeApplied = applyEdge();
            return indicatorApplied && cursorApplied && edgeApplied;
        });
    }

    synchronized boolean updateEdgeLight(boolean visible, float density, float[] cornerRadii, boolean motionEnabled) {
        if (visible && (!Float.isFinite(density) || density <= 0f || cornerRadii == null || cornerRadii.length != 8)) return false;
        if (visible) {
            for (float radius : cornerRadii) if (!Float.isFinite(radius) || radius < 0f) return false;
        }
        boolean geometryChanged = visible && (edgeDensity != density || !Arrays.equals(edgeCornerRadii, cornerRadii));
        boolean wasVisible = edgeVisible;
        edgeVisible = visible;
        edgeMotionEnabled = motionEnabled;
        if (visible) {
            edgeDensity = density;
            edgeCornerRadii = cornerRadii.clone();
            if (!wasVisible) edgeStartedAtMs = SystemClock.uptimeMillis();
        }
        return runOnMainSync(() -> {
            if (geometryChanged) removeEdge();
            return applyEdge();
        });
    }

    private boolean applyEdge() {
        mainHandler.removeCallbacks(edgeFrame);
        if (!edgeVisible) {
            removeEdge();
            return true;
        }
        if (suppressed) {
            hideEdge();
            return true;
        }
        DisplayMetrics metrics = screenMetrics(indicatorDisplay);
        if (edgeLayer == null || edgeLayer.width != metrics.widthPixels || edgeLayer.height != metrics.heightPixels) {
            removeEdge();
            // Decorative only: never create or register an input surface for the edge light.
            edgeLayer = new SurfaceLayer("Droidance Runtime Edge Light", metrics.widthPixels,
                    metrics.heightPixels, indicatorDisplayLayerStack, false);
            edgePainter = new AuroraEdgePainter(edgeDensity);
            edgePainter.resize(metrics.widthPixels, metrics.heightPixels, edgeCornerRadii);
        }
        drawEdgeFrame();
        edgeLayer.show(0, 0, EDGE_LAYER);
        if (edgeMotionEnabled) mainHandler.postDelayed(edgeFrame, AuroraEdgePainter.FRAME_INTERVAL_MS);
        return true;
    }

    private void animateEdge() {
        if (!edgeVisible || suppressed || edgeLayer == null) return;
        try {
            applyEdge();
        } catch (Throwable error) {
            Log.e(TAG, "edge light animation failed", error);
            removeEdge();
        }
    }

    private void drawEdgeFrame() {
        Canvas canvas = null;
        try {
            canvas = edgeLayer.surface.lockCanvas(null);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            long elapsed = Math.max(0L, SystemClock.uptimeMillis() - edgeStartedAtMs);
            float phase = edgeMotionEnabled ? (elapsed % AuroraEdgePainter.CYCLE_MS) / (float) AuroraEdgePainter.CYCLE_MS : 0f;
            float opacity = edgeMotionEnabled ? Math.min(1f, elapsed / (float) AuroraEdgePainter.FADE_IN_MS) : 1f;
            edgePainter.draw(canvas, phase, opacity);
        } finally {
            if (canvas != null) edgeLayer.surface.unlockCanvasAndPost(canvas);
        }
    }

    private void hideEdge() {
        mainHandler.removeCallbacks(edgeFrame);
        if (edgeLayer != null) edgeLayer.hide();
    }

    private void removeEdge() {
        mainHandler.removeCallbacks(edgeFrame);
        if (edgeLayer != null) {
            edgeLayer.release();
            edgeLayer = null;
            edgePainter = null;
        }
    }

    synchronized void beginInputDispatch() {
        // A trusted, non-input pointer layer stays visible without obscuring injected touches.
        inputDispatchDepth += 1;
    }

    synchronized void endInputDispatch() {
        if (inputDispatchDepth <= 0) return;
        inputDispatchDepth -= 1;
    }

    synchronized void destroy() {
        indicatorVisible = false;
        cursorVisible = false;
        edgeVisible = false;
        runOnMainSync(() -> {
            removeIndicator();
            removeCursor();
            removeEdge();
            return true;
        });
        if (islandBitmap != null) {
            islandBitmap.recycle();
            islandBitmap = null;
        }
        if (pointerBitmap != null) {
            pointerBitmap.recycle();
            pointerBitmap = null;
        }
    }

    private boolean applyIndicator() {
        if (!indicatorVisible) {
            removeIndicator();
            return true;
        }
        if (suppressed) {
            hideIndicator();
            return true;
        }
        Bitmap bitmap = islandBitmap;
        if (bitmap == null || bitmap.isRecycled()) return false;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = Math.max(0, (indicatorScreenWidth() - width) / 2);
        if (indicatorLayer == null || indicatorLayer.width != width || indicatorLayer.height != height) {
            releaseIndicatorLayer();
            indicatorLayer = new SurfaceLayer(
                    "Droidance Runtime Island", width, height, indicatorDisplayLayerStack, true);
        }
        drawBitmap(indicatorLayer, bitmap);
        indicatorLayer.show(left, indicatorTopOffsetPx, INDICATOR_LAYER);
        return true;
    }

    @android.annotation.SuppressLint("NewApi")
    synchronized SurfaceControl copyIndicatorSurface() {
        SurfaceLayer layer = indicatorLayer;
        if (layer == null || !layer.control.isValid()) return null;
        Parcel parcel = Parcel.obtain();
        try {
            layer.control.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return SurfaceControl.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    synchronized SurfaceControl copyIndicatorInputSurface() {
        SurfaceLayer layer = indicatorLayer;
        if (layer == null || layer.inputControl == null || !layer.inputControl.isValid()) return null;
        Parcel parcel = Parcel.obtain();
        try {
            layer.inputControl.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return SurfaceControl.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private void drawBitmap(SurfaceLayer layer, Bitmap bitmap) {
        Canvas canvas = null;
        try {
            canvas = layer.surface.lockCanvas(null);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint);
        } finally {
            if (canvas != null) layer.surface.unlockCanvasAndPost(canvas);
        }
    }

    private boolean applyCursor() {
        if (!cursorVisible) {
            removeCursor();
            return true;
        }
        if (suppressed) {
            hideCursor();
            return true;
        }
        if (pointerBitmap == null || pointerBitmap.isRecycled()) return false;
        int token = beginCursorAnimation();
        hasCursor = true;
        animateCursorFrame(token);
        return true;
    }

    private void animateCursorFrame(int token) {
        if (!isCursorAnimationActive(token)) return;
        CursorMotion.Frame frame = CursorMotion.sample(CursorMotion.nowMs(), startedAtMs,
                finishedAtMs, durationMs, actionType == ACTION_SWIPE);
        if (frame.alpha <= 0f) {
            cursorVisible = false;
            removeCursor();
            return;
        }
        CursorMotion.MotionSample sample = CursorMotion.sampleMotion(
                CursorMotion.nowMs(), startedAtMs, travelDurationMs, finishedAtMs,
                fromX, fromY, startX, startY, endX, endY, durationMs, actionType == ACTION_SWIPE, density
        );
        currentX = sample.x;
        currentY = sample.y;
        drawCursorFrame(sample, frame);
        if (!sample.animating) {
            currentX = (actionType == ACTION_SWIPE) ? endX : startX;
            currentY = (actionType == ACTION_SWIPE) ? endY : startY;
            return;
        }
        // The raw shell process uses no app animation runtime. Clock-based sampling skips late frames.
        float refreshRate = cursorDisplay.getRefreshRate();
        long intervalMs = Math.round(1000f / Math.max(30f, Math.min(60f, refreshRate)));
        long delayMs = intervalMs - Math.max(0L, CursorMotion.nowMs() - startedAtMs) % intervalMs;
        mainHandler.postDelayed(() -> animateCursorFrame(token), delayMs);
    }

    private void drawCursorFrame() {
        CursorMotion.Frame frame = CursorMotion.sample(CursorMotion.nowMs(), startedAtMs,
                finishedAtMs, durationMs, actionType == ACTION_SWIPE);
        CursorMotion.MotionSample sample = CursorMotion.sampleMotion(
                CursorMotion.nowMs(), startedAtMs, travelDurationMs, finishedAtMs,
                fromX, fromY, startX, startY, endX, endY, durationMs, actionType == ACTION_SWIPE, density
        );
        drawCursorFrame(sample, frame);
    }

    private void drawCursorFrame(CursorMotion.MotionSample sample, CursorMotion.Frame frame) {
        if (suppressed || !cursorVisible || !hasCursor) return;
        Bitmap pointer = pointerBitmap;
        if (pointer == null || pointer.isRecycled()) return;
        Display targetDisplay = cursorDisplay;
        if (targetDisplay == null) return;
        DisplayMetrics metrics = screenMetrics(targetDisplay);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if (cursorLayer == null || cursorLayer.width != width || cursorLayer.height != height) {
            releaseCursorLayer();
            cursorLayer = new SurfaceLayer(
                    "Droidance Runtime Hand", width, height, cursorDisplayLayerStack, false);
        }
        Canvas canvas = null;
        try {
            canvas = cursorLayer.surface.lockCanvas(null);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            handPainter.draw(canvas, pointer, startX, startY, sample, motionEnabled, density);
        } finally {
            if (canvas != null) cursorLayer.surface.unlockCanvasAndPost(canvas);
        }
        cursorLayer.show(0, 0, CURSOR_LAYER);
    }

    private void removeIndicator() {
        releaseIndicatorLayer();
    }

    private void hideIndicator() {
        if (indicatorLayer != null) indicatorLayer.hide();
    }

    private void releaseIndicatorLayer() {
        if (indicatorLayer != null) {
            indicatorLayer.release();
            indicatorLayer = null;
        }
    }

    private void removeCursor() {
        cancelCursorAnimation();
        hasCursor = false;
        hasPosition = false;
        releaseCursorLayer();
    }

    private void hideCursor() {
        if (cursorLayer != null) cursorLayer.hide();
    }

    private void releaseCursorLayer() {
        if (cursorLayer != null) {
            cursorLayer.release();
            cursorLayer = null;
        }
    }

    private int beginCursorAnimation() {
        cursorAnimationToken += 1;
        return cursorAnimationToken;
    }

    private void cancelCursorAnimation() {
        cursorAnimationToken += 1;
    }

    private boolean isCursorAnimationActive(int animationToken) {
        return animationToken == cursorAnimationToken
                && cursorVisible
                && hasCursor
                && !suppressed;
    }

    private Bitmap decodeBitmap(byte[] png, String label) {
        if (png == null || png.length == 0) throw new IllegalArgumentException(label + " bitmap empty");
        Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (decoded == null) throw new IllegalArgumentException(label + " bitmap decode failed");
        return decoded;
    }

    private boolean runOnMainSync(java.util.concurrent.Callable<Boolean> action) {
        try {
            if (Looper.myLooper() == mainHandler.getLooper()) return action.call();
            FutureTask<Boolean> task = new FutureTask<>(action);
            if (!mainHandler.post(task)) return false;
            return task.get(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("surface update failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("surface update interrupted", e);
        } catch (Throwable t) {
            throw new IllegalStateException("surface update failed", t);
        }
    }

    private int indicatorScreenWidth() {
        return screenMetrics(indicatorDisplay).widthPixels;
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics screenMetrics(Display targetDisplay) {
        DisplayMetrics metrics = new DisplayMetrics();
        targetDisplay.getRealMetrics(metrics);
        return metrics;
    }

    private float dpFloat(float value) {
        return value * density;
    }

    private static Method hiddenMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "required SurfaceControl display method unavailable: " + owner.getName() +
                            "." + name,
                    t
            );
        }
    }

    private static int resolveDisplayLayerStack(Display display) {
        try {
            return (Integer) DISPLAY_LAYER_STACK_METHOD.invoke(display);
        } catch (Throwable t) {
            throw new IllegalStateException("display layer stack unavailable", t);
        }
    }

    private static void bindToDisplayLayerStack(
            SurfaceControl.Transaction transaction,
            SurfaceControl control,
            int layerStack
    ) {
        try {
            SET_LAYER_STACK_METHOD.invoke(transaction, control, layerStack);
        } catch (Throwable t) {
            throw new IllegalStateException("bind runtime layer to display failed", t);
        }
    }

    /** Shell-owned presentation layers must not participate in input-occlusion blocking. */
    private static void markTrustedOverlay(SurfaceControl.Transaction transaction, SurfaceControl control) {
        // Android 11 has neither this call nor untrusted-touch occlusion blocking.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return;
        try {
            SurfaceControl.Transaction.class.getMethod("setTrustedOverlay", SurfaceControl.class, boolean.class)
                    .invoke(transaction, control, true);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Trusted runtime overlay unavailable", error);
        }
    }

    private static final class SurfaceLayer {
        final SurfaceControl control;
        final SurfaceControl inputControl;
        final Surface surface;
        final Surface inputSurface;
        final int width;
        final int height;
        final int layerStack;
        private boolean shownLogged;

        SurfaceLayer(String name, int width, int height, int layerStack, boolean withInputChild) {
            this.width = width;
            this.height = height;
            this.layerStack = layerStack;
            SurfaceControl control = new SurfaceControl.Builder()
                    .setName(name)
                    .setBufferSize(width, height)
                    .setFormat(PixelFormat.RGBA_8888)
                    .setOpaque(false)
                    .setHidden(true)
                    .build();
            this.control = control;
            this.inputControl = withInputChild
                    ? new SurfaceControl.Builder()
                            .setName(name + " Input")
                            .setParent(control)
                            .setBufferSize(width, height)
                            .setFormat(PixelFormat.RGBA_8888)
                            .setOpaque(false)
                            .setHidden(true)
                            .build()
                    : null;
            this.surface = new Surface(control);
            this.inputSurface = inputControl == null ? null : new Surface(inputControl);
            if (inputSurface != null) clearSurface(inputSurface);
            Log.i(TAG, "created layer name=" + name + " size=" + width + "x" + height +
                    " layerStack=" + layerStack);
        }

        void show(int x, int y, int layer) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                markTrustedOverlay(transaction, control);
                bindToDisplayLayerStack(transaction, control, layerStack);
                SidecarPlatformCompat.setCrop(transaction, control, new Rect(0, 0, width, height))
                        .setLayer(control, layer)
                        .setVisibility(control, true);
                // The app owns island drag position; bitmap refreshes only update visibility.
                if (inputControl == null || !shownLogged) transaction.setPosition(control, x, y);
                if (inputControl != null) {
                    SidecarPlatformCompat.setCrop(transaction, inputControl, new Rect(0, 0, width, height))
                            .setPosition(inputControl, 0, 0)
                            .setLayer(inputControl, 0)
                            .setVisibility(inputControl, true);
                }
                transaction.apply();
                if (!shownLogged) {
                    shownLogged = true;
                    Log.i(TAG, "layer shown position=" + x + "," + y + " z=" + layer);
                }
            }
        }

        void hide() {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setVisibility(control, false);
                if (inputControl != null) transaction.setVisibility(inputControl, false);
                transaction.apply();
            }
        }

        void release() {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setVisibility(control, false);
                if (inputControl != null) transaction.setVisibility(inputControl, false);
                transaction.apply();
            } finally {
                surface.release();
                if (inputSurface != null) inputSurface.release();
                if (inputControl != null) inputControl.release();
                control.release();
            }
        }

        private static void clearSurface(Surface surface) {
            Canvas canvas = null;
            try {
                canvas = surface.lockCanvas(null);
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            } finally {
                if (canvas != null) surface.unlockCanvasAndPost(canvas);
            }
        }
    }
}
