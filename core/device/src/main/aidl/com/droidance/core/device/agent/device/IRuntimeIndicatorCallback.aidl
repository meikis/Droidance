// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

oneway interface IRuntimeIndicatorCallback {
    void onRuntimeIndicatorTapped();
    void onRuntimeStopRequested();
}
