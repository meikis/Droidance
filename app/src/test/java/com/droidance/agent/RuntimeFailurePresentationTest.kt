// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.R
import com.droidance.core.model.llm.OpenAIResponsesIncompleteException
import com.droidance.core.model.llm.OpenAIResponsesUnterminatedException
import com.droidance.core.model.llm.ProviderHttpException
import com.droidance.core.knowledge.memory.DeferredMemoryEnqueueException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeFailurePresentationTest {
    @Test
    fun runtimeFailuresClassifyEveryTypedPresentationBranch() {
        assertEquals(R.string.execution_cancelled, runtimeFailureMessageResource(CancellationException("stop")))
        assertEquals(
            R.string.memory_background_save_failed,
            runtimeFailureMessageResource(DeferredMemoryEnqueueException(IllegalStateException("write failed")))
        )
        assertEquals(
            R.string.chat_output_limited,
            runtimeFailureMessageResource(OpenAIResponsesIncompleteException("max_output_tokens"))
        )
        assertEquals(
            R.string.execution_model_request_failed,
            runtimeFailureMessageResource(OpenAIResponsesIncompleteException("content_filter"))
        )
        assertEquals(
            R.string.execution_model_invalid_response,
            runtimeFailureMessageResource(OpenAIResponsesUnterminatedException())
        )
        assertEquals(R.string.chat_network_unavailable, runtimeFailureMessageResource(UnknownHostException("host")))
        assertEquals(R.string.chat_network_unavailable, runtimeFailureMessageResource(ConnectException("connect")))
        assertEquals(
            R.string.execution_model_request_timeout,
            runtimeFailureMessageResource(InterruptedIOException("timeout"))
        )
        assertEquals(
            R.string.chat_provider_auth_failed,
            runtimeFailureMessageResource(ProviderHttpException.fromResponse(401, "provider", "{}"))
        )
        assertEquals(
            R.string.chat_provider_permission_denied,
            runtimeFailureMessageResource(ProviderHttpException.fromResponse(403, "provider", "{}"))
        )
        assertEquals(
            R.string.chat_provider_rate_limited,
            runtimeFailureMessageResource(ProviderHttpException.fromResponse(429, "provider", "{}"))
        )
        assertEquals(
            R.string.execution_model_request_failed,
            runtimeFailureMessageResource(ProviderHttpException.fromResponse(500, "provider", "{}"))
        )
        assertEquals(R.string.execution_internal_error, runtimeFailureMessageResource(IllegalStateException("local")))
    }
}
