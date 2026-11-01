// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the input dispatcher's focused window for one display, independently of IME ownership. */
final class InputWindowFocusParser {
    private InputWindowFocusParser() {}

    private static final String NO_FOCUSED_WINDOWS = "FocusedWindows: <none>";
    private static final Pattern WINDOW = Pattern.compile("^displayId=(\\d+), name='(.+)'$");

    static boolean requiredLine(String line) {
        String text = line.trim();
        return text.endsWith(":") || text.equals(NO_FOCUSED_WINDOWS) || WINDOW.matcher(text).matches();
    }

    static final class Result {
        final String target;
        final String windowName;
        final String diagnostic;
        Result(String target, String windowName, String diagnostic) {
            this.target = target; this.windowName = windowName; this.diagnostic = diagnostic;
        }
    }

    static Result parse(String dump, int displayId) {
        if (dump == null || displayId < 0) return new Result(null, null, "INPUT_WINDOW_FORMAT_UNAVAILABLE");
        boolean inFocusedWindows = false;
        boolean foundSection = false;
        int sectionIndent = -1;
        String selected = null;
        for (String line : dump.split("\\r?\\n")) {
            String text = line.trim();
            int indent = line.length() - line.replaceFirst("^\\s+", "").length();
            if (text.equals(NO_FOCUSED_WINDOWS)) { foundSection = true; continue; }
            if (text.equals("FocusedWindows:")) {
                inFocusedWindows = true; foundSection = true; sectionIndent = indent; continue;
            }
            if (inFocusedWindows && !text.isEmpty() && indent <= sectionIndent) inFocusedWindows = false;
            if (!inFocusedWindows || text.isEmpty()) continue;
            Matcher window = WINDOW.matcher(text);
            if (!window.matches()) return new Result(null, null, "INPUT_WINDOW_FORMAT_UNAVAILABLE");
            if (!window.group(1).equals(Integer.toString(displayId))) continue;
            if (selected != null && !selected.equals(window.group(2)))
                return new Result(null, null, "INPUT_WINDOW_CHANGED_DURING_QUERY");
            selected = window.group(2);
        }
        if (!foundSection) return new Result(null, null, "INPUT_WINDOW_FORMAT_UNAVAILABLE");
        if (selected == null) return new Result(null, null, "INPUT_WINDOW_NOT_FOCUSED");
        String identity = com.droidance.core.agent.tools.TextInputReadback.hash(displayId + "|" + selected);
        return new Result("input_window=true;displayId=" + displayId + ";window_id=" + identity,
                selected, "INPUT_WINDOW_FOCUSED");
    }
}
