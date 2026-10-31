// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.program;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProgramEngineLimitsTest {
    @Test public void androidOnlyReceivesDeclaredJsonArguments() {
        new ProgramEngine().execute("var r=android.call('android_read',{op:'device'}); if(r.executionStatus!=='SUCCEEDED')throw Error('bad');",
            (op,json) -> { assertEquals("android",op); assertTrue(json.contains("android_read")); return "{\"value\":{\"executionStatus\":\"SUCCEEDED\"}}"; }, ()->false);
    }
    @Test public void oversizedHostRequestIsRejectedBeforeHostCall() {
        try {
            new ProgramEngine().execute("workspace.write('huge','x'.repeat(140000))", (op,json)->{fail("must be bounded");return "{}";}, ()->false);
            fail("oversized request accepted");
        } catch (org.mozilla.javascript.RhinoException expected) { assertTrue(expected.getMessage().contains("too large")); }
    }
}
