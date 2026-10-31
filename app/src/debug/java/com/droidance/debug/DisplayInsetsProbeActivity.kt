// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File

/** Device acceptance fixture. Reports actual client-delivered insets and clickable geometry. */
class DisplayInsetsProbeActivity : Activity() {
    private var taps = 0
    private var lastInsets: WindowInsetsCompat? = null
    private lateinit var content: LinearLayout
    private lateinit var button: Button
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(238, 245, 255))
        }
        button = Button(this).apply {
            text = "Insets verification"
            setOnClickListener { taps++; report() }
        }
        editor = EditText(this).apply {
            hint = "IME verification"
            setSingleLine(true)
            setOnClickListener {
                requestFocus()
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                report()
            }
        }
        content.addView(button, LinearLayout.LayoutParams(-1, 160))
        content.addView(editor, LinearLayout.LayoutParams(-1, 160))
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            lastInsets = insets
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            content.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            content.post { report() }
            insets
        }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> report() }
        setContentView(content)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        report()
    }

    private fun report() {
        val insets = lastInsets ?: return
        val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val buttonAt = IntArray(2).also(button::getLocationOnScreen)
        val editorAt = IntArray(2).also(editor::getLocationOnScreen)
        val id = content.display?.displayId ?: -1
        val report = JSONObject().put("displayId", id).put("statusTop", status.top)
            .put("navBottom", nav.bottom).put("cutoutTop", cutout.top).put("imeBottom", ime.bottom)
            .put("buttonX", buttonAt[0] + button.width / 2).put("buttonY", buttonAt[1] + button.height / 2)
            .put("editorX", editorAt[0] + editor.width / 2).put("editorY", editorAt[1] + editor.height / 2)
            .put("editorFocused", editor.hasFocus()).put("focused", window.decorView.hasWindowFocus()).put("contentWidth", content.width).put("contentHeight", content.height).put("taps", taps)
            .put("instance", intent.getStringExtra("instance") ?: "first")
        val file = File(filesDir, "display-insets-$id.json")
        val pending = File(filesDir, "display-insets-$id.tmp")
        pending.writeText(report.toString())
        pending.renameTo(file)
    }
}
