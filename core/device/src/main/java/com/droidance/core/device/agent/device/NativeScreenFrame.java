// SPDX-FileCopyrightText: 2026 Ashin (YBBLab)
// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.nio.ByteBuffer;

/** Native evidence bytes travel out of line; the Binder reply owns both descriptor lifetimes. */
public final class NativeScreenFrame implements Parcelable, AutoCloseable {
    private final SharedMemory memory;
    private final Bundle metadata;
    private boolean closed;

    private NativeScreenFrame(SharedMemory memory, Bundle metadata) {
        this.memory = memory;
        this.metadata = metadata.deepCopy();
    }

    /** An encoded image (JPEG or PNG) shared read-only with the app process. */
    public static NativeScreenFrame fromEncodedImage(byte[] image, Bundle metadata) throws ErrnoException {
        if (image.length == 0) throw new IllegalArgumentException("Empty native frame");
        long started = android.os.SystemClock.elapsedRealtime();
        SharedMemory memory = SharedMemory.create("droidance-native-frame", image.length);
        try {
            ByteBuffer mapped = memory.mapReadWrite();
            try { mapped.put(image); }
            finally { SharedMemory.unmap(mapped); }
            if (!memory.setProtect(OsConstants.PROT_READ))
                throw new IllegalStateException("Native frame cannot be made read-only");
            Bundle facts = metadata.deepCopy();
            facts.putLong("transferMs", android.os.SystemClock.elapsedRealtime() - started);
            return new NativeScreenFrame(memory, facts);
        } catch (Throwable error) {
            memory.close();
            throw error;
        }
    }

    public Bundle getMetadata() { return metadata.deepCopy(); }

    public synchronized byte[] readEncodedImage() throws ErrnoException {
        if (closed) throw new IllegalStateException("Native frame is closed");
        ByteBuffer mapped = memory.mapReadOnly();
        try {
            byte[] image = new byte[mapped.remaining()];
            mapped.get(image);
            return image;
        } finally { SharedMemory.unmap(mapped); }
    }

    @Override public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }

    @Override public synchronized void writeToParcel(Parcel destination, int flags) {
        if (closed) throw new IllegalStateException("Native frame is closed");
        try {
            memory.writeToParcel(destination, flags);
            metadata.writeToParcel(destination, flags);
        } finally {
            // AIDL writes returned objects with this flag. Bundle-nested parcelables lose it.
            if ((flags & PARCELABLE_WRITE_RETURN_VALUE) != 0) close();
        }
    }

    @Override public synchronized void close() {
        if (!closed) { closed = true; memory.close(); }
    }

    public static final Parcelable.Creator<NativeScreenFrame> CREATOR = new Parcelable.Creator<>() {
        @Override public NativeScreenFrame createFromParcel(Parcel source) {
            SharedMemory memory = SharedMemory.CREATOR.createFromParcel(source);
            try { return new NativeScreenFrame(memory, Bundle.CREATOR.createFromParcel(source)); }
            catch (Throwable error) { memory.close(); throw error; }
        }
        @Override public NativeScreenFrame[] newArray(int size) { return new NativeScreenFrame[size]; }
    };
}
