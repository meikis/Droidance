// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class InputFocusQueryStatusTest {
    @Test public void timeoutWithEmptyStderrRetainsItsLifecycleCause() {
        ManagedShellCommands.Result result = new ManagedShellCommands.Result(-1, "", "");
        result.diagnosticCode = "SHELL_TIMEOUT_EFFECT_UNKNOWN";
        assertEquals("INPUT_FOCUS_QUERY_TIMEOUT", InputFocusQueryStatus.diagnostic(result));
    }
    @Test public void stderrCannotTurnANonzeroExitIntoATimeout() {
        ManagedShellCommands.Result result = new ManagedShellCommands.Result(1, "", "TIMEOUT is unrelated text");
        result.diagnosticCode = "SHELL_EXIT_NONZERO";
        assertEquals("INPUT_FOCUS_QUERY_FAILED", InputFocusQueryStatus.diagnostic(result));
    }
    @Test public void cancellationAndTruncationRemainDistinct() {
        ManagedShellCommands.Result result = new ManagedShellCommands.Result(-1, "", "");
        result.diagnosticCode = "SHELL_CANCELED_EFFECT_UNKNOWN";
        assertEquals("INPUT_FOCUS_CANCELED", InputFocusQueryStatus.diagnostic(result));
        result.diagnosticCode = null;
        result.stdoutTruncated = true;
        assertEquals("INPUT_FOCUS_OUTPUT_TRUNCATED", InputFocusQueryStatus.diagnostic(result));
    }
}
