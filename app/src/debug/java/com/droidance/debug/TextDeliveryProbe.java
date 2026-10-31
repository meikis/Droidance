// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug;

import com.droidance.core.agent.debug.*;
import com.droidance.core.device.debug.*;

import com.droidance.debug.*;

import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import com.droidance.core.device.agent.device.VirtualSpaceSidecarService;

/** Shell entry point for replacement/append checks on a disposable virtual display. */
public final class TextDeliveryProbe {
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(
                com.droidance.core.device.agent.device.SidecarShellContext.create());
        new Thread(() -> {
            try {
                System.out.println(service.execShellCommand("am start --display 0 -f 0x18000000"
                        + " -n com.droidance.debug/com.droidance.core.device.debug.InputProbeActivity --es initial_text physical-sentinel", 15000, java.util.UUID.randomUUID().toString()));
                SystemClock.sleep(700);
                if (!service.start(1080, 2376, 480)) throw new AssertionError(service.getLastError());
                int display = service.getDisplayId();
                if (!service.ensureDisplayInteractive(display)) throw new AssertionError(service.getLastError());
                System.out.println(service.execShellCommand("am start --display " + display
                        + " -f 0x18000000 -n com.droidance.debug/com.droidance.core.device.debug.InputProbeActivity", 15000, java.util.UUID.randomUUID().toString()));
                SystemClock.sleep(1200);
                service.tap(display, 540, 1188);
                System.out.println(service.execShellCommand("input -d 0 tap 540 1188", 15000, java.util.UUID.randomUUID().toString()));
                SystemClock.sleep(400);
                Bundle readiness = service.awaitInputFocus(display, 3000, java.util.UUID.randomUUID().toString());
                System.out.println("FOCUS_READINESS " + readiness);
                String focus = readiness.getString("target");
                if (focus == null || !focus.contains(";window_id=")) throw new AssertionError("Missing editor identity: " + focus + " display=" + display);
                Bundle stale = service.text(display, "must-not-arrive", true, focus + "-stale", "shortcut");
                if (stale.getBoolean("eventInjected")) throw new AssertionError("Stale window accepted input");
                Bundle replace = service.text(display, "验证甲", true, focus, "shortcut");
                assertText(service, display, "验证甲");
                if (!replace.getBoolean("eventInjected")
                        || !replace.getString("targetBefore", "").equals(replace.getString("targetAfter"))) {
                    throw new AssertionError("Replacement lost its editor binding: " + replace);
                }
                Bundle append = service.text(display, "乙", false, focus, "shortcut");
                assertText(service, display, "验证甲乙");
                if (append.getBoolean("destinationTextMatched")) throw new AssertionError("Append borrowed replacement evidence");
                service.text(display, "English 123 中文", true, focus, "shortcut");
                assertText(service, display, "English 123 中文");
                service.text(display, "15000", true, focus, "shortcut");
                assertText(service, display, "15000");
                service.text(display, "16000", true, focus, "shortcut");
                assertText(service, display, "16000");
                service.text(display, "A💟／B－C→D", true, focus, "shortcut");
                assertText(service, display, "A💟／B－C→D");
                Bundle pasteKey = service.text(display, "Paste key 中文 💟", true, focus, "paste_key");
                assertText(service, display, "Paste key 中文 💟");
                if (!pasteKey.getBoolean("eventInjected")) throw new AssertionError("Paste key not dispatched");
                service.text(display, "追加", false, focus, "paste_key");
                assertText(service, display, "Paste key 中文 💟追加");
                service.text(display, "", true, focus, "shortcut");
                assertText(service, display, "");
                assertText(service, 0, "physical-sentinel");
                if (service.text(999999, "wrong-display", true, null, "shortcut").getBoolean("eventInjected"))
                    throw new AssertionError("Retired display accepted text");
                System.out.println("PASS display=" + display + " replacement_exact=true append_exact=true"
                        + " clear_exact=true numeric_exact=true unicode_exact=true paste_key_replace_exact=true paste_key_append_exact=true stale_window_rejected=true physical_editor_unchanged=true retired_display_rejected=true"
                        + " runtime_readback_verified=" + replace.getBoolean("destinationTextMatched")
                        + " readback_ms=" + replace.getLong("readbackDurationMs")
                        + " injection_ms=" + replace.getLong("injectionDurationMs"));
                service.destroy();
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                service.destroy();
                System.exit(1);
            }
        }, "text-delivery-probe").start();
        Looper.loop();
    }

    private static void assertText(VirtualSpaceSidecarService service, int display, String expected) {
        SystemClock.sleep(600);
        Bundle reply = service.execShellCommand("run-as com.droidance.debug cat files/input-probe-" + display + ".txt", 15000, java.util.UUID.randomUUID().toString());
        if (reply == null || reply.getInt("exit_code") != 0 || !expected.equals(reply.getString("stdout"))) {
            throw new AssertionError("Editor contents mismatch: " + (reply == null ? "null" : "exit=" + reply.getInt("exit_code") + " stdout=" + reply.getString("stdout")));
        }
    }
}
