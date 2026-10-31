// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.program;
import com.droidance.core.device.program.IProgramHost;
interface IProgramWorker {
    String identity();
    oneway void run(String code, long timeoutMs, IProgramHost host);
    oneway void stop();
}
