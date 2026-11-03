// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import java.security.MessageDigest
import java.util.UUID
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.model.llm.ModelStreamPlan

@kotlinx.serialization.Serializable
enum class MessageSource(val wireName: String) {
    DIRECT("direct"),
    SCHEDULED("scheduled"),
    APP_TAKEOVER("app_takeover")
}

/** Explicit composer selection, frozen when the request is submitted. */
@kotlinx.serialization.Serializable
data class RequestedSkill(val name: String, val revision: String, val draft: Boolean = false) {
    init {
        require(name.matches(Regex("[a-z0-9][a-z0-9-]{0,59}")))
        require(revision.matches(Regex("[a-f0-9]{64}")))
    }
}

/** Immutable ingress for every user-visible Agent turn. */
@kotlinx.serialization.Serializable
data class TurnRequest(
    val requestId: String,
    val source: MessageSource,
    val rawText: String,
    val images: List<String>,
    val rawHash: String,
    val conversationId: String? = null,
    val modelPlan: ModelStreamPlan? = null,
    val executionSpace: ExecutionSpace? = null,
    val requestedSkills: List<RequestedSkill> = emptyList()
) {
    val userText: String get() = rawText

    companion object {
        fun create(
            source: MessageSource,
            rawText: String,
            images: List<String> = emptyList(),
            conversationId: String? = null,
            modelPlan: ModelStreamPlan? = null,
            executionSpace: ExecutionSpace? = null,
            requestedSkills: List<RequestedSkill> = emptyList()
        ): TurnRequest =
            TurnRequest(
                requestId = UUID.randomUUID().toString(),
                source = source,
                rawText = rawText,
                images = images.toList(),
                rawHash = MessageDigest.getInstance("SHA-256")
                    .digest(rawText.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) },
                conversationId = conversationId,
                modelPlan = modelPlan,
                executionSpace = executionSpace,
                requestedSkills = requestedSkills.distinct().toList()
            )
    }
}
