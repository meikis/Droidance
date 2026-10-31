// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.config

import com.droidance.core.agent.config.*

import kotlinx.serialization.Serializable

@Serializable
data class ShellExecutionPolicy(val maxCommandChars: Int, val defaultTimeoutMs: Long, val maxTimeoutMs: Long) {
    fun validate() {
        require(maxCommandChars in 1..65536)
        require(defaultTimeoutMs in 1..maxTimeoutMs && maxTimeoutMs <= 120000)
    }
}
