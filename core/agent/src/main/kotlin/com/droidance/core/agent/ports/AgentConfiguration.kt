// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.ports

import com.droidance.core.agent.config.HarnessPolicy
import com.droidance.core.agent.config.HarnessPromptPolicy

/** Policy and presentation inputs loaded by the host; core owns no Android configuration. */
interface AgentConfiguration : RuleSource {
    fun harnessPolicy(): HarnessPolicy
    fun harnessPrompts(): HarnessPromptPolicy
    fun toolContracts(): com.droidance.core.agent.config.ToolContractsDocument
    fun message(key: String): String
    fun publicFallback(key: String): String
    fun outputLanguage(): String
    fun operatorSystemPrompt(): String
    fun taskWallMinutes(): Int
}
