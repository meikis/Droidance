// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import com.droidance.config.RuleConfigManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class RuntimeTransportPolicy(
    val foregroundPollMs: Long,
    val backgroundPollMs: Long,
    val reconnectMs: Long,
    val maxPendingTasks: Int,
    val maxRetainedTasks: Int,
    val completedRetentionMs: Long,
    val maxBufferedChunks: Int,
    val maxBufferedChars: Int,
    val resultBatchChunks: Int,
    val displayContentPollMs: Long
) {
    init {
        require(foregroundPollMs > 0 && backgroundPollMs >= foregroundPollMs && reconnectMs > 0)
        require(maxPendingTasks > 0 && maxRetainedTasks > 0 && completedRetentionMs > 0)
        require(displayContentPollMs > 0)
        require(maxBufferedChunks > 0 && maxBufferedChars > 0 && resultBatchChunks in 1..maxBufferedChunks)
    }
    companion object {
        fun read(rules: RuleConfigManager): RuntimeTransportPolicy = Json.decodeFromString(
            rules.readMarkdownSection("runtime_process.md", "transport_policy"))
    }
}
