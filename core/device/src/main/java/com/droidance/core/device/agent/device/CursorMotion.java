// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

/** Shared presentation clock for the preview View and shell surface. Never schedules input. */
public final class CursorMotion {
    public static final float HOTSPOT_X = 0.28f;
    public static final float HOTSPOT_Y = 0.20f;

    private CursorMotion() {}

    public static long nowMs() { return System.nanoTime() / 1_000_000L; }

    public static final class Frame {
        public final float progress, alpha, press, feedback;
        public final boolean animating;
        public Frame(float progress, float alpha, float press, float feedback, boolean animating) {
            this.progress = progress;
            this.alpha = alpha;
            this.press = press;
            this.feedback = feedback;
            this.animating = animating;
        }
    }

    public static long calculateTravelDuration(float distancePx) {
        if (distancePx <= 15f) return 0L;
        float duration = 70f + (distancePx / 1200f) * 110f;
        return (long) Math.max(70f, Math.min(180f, duration));
    }

    public static final class MotionSample {
        public final float x, y, tilt, press, feedback, alpha;
        public final boolean animating;
        public final boolean isTraveling;
        public final boolean isSwiping;
        public final float swipeProgress;

        public MotionSample(float x, float y, float tilt, float press, float feedback,
                            float alpha, boolean animating, boolean isTraveling,
                            boolean isSwiping, float swipeProgress) {
            this.x = x;
            this.y = y;
            this.tilt = tilt;
            this.press = press;
            this.feedback = feedback;
            this.alpha = alpha;
            this.animating = animating;
            this.isTraveling = isTraveling;
            this.isSwiping = isSwiping;
            this.swipeProgress = swipeProgress;
        }
    }

    public static MotionSample sampleMotion(
            long nowMs,
            long startedAtMs,
            long travelDurationMs,
            long finishedAtMs,
            float fromX, float fromY,
            float targetX, float targetY,
            float endX, float endY,
            int gestureDurationMs,
            boolean isSwipe,
            float density
    ) {
        if (travelDurationMs > 0L && nowMs < startedAtMs + travelDurationMs) {
            float t = clamp((nowMs - startedAtMs) / (float) travelDurationMs);
            // Quintic smoothstep easing: smooth zero-jerk acceleration and deceleration
            float u = t * t * t * (t * (t * 6f - 15f) + 10f);
            float dx = targetX - fromX;
            float dy = targetY - fromY;
            float dist = (float) Math.hypot(dx, dy);
            float x, y;
            if (dist > 20f) {
                float h = Math.min(32f * Math.max(1f, density), dist * 0.10f);
                float sign = (dx >= 0) ? -1f : 1f;
                float ctrlX = (fromX + targetX) * 0.5f + sign * h * (-dy / dist);
                float ctrlY = (fromY + targetY) * 0.5f + sign * h * (dx / dist);
                float oneMinusU = 1f - u;
                x = oneMinusU * oneMinusU * fromX + 2f * oneMinusU * u * ctrlX + u * u * targetX;
                y = oneMinusU * oneMinusU * fromY + 2f * oneMinusU * u * ctrlY + u * u * targetY;
            } else {
                x = fromX + dx * u;
                y = fromY + dy * u;
            }
            float maxTilt = Math.max(-10f, Math.min(10f, dx / (30f * Math.max(1f, density))));
            float tilt = maxTilt * (float) Math.sin(Math.PI * u);
            return new MotionSample(x, y, tilt, 1f, 0f, 1f, true, true, false, 0f);
        }

        long actionStartedAtMs = startedAtMs + travelDurationMs;
        long age = Math.max(0L, nowMs - actionStartedAtMs);
        boolean finished = finishedAtMs > 0L;

        if (isSwipe) {
            long duration = Math.max(1L, gestureDurationMs);
            float progress = finished ? 1f : clamp(age / (float) duration);
            boolean animating = !finished && age < duration;
            float x = targetX + (endX - targetX) * progress;
            float y = targetY + (endY - targetY) * progress;
            float tilt = Math.max(-9f, Math.min(9f, (endX - targetX) / 90f)) * (float) Math.sin(Math.PI * progress);
            float press = animating ? 0.94f : 1f;
            return new MotionSample(x, y, tilt, press, 0f, 1f, animating, false, true, progress);
        } else {
            float x = targetX;
            float y = targetY;
            float tapPhase = clamp(age / 160f);
            boolean animating = age < 160f;
            float press = 1f - 0.12f * (float) Math.sin(Math.PI * tapPhase);
            float feedback = 1f - tapPhase;
            return new MotionSample(x, y, 0f, press, feedback, 1f, animating, false, false, 0f);
        }
    }

    public static Frame sample(long nowMs, long startedAtMs, long finishedAtMs,
                               int durationMs, boolean swipe) {
        long age = Math.max(0L, nowMs - startedAtMs);
        long duration = swipe ? Math.max(1L, durationMs) : 160L;
        boolean finished = finishedAtMs > 0L;
        float progress = finished ? 1f : clamp(age / (float) duration);
        // A settled cursor persists without replaying the gesture or redrawing indefinitely.
        boolean animating = swipe ? !finished && age < duration : age < 160L;
        float tapPhase = clamp(age / 160f);
        float press = swipe ? (animating ? 0.94f : 1f) : 1f - 0.12f * (float) Math.sin(Math.PI * tapPhase);
        return new Frame(progress, 1f, press, swipe ? 0f : 1f - tapPhase, animating);
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
}
