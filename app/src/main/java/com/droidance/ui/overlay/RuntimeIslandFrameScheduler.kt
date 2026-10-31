// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

/** Coalesces input until the next display frame. All calls belong to the input Looper. */
internal class RuntimeIslandFrameScheduler(
    private val requestFrame: () -> Unit,
    private val cancelFrame: () -> Unit,
    private val submitPosition: (Long) -> Unit
) {
    private var pending = false

    fun request() {
        if (pending) return
        pending = true
        requestFrame()
    }

    fun onFrame(vsyncId: Long) {
        if (!pending) return
        pending = false
        submitPosition(vsyncId)
    }

    /** UP commits the latest coordinates immediately, without waiting for another frame. */
    fun flush() {
        if (!pending) return
        cancel()
        submitPosition(NO_FRAME_TIMELINE)
    }

    fun cancel() {
        if (!pending) return
        pending = false
        cancelFrame()
    }

    companion object {
        const val NO_FRAME_TIMELINE = -1L
    }
}
