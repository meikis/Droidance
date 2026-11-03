// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
interface INativeInputDriver {
    IBinder getWindowSession();
    IBinder startProgram(String instance);
    void closeProgram(String instance);
    ParcelFileDescriptor command(String operation, in Bundle arguments);
}
