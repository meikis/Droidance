// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*
import kotlinx.coroutines.ensureActive

/** Each observation owns its budget; no exhausted counter survives a user resume. */
suspend fun <T : Any> captureWithFrameRecovery(
    attempts: Int,
    capture: suspend () -> T?,
    reset: suspend () -> Boolean,
    report: (stage: String, success: Boolean) -> Unit,
    onFailure: (stage: String, error: Exception) -> Unit = { _, _ -> },
    retryDelayMs: Long = 0
): T? {
    require(attempts > 0 && retryDelayMs >= 0)
    suspend fun read(stage: String): T? = try {
        capture()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(stage, error)
        null
    }
    repeat(attempts) { attempt ->
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (attempt > 0) kotlinx.coroutines.delay(retryDelayMs)
        val frame = read("capture")
        report("capture", frame != null)
        if (frame != null) return frame
    }
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    val resetAccepted = try {
        reset()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure("reset", error)
        false
    }
    report("reset", resetAccepted)
    // A reset command receipt alone does not establish frame availability.
    val frame = read("capture_after_reset")
    report("capture_after_reset", frame != null)
    return frame
}
