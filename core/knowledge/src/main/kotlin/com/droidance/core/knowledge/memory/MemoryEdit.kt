// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

data class MemoryEditSnapshot(
    val sourceDocument: String,
    val displayText: String
)

enum class MemoryDisplaySaveResult {
    SAVED,
    STALE,
    AMBIGUOUS
}
