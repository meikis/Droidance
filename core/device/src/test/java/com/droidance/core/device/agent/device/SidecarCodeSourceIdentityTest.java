// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import org.junit.Test;
import static org.junit.Assert.*;

public class SidecarCodeSourceIdentityTest {
    private static final String SOURCE = "/data/app/current/base.apk";
    private static final String COMMAND = "2000 42 app_process /system/bin com.droidance.core.device.agent.device.VirtualSpaceSidecarMain 1080 2376 480 app.package ";

    @Test public void sameProtocolFromAnotherApkDoesNotOwnTheCurrentBuildLease() {
        assertFalse(VirtualSpaceSidecarMain.isCurrentBuildCommand(
                COMMAND + "droidance-sidecar-protocol=59 droidance-sidecar-source=/data/app/previous/base.apk", 59, SOURCE));
        assertTrue(VirtualSpaceSidecarMain.isCurrentBuildCommand(
                COMMAND + "droidance-sidecar-protocol=59 droidance-sidecar-source=" + SOURCE, 59, SOURCE));
    }

    @Test public void protocolAndSourceMustBothMatchExactly() {
        assertFalse(VirtualSpaceSidecarMain.isCurrentBuildCommand(
                COMMAND + "droidance-sidecar-protocol=590 droidance-sidecar-source=" + SOURCE, 59, SOURCE));
        assertFalse(VirtualSpaceSidecarMain.isCurrentBuildCommand(
                COMMAND + "droidance-sidecar-protocol=59 droidance-sidecar-source=" + SOURCE + ".old", 59, SOURCE));
        assertFalse(VirtualSpaceSidecarMain.isCurrentBuildCommand(
                COMMAND + "droidance-sidecar-protocol=59", 59, SOURCE));
    }
}
