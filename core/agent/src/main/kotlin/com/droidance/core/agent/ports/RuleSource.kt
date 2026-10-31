// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.ports

/**
 * Pure JVM contract for reading rules and prompt templates.
 * Implemented by app's RuleConfigManager or test mocks.
 */
fun interface RuleSource {
    fun readMarkdownSection(fileName: String, heading: String): String
}
