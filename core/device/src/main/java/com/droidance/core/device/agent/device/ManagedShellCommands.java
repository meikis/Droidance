// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.system.Os;
import android.system.OsConstants;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Owns ordinary shell descendants as a process group. This does not sandbox privileged code. */
public final class ManagedShellCommands {
    public static final class Result {
        public final int exitCode;
        public final String stdout, stderr;
        public String executionStatus, diagnosticCode;
        public boolean stdoutTruncated, stderrTruncated;
        public long stdoutBytes, stderrBytes;
        public final SystemQueryOutput.Result systemOutput;
        Result(int code, String out, String err) { this(code, out, err, null); }
        Result(int code, String out, String err, SystemQueryOutput.Result selected) {
            exitCode=code; stdout=out; stderr=err; systemOutput=selected;
            executionStatus=code==0 ? "SUCCEEDED" : "FAILED";
            stdoutBytes=out.getBytes(StandardCharsets.UTF_8).length; stderrBytes=err.getBytes(StandardCharsets.UTF_8).length;
        }
    }
    private static final class Slot {
        volatile boolean canceled;
        volatile boolean timedOut;
        volatile java.lang.Process process;
        volatile int group;
        volatile boolean dispatched;
        final long created = android.os.SystemClock.elapsedRealtime();
    }
    private final java.util.concurrent.ScheduledExecutorService deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"shell-deadline");thread.setDaemon(true);return thread;
    });
    private final Map<String,Slot> slots = new ConcurrentHashMap<>();
    public void cancel(String id) {
        if (id == null || id.length() > 64) return;
        Slot slot = slots.computeIfAbsent(id, ignored -> new Slot());
        synchronized (slot) { slot.canceled = true; kill(slot); }
    }
    public Result execute(String id, String command, long timeoutMs) {
        return execute(id, command, timeoutMs, null, 0, null);
    }
    public Result executeSystem(String id, SystemStateQuery request, long timeoutMs) {
        return execute(id, request.getCommand(), timeoutMs, request.getLineFilter(), request.getContextLines(), null);
    }
    public Result executeInputFocus(String id, long timeoutMs) {
        return execute(id, "dumpsys input", timeoutMs, null, 0, InputWindowFocusParser::requiredLine);
    }
    private Result execute(String id, String command, long timeoutMs, String filter, int context,
                           java.util.function.Predicate<String> selector) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) return status(new Result(-1,"",""), "NOT_EXECUTED", "INVALID_EXECUTION_ID");
        long now=android.os.SystemClock.elapsedRealtime();
        slots.entrySet().removeIf(entry -> entry.getValue().process == null && now-entry.getValue().created>300000);
        Slot slot=slots.computeIfAbsent(id, ignored -> new Slot());
        String[] output={""}, error={""};
        BoundedShellOutput.Result[] outRead={null}, errRead={null};
        SystemQueryOutput.Result[] selected={null};
        String[] selectionError={null};
        Thread out=null, err=null;
        java.util.concurrent.ScheduledFuture<?> deadline=deadlines.schedule(() -> {
            synchronized(slot) { slot.timedOut=true;slot.canceled=true;kill(slot); }
        }, Math.max(1,Math.min(120000,timeoutMs)),TimeUnit.MILLISECONDS);
        try {
            synchronized(slot) {
                if(slot.canceled || slot.process!=null)return status(new Result(-1,"",""), "NOT_EXECUTED", "SHELL_REVOKED");
                // The user command cannot start before its process group is registered by the host.
                String wrapper="printf '%s\\n' \"$$\"; read -r ready; [ \"$ready\" = '"+id+"' ] || exit 125;\n"+command;
                slot.process=new ProcessBuilder("/system/bin/setsid","/system/bin/sh","-c",wrapper).start();
            }
            String pid=readHeader(slot.process.getInputStream());
            synchronized(slot) {
                slot.group=Integer.parseInt(pid);
                if(slot.group<=1 || slot.canceled) { kill(slot); return status(new Result(-1,"",""), "NOT_EXECUTED", "SHELL_REVOKED"); }
                slot.process.getOutputStream().write((id+"\n").getBytes(StandardCharsets.UTF_8));
                slot.process.getOutputStream().flush();
                slot.dispatched=true;
                slot.process.getOutputStream().close();
            }
            out=new Thread(() -> {
                try {
                    if (selector != null) { selected[0]=SystemQueryOutput.readSelected(slot.process.getInputStream(), selector); output[0]=selected[0].stdout; }
                    else if (filter == null) { outRead[0]=BoundedShellOutput.read(slot.process.getInputStream()); output[0]=outRead[0].text; }
                    else { selected[0]=SystemQueryOutput.read(slot.process.getInputStream(), filter, context); output[0]=selected[0].stdout; }
                } catch(Exception failure) { selectionError[0]="SYSTEM_QUERY_READ_FAILED"; }
            },"shell-out");
            err=new Thread(() -> { try { errRead[0]=BoundedShellOutput.read(slot.process.getErrorStream()); error[0]=errRead[0].text; } catch(Exception ignored){} },"shell-err");
            out.start(); err.start();
            boolean finished=slot.process.waitFor(Math.max(1,Math.min(120000,timeoutMs)),TimeUnit.MILLISECONDS);
            int exit=finished ? slot.process.exitValue() : -1;
            synchronized(slot) { kill(slot); }
            out.join(1000); err.join(1000);
            boolean selectionFailed=(filter != null || selector != null) && (selected[0] == null || selectionError[0] != null);
            Result result=new Result(slot.canceled || selectionFailed ? -1 : exit,output[0],error[0],selected[0]);
            if(outRead[0]!=null) { result.stdoutTruncated=outRead[0].truncated; result.stdoutBytes=outRead[0].originalBytes; }
            if(errRead[0]!=null) { result.stderrTruncated=errRead[0].truncated; result.stderrBytes=errRead[0].originalBytes; }
            if(selected[0]!=null)result.stdoutTruncated=selected[0].truncated;
            if(slot.timedOut || slot.canceled || !finished) return status(result,
                slot.timedOut || !finished ? "INTERRUPTED" : "CANCELED",
                slot.timedOut || !finished ? "SHELL_TIMEOUT_EFFECT_UNKNOWN" : "SHELL_CANCELED_EFFECT_UNKNOWN");
            if(selectionFailed || (filter==null && selector==null && outRead[0]==null) || errRead[0]==null)
                return status(result,"INTERRUPTED","SHELL_OUTPUT_UNAVAILABLE");
            return status(result, exit==0 ? "SUCCEEDED" : "FAILED", exit==0 ? null : "SHELL_EXIT_NONZERO");
        } catch(Exception failure) {
            return status(new Result(-1,output[0],error[0]), slot.dispatched ? "INTERRUPTED" : "NOT_EXECUTED",
                slot.dispatched ? "SHELL_EFFECT_UNKNOWN" : "SHELL_START_FAILED");
        } finally {
            deadline.cancel(false);
            synchronized(slot) { kill(slot); slot.process=null; slot.canceled=true; }
            // Keep a short tombstone: late dispatch/cancel must never restart this identity.
        }
    }
    private static Result status(Result result, String state, String code) {
        result.executionStatus=state; result.diagnosticCode=code; return result;
    }
    private static String readHeader(InputStream input) throws Exception {
        StringBuilder value=new StringBuilder();
        for(int i=0;i<16;i++) { int next=input.read(); if(next=='\n')return value.toString(); if(next<0)break; value.append((char)next); }
        throw new IllegalStateException("SHELL_START_HANDSHAKE_FAILED");
    }
    private static void kill(Slot slot) {
        if(slot.group>1) { try { Os.kill(-slot.group,OsConstants.SIGKILL); } catch(Exception ignored){} }
        if(slot.process!=null)slot.process.destroyForcibly();
        slot.group=0;
    }
}
