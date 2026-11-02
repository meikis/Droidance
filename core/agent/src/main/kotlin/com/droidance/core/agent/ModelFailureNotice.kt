// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.model.llm.ProviderErrorCategory
import com.droidance.core.model.llm.providerHttpException

/** Classifies transport facts only; provider messages and endpoints never become public text. */
internal fun modelFailureNoticeKey(error: Throwable): String = when (error.providerHttpException()?.category) {
    ProviderErrorCategory.BILLING -> "termination_model_billing"
    ProviderErrorCategory.AUTHENTICATION -> "termination_model_authentication"
    ProviderErrorCategory.PERMISSION_DENIED -> "termination_model_permission_denied"
    ProviderErrorCategory.RATE_LIMIT -> "termination_model_rate_limit"
    ProviderErrorCategory.CONTEXT_LIMIT -> "termination_model_context_limit"
    ProviderErrorCategory.REQUEST_REJECTED, ProviderErrorCategory.PREVIOUS_RESPONSE_NOT_FOUND,
    ProviderErrorCategory.REASONING_UNSUPPORTED, ProviderErrorCategory.IMAGE_DETAIL_UNSUPPORTED -> "termination_model_request_rejected"
    ProviderErrorCategory.MODEL_UNAVAILABLE -> "termination_model_not_found"
    else -> when {
        generateSequence(error) { it.cause }.take(8).any { it is java.io.IOException } -> "termination_model_network"
        error is kotlinx.serialization.SerializationException ||
            error is com.droidance.core.model.llm.OpenAIResponsesIncompleteException ||
            error is com.droidance.core.model.llm.OpenAIResponsesUnterminatedException -> "termination_model_response_invalid"
        else -> "termination_model_unavailable"
    }
}
