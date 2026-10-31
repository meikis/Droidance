// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class DisplayInsetsGeometryTest {
    @Test public void physicalInsetsAreIndependentOfPreviewResolution() {
        assertArrayEquals(new int[]{0, 120, 0, 0}, DisplayInsetsGeometry.map(
                new int[]{0, 120, 0, 0}, 1080, 2376, 1080, 2376));
        assertArrayEquals(new int[]{0, 80, 0, 32}, DisplayInsetsGeometry.map(
                new int[]{0, 120, 0, 48}, 1080, 2376, 720, 1584));
    }
    @Test public void letterboxMarginsAlreadyProtectTheImage() {
        assertArrayEquals(new int[]{0, 0, 0, 0}, DisplayInsetsGeometry.map(
                new int[]{0, 120, 0, 48}, 1080, 2376, 2376, 1080));
        assertArrayEquals(new int[]{0, 106, 0, 0}, DisplayInsetsGeometry.map(
                new int[]{120, 48, 48, 0}, 2376, 1080, 1080, 2376));
    }
    @Test public void geometryCanReturnToZeroWithoutKeepingOldPadding() {
        assertArrayEquals(new int[]{0, 0, 0, 0}, DisplayInsetsGeometry.map(
                new int[]{0, 0, 0, 0}, 1080, 2376, 1080, 2376));
    }
    @Test(expected = IllegalArgumentException.class) public void invalidExtentIsRejected() {
        DisplayInsetsGeometry.map(new int[]{0, 0, 0, 0}, 0, 2376, 1080, 2376);
    }
    @Test(expected = IllegalArgumentException.class) public void negativeSafeAreaIsRejected() {
        DisplayInsetsGeometry.map(new int[]{0, -1, 0, 0}, 1080, 2376, 1080, 2376);
    }
    @Test(expected = IllegalArgumentException.class) public void impossibleSafeAreaIsRejected() {
        DisplayInsetsGeometry.map(new int[]{0, Integer.MAX_VALUE, 0, 120}, 1080, 2376, 1080, 2376);
    }
}
