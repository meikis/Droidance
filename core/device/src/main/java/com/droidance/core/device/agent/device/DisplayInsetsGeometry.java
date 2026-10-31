// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

/** Physical display pixels, never JPEG preview pixels. Sides are left, top, right, bottom. */
public final class DisplayInsetsGeometry {
    private DisplayInsetsGeometry() {}

    public static int[] map(int[] sides, int sourceWidth, int sourceHeight,
                            int targetWidth, int targetHeight) {
        if (sides == null || sides.length != 4 || sourceWidth <= 0 || sourceHeight <= 0
                || targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("Invalid display inset geometry");
        }
        for (int side : sides) if (side < 0) throw new IllegalArgumentException("Negative inset");
        if ((long) sides[0] + sides[2] >= sourceWidth || (long) sides[1] + sides[3] >= sourceHeight) {
            throw new IllegalArgumentException("Insets consume the entire source display");
        }
        // The viewer draws an upright bitmap using FIT_CENTER. Insets only apply where a host
        // system bar actually intersects that image; letterbox margins already provide clearance.
        // Rotation is represented by the current bounds, not by rotating the bitmap a second time.
        double scale = Math.min(sourceWidth / (double) targetWidth, sourceHeight / (double) targetHeight);
        double offsetX = (sourceWidth - targetWidth * scale) / 2;
        double offsetY = (sourceHeight - targetHeight * scale) / 2;
        int[] mapped = new int[4];
        for (int side = 0; side < 4; side++) {
            double offset = side % 2 == 0 ? offsetX : offsetY;
            mapped[side] = (int) Math.round(Math.max(0, sides[side] - offset) / scale);
        }
        return mapped;
    }
}
