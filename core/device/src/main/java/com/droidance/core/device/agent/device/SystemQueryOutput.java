// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import com.droidance.core.device.tools.SystemQueryDiagnostic;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** Selects native dump lines while draining the original stream, before bounded IPC retention. */
public final class SystemQueryOutput {
    private static final int MAX_LINE_CHARS = 16_384;

    public static final class Result {
        public final String stdout, diagnosticCode;
        public final long originalChars, selectedChars;
        public final int originalLines, selectedLines;
        public final boolean truncated;

        public Result(String stdout, long originalChars, long selectedChars,
                      int originalLines, int selectedLines, boolean truncated, String diagnosticCode) {
            this.stdout = stdout;
            this.originalChars = originalChars;
            this.selectedChars = selectedChars;
            this.originalLines = originalLines;
            this.selectedLines = selectedLines;
            this.truncated = truncated;
            this.diagnosticCode = diagnosticCode;
        }
    }

    private static final class Line {
        final String text;
        final long chars;
        final int number;
        final boolean newline;
        Line(String text, long chars, int number, boolean newline) {
            this.text = text; this.chars = chars; this.number = number; this.newline = newline;
        }
    }

    private final String filter;
    private final int context;
    private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
    private final ArrayDeque<Line> preceding = new ArrayDeque<>();
    private long originalChars, selectedChars;
    private int originalLines, selectedLines, emittedThrough, followingThrough;
    private boolean truncated, retentionExhausted;
    private String diagnosticCode;

    private SystemQueryOutput(String filter, int context) {
        if (filter == null || filter.length() > 200 || context < 0 || context > 40 ||
                (context > 0 && isBlank(filter))) throw new IllegalArgumentException("Invalid system selection");
        this.filter = filter;
        this.context = context;
    }

    public static Result read(InputStream input, String filter, int context) throws IOException {
        return read(new InputStreamReader(input, StandardCharsets.UTF_8), filter, context);
    }

    public static Result read(Reader reader, String filter, int context) throws IOException {
        return read(reader, filter, context, null);
    }

    public static Result readSelected(InputStream input, java.util.function.Predicate<String> selector) throws IOException {
        return read(new InputStreamReader(input, StandardCharsets.UTF_8), "", 0, selector);
    }

    private static Result read(Reader reader, String filter, int context,
                               java.util.function.Predicate<String> selector) throws IOException {
        SystemQueryOutput output = new SystemQueryOutput(filter, context);
        StringBuilder line = new StringBuilder();
        String matchTail = "";
        long lineChars = 0;
        boolean matched = isBlank(filter);
        boolean lineTruncated = false;
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            output.originalChars += count;
            int start = 0;
            for (int end = 0; end <= count; end++) {
                if (end < count && buffer[end] != '\n') continue;
                int length = end - start;
                lineChars += length;
                int keep = Math.min(length, MAX_LINE_CHARS - line.length());
                line.append(buffer, start, keep);
                lineTruncated |= keep < length;
                if (!matched && length > 0) {
                    String candidate = matchTail + new String(buffer, start, length);
                    matched = contains(candidate, filter);
                    matchTail = candidate.substring(Math.max(0, candidate.length() - filter.length() + 1));
                }
                if (end < count) {
                    String text = lineText(line, lineChars);
                    boolean selected = selector == null ? matched : selector.test(text);
                    output.truncated |= lineTruncated && (selector == null || selected);
                    output.accept(new Line(text, lineChars, ++output.originalLines, true), selected);
                    line.setLength(0); lineChars = 0; matchTail = ""; matched = isBlank(filter);
                    lineTruncated = false;
                }
                start = end + 1;
            }
        }
        if (lineChars > 0) {
            String text = lineText(line, lineChars);
            boolean selected = selector == null ? matched : selector.test(text);
            output.truncated |= lineTruncated && (selector == null || selected);
            output.accept(new Line(text, lineChars, ++output.originalLines, false), selected);
        }
        String text = retainedText(output.retained);
        return new Result(text, output.originalChars, output.selectedChars, output.originalLines,
                output.selectedLines, output.truncated, output.diagnosticCode);
    }

    private static boolean isBlank(String text) {
        for (int i = 0; i < text.length(); i++)
            if (!Character.isWhitespace(text.charAt(i)) && !Character.isSpaceChar(text.charAt(i))) return false;
        return true;
    }

    private static boolean contains(String text, String filter) {
        for (int i = 0; i <= text.length() - filter.length(); i++)
            if (text.regionMatches(true, i, filter, 0, filter.length())) return true;
        return false;
    }

    private static String lineText(StringBuilder text, long originalLength) {
        int end = text.length();
        if (originalLength > end && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static String retainedText(ByteArrayOutputStream bytes) {
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private void accept(Line line, boolean matched) {
        if (diagnosticCode == null) diagnosticCode = SystemQueryDiagnostic.INSTANCE.code(line.text, "");
        if (matched) {
            for (Line prior : preceding) emit(prior);
            followingThrough = line.number + context;
        }
        if (matched || line.number <= followingThrough) emit(line);
        if (context > 0) {
            preceding.addLast(line);
            while (preceding.size() > context) preceding.removeFirst();
        }
    }

    private void emit(Line line) {
        if (line.number <= emittedThrough) return;
        emittedThrough = line.number;
        selectedLines++;
        String prefix = context > 0 ? line.number + ": " : "";
        selectedChars += prefix.length() + line.chars + (line.newline ? 1 : 0);
        if (retentionExhausted) return;
        byte[] bytes = (prefix + line.text + (line.newline ? "\n" : "")).getBytes(StandardCharsets.UTF_8);
        int keep = Math.min(bytes.length, BoundedShellOutput.MAX_BYTES - retained.size());
        retentionExhausted = keep < bytes.length;
        truncated |= retentionExhausted;
        // Do not split a UTF-8 code point at the IPC boundary.
        if (keep < bytes.length) while (keep > 0 && (bytes[keep] & 0xc0) == 0x80) keep--;
        retained.write(bytes, 0, keep);
    }
}
