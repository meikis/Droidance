// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

/** Preserves shell lifecycle failures independently of human-readable stderr. */
final class InputFocusQueryStatus {
    private InputFocusQueryStatus() {}

    static String diagnostic(ManagedShellCommands.Result query) {
        if ("SHELL_TIMEOUT_EFFECT_UNKNOWN".equals(query.diagnosticCode) ||
                query.systemOutput != null && "SYSTEM_QUERY_TIMEOUT".equals(query.systemOutput.diagnosticCode))
            return "INPUT_FOCUS_QUERY_TIMEOUT";
        if ("SHELL_CANCELED_EFFECT_UNKNOWN".equals(query.diagnosticCode) || "SHELL_REVOKED".equals(query.diagnosticCode))
            return "INPUT_FOCUS_CANCELED";
        if (query.stdoutTruncated) return "INPUT_FOCUS_OUTPUT_TRUNCATED";
        return "INPUT_FOCUS_QUERY_FAILED";
    }
}
