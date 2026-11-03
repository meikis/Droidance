// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

/** An ordinary app process is a client. Only the paired shell process can own execution. */
object RuntimeProcessRole {
    val isOwner: Boolean get() = android.os.Process.myUid() == 2000
}

class RuntimeUnavailableException(cause: Throwable? = null) : IllegalStateException("Resident task runtime is unavailable", cause)
