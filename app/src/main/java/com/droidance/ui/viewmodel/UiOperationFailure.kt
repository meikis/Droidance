// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import androidx.annotation.StringRes
import com.droidance.R
import com.droidance.agent.runtimeFailureMessageResource
import com.droidance.core.knowledge.memory.MemorySecretRedactor
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Presentation facts only. Recovery decisions remain with the conversation Agent. */
data class UiOperationFailure(@StringRes val messageRes: Int, val diagnostic: String) {
    companion object {
        fun from(error: Throwable): UiOperationFailure {
            if (error is CancellationException) throw error
            val runtimeMessage = runtimeFailureMessageResource(error)
            val message = when {
                runtimeMessage != R.string.execution_internal_error -> runtimeMessage
                error is SecurityException -> R.string.ui_permission_required
                error is IllegalArgumentException -> R.string.ui_input_rejected
                error is IllegalStateException -> R.string.ui_state_changed
                error is IOException -> R.string.ui_io_failed
                else -> runtimeMessage
            }
            return UiOperationFailure(message,
                MemorySecretRedactor.redact("${error.javaClass.simpleName}: ${error.message.orEmpty()}").take(2000))
        }
    }
}
