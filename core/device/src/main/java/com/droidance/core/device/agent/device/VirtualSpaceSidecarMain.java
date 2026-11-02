// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

/**
 * app_process entrypoint for the Standard shell sidecar.
 */
public final class VirtualSpaceSidecarMain {
    private static final String TAG = "DroidanceVSpace";
    private static final String MAIN_CLASS_NAME = "com.droidance.core.device.agent.device.VirtualSpaceSidecarMain";
    private static final String PROTOCOL_ARGUMENT_PREFIX = "droidance-sidecar-protocol=";
    private static final String SOURCE_ARGUMENT_PREFIX = "droidance-sidecar-source=";
    private static final int SHELL_UID = 2000;
    private static final long STALE_PROCESS_EXIT_WAIT_MS = 100L;
    private static final int STALE_PROCESS_EXIT_RETRIES = 20;
    private static final long SCREEN_OFF_APP_PROBE_MS = 2000L;
    private static java.io.RandomAccessFile leaseFile;
    private static java.nio.channels.FileLock leaseLock;

    private VirtualSpaceSidecarMain() {}

    public static void main(String[] args) {
        try {
            ensureSupportedIdentity();
            writeLog("INFO main entered, uid=" + Process.myUid() + " args=" + args.length, null);
            String codeSource = System.getProperty("java.class.path", "");
            if (args.length != 6 || parseProtocolVersion(args) != VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION
                    || codeSource.isEmpty() || !args[5].equals(SOURCE_ARGUMENT_PREFIX + codeSource)) {
                reportFailure("missing sidecar display args", null);
                return;
            }
            int width = parseInt(args[0], 1);
            int height = parseInt(args[1], 1);
            int densityDpi = parseInt(args[2], 160);
            String targetPackage = args[3];
            if (!acquireProcessLease()) {
                int protocolVersion = parseProtocolVersion(args);
                java.util.List<Integer> stalePids = replaceStaleSidecarProcesses(
                        protocolVersion, codeSource
                );
                if (!stalePids.isEmpty() && acquireProcessLeaseWithRetry()) {
                    reportInfo(
                            "replaced stale sidecar pids=" + stalePids +
                                    " protocol=" + protocolVersion +
                                    " role=" + "virtual"
                    );
                } else {
                    reportInfo("duplicate sidecar skipped for role=" + "virtual");
                    return;
                }
            }
            Looper.prepareMainLooper();
            writeLog("INFO looper prepared", null);
            Context sidecarContext = SidecarShellContext.create();
            writeLog("INFO sidecar context created pkg=" + sidecarContext.getPackageName(), null);
            VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(sidecarContext);
            writeLog("INFO service created", null);
            registerScreenLifecycle(sidecarContext, service, targetPackage);
            boolean broadcastOk = service.broadcastBinder(sidecarContext, targetPackage);
            writeLog("INFO broadcastBinder returned=" + broadcastOk + " err=" + service.getLastError(), null);
            if (!broadcastOk) {
                reportFailure("broadcastBinder failed: " + service.getLastError(), null);
                return;
            }
            reportInfo("sidecar binder broadcast sent");
            // 持续重发 binder 广播：防 app receiver 第一次未就绪而丢失 binder，
            // 让 app 在轮询窗口内总能收到一个存活的 binder。
            startRebroadcast(service, sidecarContext, targetPackage);
            Looper.loop();
        } catch (Throwable t) {
            reportFailure(t.getClass().getSimpleName() + ": " + safeMessage(t), t);
        }
    }

