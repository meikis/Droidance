// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.session

import com.droidance.core.model.llm.session.*

import kotlinx.serialization.Serializable

@Serializable
enum class ModelTransport {
    OPENAI_RESPONSES,
    GENERIC_CHAT_COMPLETIONS
}
