// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

/** Storage operations for the canonical Markdown memory documents. */
interface MemoryDocumentStorage {
    fun readFile(relativePath: String): String?
    fun writeFile(relativePath: String, content: String)
    fun exists(relativePath: String): Boolean
}
