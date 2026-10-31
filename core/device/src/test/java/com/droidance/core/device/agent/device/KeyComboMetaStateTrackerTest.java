// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;



import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;
import org.junit.Test;

public class KeyComboMetaStateTrackerTest {
    @Test
    public void ctrlAEventsCarryCtrlMetaUntilCtrlRelease() {
        KeyComboMetaStateTracker tracker = new KeyComboMetaStateTracker();
        int ctrlMask = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;

        assertEquals(ctrlMask, tracker.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(ctrlMask, tracker.onKeyDown(KeyEvent.KEYCODE_A));
        assertEquals(ctrlMask, tracker.onKeyUp(KeyEvent.KEYCODE_A));
        assertEquals(ctrlMask, tracker.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(0, tracker.currentMetaState());
    }

    @Test
    public void ctrlShiftZKeepsEachModifierUntilItsOwnRelease() {
        KeyComboMetaStateTracker tracker = new KeyComboMetaStateTracker();
        int ctrlMask = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        int shiftMask = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;

        assertEquals(ctrlMask, tracker.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(ctrlMask | shiftMask, tracker.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT));
        assertEquals(ctrlMask | shiftMask, tracker.onKeyDown(KeyEvent.KEYCODE_Z));
        assertEquals(ctrlMask | shiftMask, tracker.onKeyUp(KeyEvent.KEYCODE_Z));
        assertEquals(ctrlMask | shiftMask, tracker.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT));
        assertEquals(ctrlMask, tracker.currentMetaState());
        assertEquals(ctrlMask, tracker.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(0, tracker.currentMetaState());
    }
}
