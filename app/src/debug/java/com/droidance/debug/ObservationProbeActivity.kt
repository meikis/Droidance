// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View

/** Deterministic changing surface for the shell-side decision-frame integration probe. */
class ObservationProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var blue = false
        setContentView(View(this).apply {
            setBackgroundColor(Color.RED)
            setOnClickListener {
                postDelayed({
                    blue = !blue
                    setBackgroundColor(if (blue) Color.BLUE else Color.RED)
                }, 350)
            }
        })
    }
}
