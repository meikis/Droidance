// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.view.KeyEvent;

final class KeyComboMetaStateTracker {
    private int metaState;

    int onKeyDown(int keycode) {
        metaState |= modifierMask(keycode);
        return metaState;
    }

    int onKeyUp(int keycode) {
        int eventMetaState = metaState;
        metaState &= ~modifierMask(keycode);
        return eventMetaState;
    }

    int currentMetaState() {
        return metaState;
    }

    private static int modifierMask(int keycode) {
        switch (keycode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
            case KeyEvent.KEYCODE_META_LEFT:
                return KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
            case KeyEvent.KEYCODE_META_RIGHT:
                return KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON;
            default:
                return 0;
        }
    }
}
