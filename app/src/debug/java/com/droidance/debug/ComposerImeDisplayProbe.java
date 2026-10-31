// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug;

import com.droidance.core.agent.debug.*;
import com.droidance.core.device.debug.*;

import com.droidance.debug.*;

import android.os.Bundle;
import android.os.Looper;
import com.droidance.core.device.agent.device.SidecarShellContext;
import com.droidance.core.device.agent.device.VirtualSpaceSidecarService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.UUID;

/** Disposable shell-owned display for the production composer IME regression. */
public final class ComposerImeDisplayProbe {
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(SidecarShellContext.create());
        new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> System.exit(2), 60_000);
        new Thread(() -> {
            int exit = 1;
            try {
                int width = Integer.parseInt(args[0]);
                int height = Integer.parseInt(args[1]);
                int density = Integer.parseInt(args[2]);
                if (!service.start(width, height, density)) throw new AssertionError(service.getLastError());
                int display = service.getDisplayId();
                if (args.length > 3) setImePolicy(display, Integer.parseInt(args[3]));
                reply("DISPLAY " + display);
                BufferedReader commands = new BufferedReader(new InputStreamReader(System.in));
                for (String command; (command = commands.readLine()) != null;) {
                    if (command.equals("exit")) break;
                    // The fixture shares the host app's UID. Bring a fresh editor
                    // forward for each handoff rather than assuming its old window
                    // stayed resumed after the host task was brought to the front.
                    shell(service, "am start --display " + display + " -f 0x18000000"
                            + " -n com.droidance.debug/com.droidance.core.device.debug.InputProbeActivity");
                    Bundle focus = service.awaitInputFocus(display, 5_000, UUID.randomUUID().toString());
                    if (focus.getString("target") == null) {
                        shell(service, "dumpsys input_method > /data/local/tmp/droidance-ime-probe-failure.txt");
                        throw new AssertionError("Virtual editor is not connected: " + focus);
                    }
                    if (command.equals("focus")) {
                        reply("FOCUSED");
                    } else if (command.startsWith("text ")) {
                        String text = command.substring(5);
                        Bundle receipt = service.text(display, text, true, null, "shortcut");
                        if (!receipt.getBoolean("eventInjected")) throw new AssertionError(receipt.toString());
                        String actual = shell(service, "run-as com.droidance.debug cat files/input-probe-" + display + ".txt");
                        if (!text.equals(actual)) throw new AssertionError("Virtual editor contents: " + actual);
                        reply("TEXT " + text);
                    } else {
                        throw new AssertionError("Unknown probe command");
                    }
                }
                exit = 0;
            } catch (Throwable error) {
                reply("ERROR " + error);
            } finally {
                service.shutdown();
                reply("CLOSED");
                System.exit(exit);
            }
        }, "composer-ime-display-probe").start();
        Looper.loop();
    }

    private static String shell(VirtualSpaceSidecarService service, String command) {
        Bundle result = service.execShellCommand(command, 15_000, UUID.randomUUID().toString());
        if (result == null || result.getInt("exit_code", -1) != 0) throw new AssertionError("Shell failed: " + result);
        return result.getString("stdout", "");
    }

    private static void setImePolicy(int display, int policy) throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        android.os.IBinder binder = (android.os.IBinder) manager.getDeclaredMethod("getService", String.class)
                .invoke(null, android.content.Context.WINDOW_SERVICE);
        Object windows = Class.forName("android.view.IWindowManager$Stub")
                .getDeclaredMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Class.forName("android.view.IWindowManager").getMethod("setDisplayImePolicy", int.class, int.class)
                .invoke(windows, display, policy);
    }

    private static void reply(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
