// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/** Each observation owns its budget; no exhausted counter survives a user resume. */
suspend fun <T : Any> captureWithFrameRecovery(
    attempts: Int,
    capture: suspend () -> T?,
    reset: suspend () -> Boolean,
    report: (stage: String, success: Boolean) -> Unit
): T? {
    require(attempts > 0)
    repeat(attempts) {
        val frame = capture()
        report("capture", frame != null)
        if (frame != null) return frame
    }
    val resetAccepted = reset()
    report("reset", resetAccepted)
    // A reset command receipt alone does not establish frame availability.
    val frame = capture()
    report("capture_after_reset", frame != null)
    return frame
}
