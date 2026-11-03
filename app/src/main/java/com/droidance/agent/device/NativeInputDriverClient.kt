// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.agent.device

import android.os.Bundle
import com.droidance.core.device.agent.device.RuntimePlatformDriver
import com.droidance.core.device.agent.device.RuntimeWire
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeInputDriverClient @Inject constructor() {
    fun command(operation: String, arguments: Bundle = Bundle()): Bundle =
        RuntimeWire.read(RuntimePlatformDriver.connect().command(operation, arguments))
}
