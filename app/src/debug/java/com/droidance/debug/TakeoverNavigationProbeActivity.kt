// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.droidance.R

/** Isolated app navigation target for the real display-bound takeover regression. */
class TakeoverNavigationProbeActivity : ComponentActivity() {
    var backCount: Int = 0
        private set
    var pageDepth: Int = 3
        private set
    private lateinit var page: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(25, 70, 180))
            gravity = android.view.Gravity.CENTER
        }
        setContentView(page)
        render()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backCount++
                pageDepth = (pageDepth - 1).coerceAtLeast(0)
                render()
            }
        })
    }

    private fun render() {
        page.text = getString(R.string.takeover_navigation_probe_status, pageDepth, backCount)
    }
}
