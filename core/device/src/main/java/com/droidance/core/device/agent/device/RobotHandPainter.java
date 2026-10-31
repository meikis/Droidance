// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** The robot's own hand, shared by the main display and preview. Pivot is always the fingertip. */
public final class RobotHandPainter {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint feedbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    public void draw(Canvas canvas, Bitmap hand, float sx, float sy, float ex, float ey,
                     CursorMotion.Frame frame, boolean swipe, boolean animate, float density) {
        if (frame.alpha <= 0f) return;
        float x = sx + (ex - sx) * frame.progress;
        float y = sy + (ey - sy) * frame.progress;
        feedbackPaint.setColor(Color.rgb(153, 143, 238));
        feedbackPaint.setStyle(Paint.Style.STROKE);
        feedbackPaint.setStrokeCap(Paint.Cap.ROUND);
        if (swipe && frame.animating) {
            float dx = ex - sx, dy = ey - sy;
            float distance = (float) Math.hypot(dx, dy);
            float tail = Math.max(0f, frame.progress - 42f * density / Math.max(1f, distance));
            feedbackPaint.setStrokeWidth(2f * density);
            feedbackPaint.setAlpha(Math.round(100f * frame.alpha));
            canvas.drawLine(sx + dx * tail, sy + dy * tail, x, y, feedbackPaint);
        } else if (animate && frame.feedback > 0f) {
            feedbackPaint.setStrokeWidth(1.2f * density);
            feedbackPaint.setAlpha(Math.round(150f * frame.feedback * frame.alpha));
            canvas.drawCircle(x, y, (4f + 12f * (1f - frame.feedback)) * density, feedbackPaint);
        }
        float width = hand.getWidth(), height = hand.getHeight();
        bounds.set(x - width * CursorMotion.HOTSPOT_X, y - height * CursorMotion.HOTSPOT_Y,
                x + width * (1f - CursorMotion.HOTSPOT_X), y + height * (1f - CursorMotion.HOTSPOT_Y));
        bitmapPaint.setAlpha(Math.round(255f * frame.alpha));
        canvas.save();
        if (animate) {
            float tilt = swipe ? Math.max(-9f, Math.min(9f, (ex - sx) / 90f)) : 0f;
            canvas.rotate(tilt * (float) Math.sin(Math.PI * frame.progress), x, y);
            canvas.scale(1f + (1f - frame.press) * 0.25f, frame.press, x, y);
        }
        canvas.drawBitmap(hand, null, bounds, bitmapPaint);
        canvas.restore();
    }

    public void draw(Canvas canvas, Bitmap hand, float swipeStartX, float swipeStartY,
                     CursorMotion.MotionSample sample, boolean animate, float density) {
        if (sample.alpha <= 0f) return;
        float x = sample.x;
        float y = sample.y;
        feedbackPaint.setColor(Color.rgb(153, 143, 238));
        feedbackPaint.setStyle(Paint.Style.STROKE);
        feedbackPaint.setStrokeCap(Paint.Cap.ROUND);
        if (sample.isSwiping && sample.animating) {
            float dx = x - swipeStartX, dy = y - swipeStartY;
            float distance = (float) Math.hypot(dx, dy);
            float tail = Math.max(0f, sample.swipeProgress - 42f * density / Math.max(1f, distance));
            feedbackPaint.setStrokeWidth(2f * density);
            feedbackPaint.setAlpha(Math.round(100f * sample.alpha));
            canvas.drawLine(swipeStartX + dx * tail, swipeStartY + dy * tail, x, y, feedbackPaint);
        } else if (animate && sample.feedback > 0f) {
            feedbackPaint.setStrokeWidth(1.2f * density);
            feedbackPaint.setAlpha(Math.round(150f * sample.feedback * sample.alpha));
            canvas.drawCircle(x, y, (4f + 12f * (1f - sample.feedback)) * density, feedbackPaint);
        }
        float width = hand.getWidth(), height = hand.getHeight();
        bounds.set(x - width * CursorMotion.HOTSPOT_X, y - height * CursorMotion.HOTSPOT_Y,
                x + width * (1f - CursorMotion.HOTSPOT_X), y + height * (1f - CursorMotion.HOTSPOT_Y));
        bitmapPaint.setAlpha(Math.round(255f * sample.alpha));
        canvas.save();
        if (animate) {
            canvas.rotate(sample.tilt, x, y);
            canvas.scale(1f + (1f - sample.press) * 0.25f, sample.press, x, y);
        }
        canvas.drawBitmap(hand, null, bounds, bitmapPaint);
        canvas.restore();
    }
}
