// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role

/**
 * Projects the durable conversation into the subset understood consistently by the
 * OpenAI-compatible transports.
 *
 * The stable operator prompt is sent through the provider's dedicated system field. Runtime
 * control messages still need to reach the model, however. Some transports do not accept
 * interleaved system messages (and previously dropped them), so preserve their ordering and
 * intent as explicitly tagged user-context messages instead of silently losing them.
 */
object ProviderMessageProjection {
    private const val RUNTIME_CONTROL_OPEN = "<runtime_control>"
    private const val RUNTIME_CONTROL_CLOSE = "</runtime_control>"

    fun forOpenAiCompatible(messages: List<ChatMessage>): List<ChatMessage> =
        messages.map { message ->
            if (message.role != Role.SYSTEM) {
                message
            } else {
                message.copy(
                    role = Role.USER,
                    content = buildString {
                        appendLine(RUNTIME_CONTROL_OPEN)
                        append(message.content)
                        appendLine()
                        append(RUNTIME_CONTROL_CLOSE)
                    }
                )
            }
        }
}
