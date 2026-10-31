// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.program;
interface IProgramHost {
    String invoke(String operation, String arguments);
    void finished(String status, String error);
}
