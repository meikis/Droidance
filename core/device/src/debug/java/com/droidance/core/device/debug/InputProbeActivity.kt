// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.debug



import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Isolated editor for device input verification; absent from release builds. */
class InputProbeActivity : Activity() {
    lateinit var editor: EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = EditText(this).apply {
            id = android.view.View.generateViewId()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            hint = "Input verification"
        }
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                java.io.File(filesDir, "input-probe-${windowManager.defaultDisplay.displayId}.txt").writeText(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        setContentView(editor)
        editor.setText(intent.getStringExtra("initial_text").orEmpty())
        editor.requestFocus()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) editor.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
