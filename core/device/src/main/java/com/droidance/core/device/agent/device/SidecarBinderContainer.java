// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parcelable wrapper used to ship the sidecar Binder from the shell-identity
 * app_process server to the app process via a broadcast Intent. This avoids
 * ServiceManager.addService, which SELinux rejects for custom service names.
 */
public final class SidecarBinderContainer implements Parcelable {
    private final IBinder binder;

    public SidecarBinderContainer(IBinder binder) {
        this.binder = binder;
    }

    private SidecarBinderContainer(Parcel in) {
        this.binder = in.readStrongBinder();
    }

    public IBinder getBinder() {
        return binder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(binder);
    }

    public static final Creator<SidecarBinderContainer> CREATOR = new Creator<SidecarBinderContainer>() {
        @Override
        public SidecarBinderContainer createFromParcel(Parcel in) {
            return new SidecarBinderContainer(in);
        }

        @Override
        public SidecarBinderContainer[] newArray(int size) {
            return new SidecarBinderContainer[size];
        }
    };
}
