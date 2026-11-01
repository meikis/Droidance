// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device;


import org.junit.Test;
import static org.junit.Assert.*;
public class InputWindowFocusParserTest {
    private String dump(String windows) { return "Input Dispatcher State:\n  FocusedDisplayId: 0\n  FocusedWindows:\n" + windows + "  FocusedApplications:\n    displayId=0, name='unrelated-app'\n"; }
    @Test public void selectsRequestedWindowWhileThePhysicalDisplayOwnsGlobalFocus() {
        String d = dump("    displayId=0, name='aaa physical/.Editor'\n    displayId=358, name='bbb virtual/.Editor'\n");
        InputWindowFocusParser.Result physical = InputWindowFocusParser.parse(d, 0);
        InputWindowFocusParser.Result virtual = InputWindowFocusParser.parse(d, 358);
        assertEquals("bbb virtual/.Editor", virtual.windowName);
        assertTrue(virtual.target.contains("displayId=358;window_id="));
        assertNotEquals(physical.target, virtual.target);
        assertEquals("INPUT_WINDOW_NOT_FOCUSED", InputWindowFocusParser.parse(d, 999).diagnostic);
    }
    @Test public void opaqueWindowIdentityChangesWithoutChangingTheActivityName() {
        String a = InputWindowFocusParser.parse(dump("    displayId=3, name='aaa same/.Editor'\n"), 3).target;
        String b = InputWindowFocusParser.parse(dump("    displayId=3, name='bbb same/.Editor'\n"), 3).target;
        assertNotEquals(a, b);
    }
    @Test public void malformedAndConflictingSnapshotsNeverAttestAnInputTarget() {
        assertNull(InputWindowFocusParser.parse("mCurClient displayId=3", 3).target);
        assertNull(InputWindowFocusParser.parse(dump("    displayId=3 name=unknown\n"), 3).target);
        String a = dump("    displayId=3, name='aaa activity'\n");
        assertNotNull(InputWindowFocusParser.parse(a + a, 3).target);
        assertEquals("INPUT_WINDOW_CHANGED_DURING_QUERY", InputWindowFocusParser.parse(a + dump("    displayId=3, name='bbb activity'\n"), 3).diagnostic);
    }

    @Test public void noFocusedWindowsIsNotAnUnknownFormat() {
        assertTrue(InputWindowFocusParser.requiredLine("  FocusedWindows: <none>"));
        assertEquals("INPUT_WINDOW_NOT_FOCUSED",
                InputWindowFocusParser.parse("  FocusedWindows: <none>\n", 7).diagnostic);
    }
}
