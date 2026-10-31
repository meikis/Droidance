// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.test;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Standalone test-APK editor using only platform classes, including outside instrumentation. */
public final class InputProbeActivity extends Activity {
    private EditText editor;

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (getIntent().getBooleanExtra("record_touch", false)) {
            try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "touch-probe.txt"), true)) {
                String line = event.getActionMasked() + "," + event.getDownTime() + ","
                        + event.getX() + "," + event.getY() + ","
                        + (event.getFlags() & (MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) + "\n";
                output.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) { throw new IllegalStateException(failure); }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        new File(getFilesDir(), "touch-probe.txt").delete();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Input verification");
        title.setTextSize(20);
        layout.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        editor = new EditText(this);
        editor.setId(View.generateViewId());
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setSingleLine(true);
        editor.setHint("Enter text");
        writeProbe("");
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                writeProbe(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        layout.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(layout);
        editor.requestFocus();
        editor.post(() -> {
            int[] location = new int[2];
            editor.getLocationOnScreen(location);
            String position = getDisplay().getDisplayId() + ","
                    + (location[0] + editor.getWidth() / 2) + ","
                    + (location[1] + editor.getHeight() / 2);
            try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "input-probe-position.txt"))) {
                output.write(position.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        });
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && !getIntent().getBooleanExtra("record_touch", false)) editor.post(() -> {
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null) manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void writeProbe(String value) {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "input-probe.txt"))) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Probe write failed", failure);
        }
    }
}
