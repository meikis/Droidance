// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;

/** Shared edge light for the virtual-space preview and the main-screen system surface. */
public final class AuroraEdgePainter {
    public static final long CYCLE_MS = 18_000L;
    public static final long FRAME_INTERVAL_MS = 33L;
    public static final long FADE_IN_MS = 700L;

    private final Paint haloPaint;
    private final Paint weavePaint;
    private final Paint corePaint;
    private final Path edgePath = new Path();
    private final Matrix gradientMatrix = new Matrix();
    private SweepGradient primary;
    private SweepGradient secondary;
    private float centerX;
    private float centerY;

    public AuroraEdgePainter(float density) {
        haloPaint = stroke(density, 8f, 8f);
        weavePaint = stroke(density, 5f, 5f);
        corePaint = stroke(density, 2.5f, 2f);
    }

    private static Paint stroke(float density, float width, float blur) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * width);
        paint.setMaskFilter(new BlurMaskFilter(density * blur, BlurMaskFilter.Blur.NORMAL));
        return paint;
    }

    public void resize(int width, int height, float[] cornerRadii) {
        centerX = width / 2f;
        centerY = height / 2f;
        primary = new SweepGradient(centerX, centerY, new int[] {
                Color.rgb(90, 200, 250), Color.rgb(123, 108, 246),
                Color.rgb(255, 94, 156), Color.rgb(255, 159, 90), Color.rgb(90, 200, 250)
        }, null);
        secondary = new SweepGradient(centerX, centerY, new int[] {
                Color.rgb(255, 94, 156), Color.rgb(90, 200, 250),
                Color.rgb(139, 124, 255), Color.rgb(255, 122, 184), Color.rgb(255, 94, 156)
        }, null);
        edgePath.reset();
        edgePath.addRoundRect(new RectF(0f, 0f, width, height), cornerRadii, Path.Direction.CW);
    }

    public void draw(Canvas canvas, float phase, float opacity) {
        if (primary == null || secondary == null) return;
        float breath = 0.86f + 0.14f * (float) Math.sin(phase * 6f * Math.PI);
        float alpha = Math.max(0f, Math.min(1f, opacity)) * breath;
        gradientMatrix.setRotate(phase * 720f, centerX, centerY);
        primary.setLocalMatrix(gradientMatrix);
        haloPaint.setShader(primary);
        haloPaint.setAlpha((int) (255 * 0.42f * alpha));
        canvas.drawPath(edgePath, haloPaint);
        gradientMatrix.setRotate(-phase * 360f, centerX, centerY);
        secondary.setLocalMatrix(gradientMatrix);
        weavePaint.setShader(secondary);
        weavePaint.setAlpha((int) (255 * 0.34f * alpha));
        canvas.drawPath(edgePath, weavePaint);
        corePaint.setShader(primary);
        corePaint.setAlpha((int) (255 * 0.78f * alpha));
        canvas.drawPath(edgePath, corePaint);
    }
}
