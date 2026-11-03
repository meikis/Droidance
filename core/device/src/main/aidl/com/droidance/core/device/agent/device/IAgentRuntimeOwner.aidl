// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** The resident process owns task execution and persistence; clients carry commands and snapshots. */
interface IAgentRuntimeOwner {
    ParcelFileDescriptor command(String operation, in Bundle arguments);
    ParcelFileDescriptor state();
    ParcelFileDescriptor files();
    ParcelFileDescriptor blob(String operation, String contentRef, long maxBytes, in ParcelFileDescriptor input);
}