    private static void registerScreenLifecycle(
            Context context,
            VirtualSpaceSidecarService service,
            String targetPackage
    ) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    service.onScreenLocked();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    service.onScreenUnlocked();
                    service.broadcastBinder(receiverContext, targetPackage);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    KeyguardManager keyguard = (KeyguardManager) receiverContext.getSystemService(Context.KEYGUARD_SERVICE);
                    if (keyguard != null && keyguard.isKeyguardLocked()) {
                        service.onScreenLocked();
                    } else {
                        service.onScreenUnlocked();
                    }
                    service.broadcastBinder(receiverContext, targetPackage);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        writeLog("INFO screen lifecycle receiver registered", null);
    }

    private static void ensureSupportedIdentity() {
        int uid = Process.myUid();
        if ((uid != SHELL_UID)
                || (uid == SHELL_UID && Os.getgid() != SHELL_UID)) {
            throw new IllegalStateException(
                    "unsupported sidecar identity: uid=" + uid + " gid=" + Os.getgid());
        }
    }

    /**
     * Keep one long-lived app_process for the shell UID. The Android app can be
     * restarted while a resident sidecar deliberately survives; without a process-level lease
     * the restarted app starts another broadcaster whenever the old Binder is not yet in its
     * registry. File locks are released by the kernel when the sidecar exits, so no stale cleanup
     * job or app-specific process lookup is needed.
     */
    private static boolean acquireProcessLease() {
        String role = "virtual";
        String path = "/data/local/tmp/droidance_vspace_" + Process.myUid() + "_" + role + ".lock";
        try {
            leaseFile = new java.io.RandomAccessFile(path, "rw");
            leaseLock = leaseFile.getChannel().tryLock();
            if (leaseLock != null) return true;
            leaseFile.close();
            leaseFile = null;
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            closeProcessLease();
            return false;
        } catch (Throwable t) {
            closeProcessLease();
            reportFailure("sidecar process lease unavailable: " + safeMessage(t), t);
            return false;
        }
    }

    /**
     * Replaces a resident process from another APK or protocol before the new process
     * gives up on its lease.  This is intentionally done inside app_process: after the Android
     * app is restarted the AIR shell may not be ready yet, while a same-UID sidecar can still
     * safely terminate its stale sibling without relying on the app Binder registry.
     */
    private static java.util.List<Integer> replaceStaleSidecarProcesses(
            int protocolVersion, String codeSource
    ) {
        java.util.List<Integer> stalePids = new java.util.ArrayList<>();
        java.lang.Process probe = null;
        try {
            probe = new ProcessBuilder(
                    "/system/bin/ps",
                    "-A",
                    "-o",
                    "UID,PID,ARGS"
            ).redirectErrorStream(true).start();
            if (!probe.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return stalePids;
            }
            String output = new String(
                    probe.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            );
            for (String line : output.split("\\r?\\n")) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 5 || !isSameSidecarIdentity(fields[0])) continue;
                int pid = parsePositiveInt(fields[1]);
                if (pid <= 1 || pid == android.os.Process.myPid()) continue;
                if (!MAIN_CLASS_NAME.equals(fields[4])) continue;
                if (isCurrentBuildCommand(line, protocolVersion, codeSource)) continue;
                if (android.os.Process.myUid() == SHELL_UID) {
                    killProcessGroup(pid);
                    stalePids.add(pid);
                }
            }
        } catch (Throwable t) {
            writeLog("WARN stale sidecar probe failed: " + safeMessage(t), t);
        } finally {
            if (probe != null) {
                try {
                    probe.destroy();
                } catch (Throwable error) {
                    writeLog("WARN stale sidecar process cleanup failed: " + error.getClass().getSimpleName(), null);
                }
            }
        }
        return stalePids;
    }

    /** A stale supervisor must stop with its sidecar, or it would relaunch the previous build. */
    private static void killProcessGroup(int pid) {
        try {
            int group = processGroupOf(Integer.toString(pid));
            if (group > 1 && group != processGroupOf("self")) Os.kill(-group, OsConstants.SIGKILL);
        } catch (ErrnoException | java.io.IOException | RuntimeException e) {
            writeLog("WARN stale sidecar group kill failed: " + safeMessage(e), null);
        }
        android.os.Process.killProcess(pid);
    }

    private static int processGroupOf(String process) throws java.io.IOException {
        String stat = new String(
                java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/" + process + "/stat")),
                java.nio.charset.StandardCharsets.UTF_8
        );
        // Fields after the parenthesized name: state, ppid, pgrp.
        return Integer.parseInt(stat.substring(stat.lastIndexOf(')') + 2).split(" ")[2]);
    }

    static boolean isCurrentBuildCommand(String commandLine, int protocolVersion, String codeSource) {
        if (protocolVersion <= 0 || codeSource.isEmpty() || parseProtocolVersion(commandLine) != protocolVersion) return false;
        for (String field : commandLine.trim().split("\\s+")) {
            if (field.equals(SOURCE_ARGUMENT_PREFIX + codeSource)) return true;
        }
        return false;
    }

    private static boolean acquireProcessLeaseWithRetry() {
        for (int i = 0; i < STALE_PROCESS_EXIT_RETRIES; i++) {
            if (acquireProcessLease()) return true;
            try {
                Thread.sleep(STALE_PROCESS_EXIT_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean isSameSidecarIdentity(String uidField) {
        int uid = parsePositiveInt(uidField);
        return uid == android.os.Process.myUid() &&
                (uid == SHELL_UID);
    }

    private static int parseProtocolVersion(String commandLine) {
        for (String field : commandLine.trim().split("\\s+")) {
            if (!field.startsWith(PROTOCOL_ARGUMENT_PREFIX)) continue;
            return parsePositiveInt(field.substring(PROTOCOL_ARGUMENT_PREFIX.length()));
        }
        return 0;
    }

    private static int parseProtocolVersion(String[] args) {
        if (args == null) return 0;
        for (String arg : args) {
            if (arg == null || !arg.startsWith(PROTOCOL_ARGUMENT_PREFIX)) continue;
            return parsePositiveInt(arg.substring(PROTOCOL_ARGUMENT_PREFIX.length()));
        }
        return 0;
    }

    private static int parsePositiveInt(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void closeProcessLease() {
        try {
            if (leaseLock != null) leaseLock.release();
        } catch (Throwable t) {
            Log.w(TAG, "sidecar lease release failed", t);
        }
        try {
            if (leaseFile != null) leaseFile.close();
        } catch (Throwable t) {
            Log.w(TAG, "sidecar lease file close failed", t);
        }
        leaseLock = null;
        leaseFile = null;
    }

    private static int readAppPid(String targetPackage) {
        java.lang.Process probe = null;
        try {
            probe = new ProcessBuilder("/system/bin/pidof", targetPackage).redirectErrorStream(true).start();
            if (!probe.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) return 0;
            String output = new String(
                    probe.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            ).trim();
            return output.isEmpty() ? 0 : parsePositiveInt(output.split("\\s+")[0]);
        } catch (Throwable t) {
            writeLog("WARN app pid probe failed: " + safeMessage(t), null);
            return 0;
        } finally {
            if (probe != null) probe.destroyForcibly();
        }
    }

    private static void startRebroadcast(
            VirtualSpaceSidecarService service,
            Context ctx,
            String targetPackage
    ) {
        Thread t = new Thread(() -> {
            // 前 15 秒快频(1s)吸收 app receiver 首次未就绪，之后转慢频(5s)长驻。
            // 关键 WiFi 无关性：sidecar 用 setsid 长活，app 进程重启后错过首轮广播也能在数秒内重新收到
            // 一个存活的 binder，无需再经 ADB/无线调试(WiFi 关也成立)。广播是本机 IPC，与网络无关。
            int i = 0;
            PowerManager powerManager = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            int observedAppPid = -1;
            while (true) {
                boolean interactive = powerManager == null || powerManager.isInteractive();
                long sleepMs = i < 15 ? 1000L : interactive ? 5000L : SCREEN_OFF_APP_PROBE_MS;
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    return;
                }
                // 熄屏时不向挂起的目标应用发送周期广播，防止 OEM 功耗冻结管理器代理队列打满(如 ColorOS bcMax)强制唤醒
                // 但无 ADB 时重启后的 app 只能等这里：熄屏时 app 进程号变化才补发一次，不走周期广播。
                if (powerManager != null && !powerManager.isInteractive()) {
                    int appPid = readAppPid(targetPackage);
                    boolean restarted = observedAppPid >= 0 && appPid > 0 && appPid != observedAppPid;
                    observedAppPid = appPid;
                    if (!restarted) continue;
                } else {
                    observedAppPid = -1;
                }
                try {
                    service.broadcastBinder(ctx, targetPackage);
                } catch (Throwable t1) {
                    writeLog("WARN rebroadcast failed: " + safeMessage(t1), t1);
                }
                i++;
            }
        }, "DroidanceVSpaceRebroadcast");
        t.setDaemon(true);
        t.start();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Math.max(Integer.parseInt(raw), 0);
        } catch (Throwable t) {
            writeLog("WARN bad integer arg: " + raw + ", " + safeMessage(t), t);
            return fallback;
        }
    }

    private static java.io.PrintWriter logWriter;

    /** 直接写文件(像 aladdin)，不依赖 stdout 重定向——realme app_process 的 System.out 走 logcat 不进 >log。 */
    private static synchronized void writeLog(String line, Throwable t) {
        try {
            if (logWriter == null) {
                logWriter = new java.io.PrintWriter(
                    new java.io.FileWriter("/data/local/tmp/droidance_vspace.log", true), true);
            }
            logWriter.println(System.currentTimeMillis() + " " + line);
            if (t != null) t.printStackTrace(logWriter);
        } catch (Throwable t1) {
            Log.w(TAG, "sidecar file log failed: " + safeMessage(t1));
        }
    }

    private static void reportFailure(String message, Throwable t) {
        String detail = message == null || message.isEmpty() ? "unknown sidecar failure" : message;
        Log.e(TAG, detail, t);
        writeLog("FAIL " + detail, t);
    }

    private static void reportInfo(String message) {
        Log.i(TAG, message);
        writeLog("INFO " + message, null);
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "" : message;
    }
}
