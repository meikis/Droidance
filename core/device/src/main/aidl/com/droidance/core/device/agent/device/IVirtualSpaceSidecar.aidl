// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import android.os.Bundle;
import android.view.SurfaceControl;
import com.droidance.core.device.agent.device.IRuntimeIndicatorCallback;
import com.droidance.core.device.agent.device.NativeScreenFrame;

interface IVirtualSpaceSidecar {
    boolean start(int width, int height, int densityDpi);
    int getDisplayId();
    int getUid();
    String getStatus();
    String getLastError();
    boolean isFrameReady();
    boolean launchPackage(int displayId, String packageName, String component);
    boolean launchActivity(int displayId, String component);
    boolean tap(int displayId, int x, int y);
    boolean doubleTap(int displayId, int x, int y);
    boolean longPress(int displayId, int x, int y, int durationMs);
    boolean touch(int displayId, int action, int x, int y, long downTimeMs, long eventTimeMs);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);
    boolean key(int displayId, int keycode);
    boolean keyCombo(int displayId, in int[] keycodes);
    Bundle text(int displayId, String text, boolean clearExisting, String expectedFocusTarget, String method);
    String getForegroundComponent(int displayId);
    String getInputFocusState(int displayId);
    byte[] capturePng(int displayId);
    Bundle execShellCommand(String command, long timeoutMs, String executionToken);
    boolean shutdown();
    void destroy();
    // Protocol 58 uses one paired shell process and no execution-role parameter.
    byte[] capturePreviewJpeg(int displayId);
    int getProtocolVersion();
    boolean onScreenLocked();
    boolean onScreenUnlocked();
    Bundle capturePngFrame(int displayId);
    boolean updateRuntimeIndicator(boolean visible, String label, String actionLabel, boolean paused);
    boolean updateRuntimeCursor(boolean visible, int actionType, int startX, int startY, int endX, int endY, int durationMs);
    boolean setRuntimeSurfaceSuppressed(boolean suppressed);
    void setRuntimeIndicatorCallback(IRuntimeIndicatorCallback callback);
    boolean updateRuntimeIsland(boolean visible, String progressText, String currentActionText, String takeoverLabel, String stopLabel, boolean paused);
    boolean updateRuntimeIslandBitmap(boolean visible, in byte[] islandPng, int topOffsetPx);
    boolean updateRuntimeCursorBitmap(boolean visible, int actionType, int startX, int startY, int endX, int endY, int durationMs, long startedAtMs, long finishedAtMs, boolean motionEnabled, in byte[] pointerPng);
    SurfaceControl getRuntimeIslandInputSurface();
    boolean updateRuntimeCursorBitmapOnDisplay(int targetDisplayId, boolean visible, int actionType, int startX, int startY, int endX, int endY, int durationMs, long startedAtMs, long finishedAtMs, boolean motionEnabled, in byte[] pointerPng);
    String getLastInputEvidence();
    boolean ensureDisplayInteractive(int displayId);
    Bundle sampleFrame(int displayId);
    Bundle capturePreviewFrame(int displayId);
    NativeScreenFrame captureFreshFrame(int displayId, int sampleSide);
    oneway void cancelShellCommand(String executionToken);
    Bundle awaitInputFocus(int displayId, long timeoutMs, String executionToken);
    Bundle captureFreshSample(int displayId, in int[] coordinates);
    oneway void cancelInputFocus(String executionToken);
    Bundle configureDisplayInsets(int displayId, in Bundle geometry);
    Bundle querySystem(String serviceName, String queryKind, String lineFilter, int contextLines, String executionToken);
    Bundle inspectDisplayContent(int displayId);
    boolean updateRuntimeEdgeLight(boolean visible, float density, in float[] cornerRadii, boolean motionEnabled);
    SurfaceControl getRuntimeIslandSurface();
    String getCodeSourcePath();
    boolean attachViewerMirror(int displayId, in SurfaceControl parent, int viewWidth, int viewHeight, String owner, IBinder lifetime);
    boolean detachViewerMirror(int displayId, String owner);
    Bundle readClipboard();
    boolean setViewerImeEnabled(int displayId, boolean enabled, String owner, IBinder lifetime);
    int getViewerImeBottomInset(int displayId);
    boolean endViewerInput(int displayId);
    boolean setExecutionImeEnabled(int displayId, boolean enabled, String owner, IBinder lifetime);
}
