// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.context.ContextCompactionOutputLimit
import com.droidance.core.model.llm.OpenAIResponsesIncompleteException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCompactionFailureTest {
    @Test
    fun exhaustedReportedUsageIsAnOutputLimitEvenWithoutAReason() {
        val failure = OpenAIResponsesIncompleteException(null, 2048, 2048)
        val classified = compactionResponseFailure(failure)
        assertTrue(classified is ContextCompactionOutputLimit)
        assertSame(failure, classified.cause)
    }

    @Test
    fun unrelatedFailuresAreNotTurnedIntoOutputRetries() {
        for (failure in listOf(
            OpenAIResponsesIncompleteException("content_filter", 2048, 2048),
            OpenAIResponsesIncompleteException(null, 100, 2048),
            IllegalStateException("invalid evidence"),
            CancellationException("user canceled")
        )) {
            assertSame(failure, compactionResponseFailure(failure))
        }
    }
}
