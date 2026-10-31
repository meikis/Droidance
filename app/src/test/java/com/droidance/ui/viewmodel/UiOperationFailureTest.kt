// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.R
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class UiOperationFailureTest {
    @Test fun failuresKeepDistinctCausesAndRedactRecoveryDiagnostics() {
        assertEquals(R.string.ui_input_rejected, UiOperationFailure.from(IllegalArgumentException("invalid document")).messageRes)
        assertEquals(R.string.ui_state_changed, UiOperationFailure.from(IllegalStateException("SKILL_REVISION_CHANGED")).messageRes)
        assertEquals(R.string.ui_permission_required, UiOperationFailure.from(SecurityException()).messageRes)
        assertEquals(R.string.ui_io_failed, UiOperationFailure.from(IOException("disk full")).messageRes)
        assertEquals(R.string.chat_network_unavailable, UiOperationFailure.from(UnknownHostException()).messageRes)
        assertEquals(R.string.chat_output_limited, UiOperationFailure.from(
            com.droidance.core.model.llm.OpenAIResponsesIncompleteException("max_output_tokens")).messageRes)
        val failure = UiOperationFailure.from(IllegalArgumentException("password=private-value; SKILL_REVISION_CHANGED"))
        assertFalse(failure.diagnostic.contains("private-value"))
        assertTrue(failure.diagnostic.contains("SKILL_REVISION_CHANGED"))
    }

    @Test(expected = CancellationException::class)
    fun cancellationDoesNotBecomeAUserFacingFailure() {
        UiOperationFailure.from(CancellationException("screen left"))
    }
}
