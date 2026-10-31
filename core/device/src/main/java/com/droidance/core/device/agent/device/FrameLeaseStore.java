// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import java.util.function.Consumer;

/** Keeps frame ownership valid while encoders run outside the publication lock. */
public final class FrameLeaseStore<T extends AutoCloseable> implements AutoCloseable {
    private final Runnable onDrained;
    private final Consumer<Exception> onCloseFailure;
    private Entry<T> latest;
    private boolean closed;
    private boolean drained;
    private int readers;

    public FrameLeaseStore(Runnable onDrained, Consumer<Exception> onCloseFailure) {
        this.onDrained = onDrained;
        this.onCloseFailure = onCloseFailure;
    }

    public synchronized void publish(T value) {
        if (closed) { closeValue(value); return; }
        Entry<T> previous = latest;
        latest = new Entry<>(value);
        retire(previous);
    }

    public synchronized Lease<T> acquire() {
        if (closed || latest == null) return null;
        latest.readers++;
        readers++;
        return new Lease<>(this, latest);
    }

    public synchronized boolean hasFrame() { return !closed && latest != null; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        retire(latest);
        latest = null;
        drain();
    }

    private void retire(Entry<T> entry) {
        if (entry == null) return;
        entry.retired = true;
        if (entry.readers == 0) closeValue(entry.value);
    }

    private synchronized void release(Entry<T> entry) {
        entry.readers--;
        readers--;
        if (entry.retired && entry.readers == 0) closeValue(entry.value);
        drain();
    }

    private void drain() {
        if (closed && readers == 0 && !drained) {
            drained = true;
            onDrained.run();
        }
    }

    private void closeValue(AutoCloseable value) {
        try { value.close(); } catch (Exception error) { onCloseFailure.accept(error); }
    }

    private static final class Entry<T> {
        final T value;
        int readers;
        boolean retired;
        Entry(T value) { this.value = value; }
    }

    public static final class Lease<T extends AutoCloseable> implements AutoCloseable {
        private final FrameLeaseStore<T> store;
        private Entry<T> entry;
        private Lease(FrameLeaseStore<T> store, Entry<T> entry) { this.store = store; this.entry = entry; }
        public T value() { return entry.value; }
        @Override public synchronized void close() {
            if (entry == null) return;
            store.release(entry);
            entry = null;
        }
    }
}
