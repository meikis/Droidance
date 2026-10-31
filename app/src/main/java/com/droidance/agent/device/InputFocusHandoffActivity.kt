// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.app.Activity
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager

/** A non-editing focus handoff on the execution display; never an IME or a text proxy. */
class InputFocusHandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        if (windowManager.defaultDisplay.displayId == Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.setLayout(1, 1)
        window.setDimAmount(0f)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(View(this))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Post after the current focus dispatch, including Android's IME focus notification.
        if (hasFocus) window.decorView.post { if (!isFinishing) finish() }
    }
}
