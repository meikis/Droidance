// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.model.llm.ProviderHttpException
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFailureNoticeTest {
    @Test fun transportFailuresRetainTheirOwnCategoryWithoutCopyingPrivateProviderText() {
        assertEquals("termination_model_rate_limit", modelFailureNoticeKey(
            ProviderHttpException.fromResponse(429, "private-provider", "private-body")))
        assertEquals("termination_model_unavailable", modelFailureNoticeKey(
            ProviderHttpException.fromResponse(503, "private-provider", "private-body")))
        assertEquals("termination_model_network", modelFailureNoticeKey(
            IllegalStateException("wrapper", java.net.SocketException("private-address"))))
        assertEquals("termination_model_response_invalid", modelFailureNoticeKey(
            kotlinx.serialization.SerializationException("private-response")))
    }
}
