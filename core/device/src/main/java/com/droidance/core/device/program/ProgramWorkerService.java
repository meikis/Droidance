// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.program;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dedicated isolated UID: no application storage, network permissions, or privileged Binder handles. */
public final class ProgramWorkerService extends Service {
    private final AtomicBoolean started = new AtomicBoolean();
    private final IProgramWorker.Stub binder = new IProgramWorker.Stub() {
        @Override public String identity() { return "{\"isolated\":" + Process.isIsolated() + ",\"uid\":" + Process.myUid() + "}"; }
        @Override public void run(String code, long timeoutMs, IProgramHost host) {
            if (!Process.isIsolated() || !started.compareAndSet(false, true)) { Process.killProcess(Process.myPid()); return; }
            if (code == null || code.length() > 128 * 1024 || timeoutMs < 1 || timeoutMs > 300000) {
                Process.killProcess(Process.myPid()); return;
            }
            try { host.asBinder().linkToDeath(() -> Process.killProcess(Process.myPid()), 0); }
            catch (Exception error) { Process.killProcess(Process.myPid()); return; }
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            new Thread(() -> {
                try {
                    new ProgramEngine().execute(code, host::invoke,
                        () -> android.os.SystemClock.elapsedRealtime() >= deadline);
                    host.finished("exited", "");
                } catch (Throwable error) {
                    try {
                        String detail = ProgramEngine.formatError(error);
                        if (detail.length() > 2000) detail = detail.substring(0, 2000);
                        host.finished(error instanceof ProgramEngine.Interrupted ? "timed_out" : "failed", detail);
                    } catch (Exception ignored) { /* Host death revokes the worker. */ }
                }
            }, "program-evaluation").start();
            // Native regex/string work may not reach the interpreter instruction counter.
            new Thread(() -> {
                try { Thread.sleep(timeoutMs + 1000); } catch (InterruptedException ignored) { }
                Process.killProcess(Process.myPid());
            }, "program-deadline").start();
        }
        @Override public void stop() { Process.killProcess(Process.myPid()); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) { Process.killProcess(Process.myPid()); return false; }
}
