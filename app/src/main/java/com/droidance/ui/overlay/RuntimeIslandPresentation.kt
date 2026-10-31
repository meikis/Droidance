// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Context
import androidx.core.content.ContextCompat
import com.droidance.R

/** Localized island states; the fixed task title is supplied once by the existing Agent. */
object RuntimeIslandPresentation {
    enum class State { EXECUTING, WAITING_FOR_USER, USER_CONTROL, WAITING_PERMISSION, READY, DEMONSTRATING }

    fun label(context: Context, state: State, taskTitle: String = ""): String = when (state) {
        State.WAITING_FOR_USER -> ContextCompat.getString(context, R.string.runtime_island_takeover_requested)
        State.USER_CONTROL -> ContextCompat.getString(context, R.string.runtime_island_user_control)
        State.WAITING_PERMISSION -> ContextCompat.getString(context, R.string.runtime_island_permission)
        State.READY -> ContextCompat.getString(context, R.string.runtime_island_ready)
        State.DEMONSTRATING -> ContextCompat.getString(context, R.string.runtime_island_demonstrating)
        State.EXECUTING -> taskTitle.trim().ifBlank { ContextCompat.getString(context, R.string.takeover_viewer_running) }
    }
}
