// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;



import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;

/** Shell-only visual smoke test. Does not inject touches or start an agent task. */
public final class MascotSurfaceProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Context context = SidecarShellContext.create();
        SidecarRuntimeSurface surface = new SidecarRuntimeSurface(context, () -> {}, () -> {});
        Bitmap source = BitmapFactory.decodeFile(args[0]);
        int size = Math.round(context.getResources().getDisplayMetrics().density * 36f);
        Bitmap hand = Bitmap.createScaledBitmap(source, size, size, true);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        hand.compress(Bitmap.CompressFormat.PNG, 100, stream);
        byte[] png = stream.toByteArray();
        long start = CursorMotion.nowMs();
        surface.updateCursorBitmap(0, true, 2, 250, 650, 650, 950, 600, start, 0L, true, png);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            surface.beginInputDispatch();
            System.out.println("SUPPRESS_OK");
        }, 200);
        handler.postDelayed(() -> {
            surface.endInputDispatch();
            System.out.println("RESTORE_WITH_CURRENT_PROGRESS_OK");
        }, 400);
        handler.postDelayed(() -> {
            surface.updateCursorBitmap(0, true, 2, 250, 650, 650, 950, 600, start, start + 600, true, png);
            System.out.println("FINISHED_AT_ENDPOINT_OK");
        }, 650);
        handler.postDelayed(() -> {
            surface.setSuppressed(true);
            surface.setSuppressed(false);
            System.out.println("EXPIRED_RESTORE_OK");
        }, 1200);
        handler.postDelayed(() -> {
            surface.destroy();
            System.out.println("SURFACE_PROBE_COMPLETE");
            System.exit(0);
        }, 1800);
        Looper.loop();
    }
}
