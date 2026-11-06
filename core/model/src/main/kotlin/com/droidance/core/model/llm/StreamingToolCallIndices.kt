// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.model.llm

import java.io.IOException

/** Tracks identity within one response; indices are never serialized into request history. */
internal class StreamingToolCallIndices {
    private val byId = mutableMapOf<String, Int>()
    private val occupied = mutableSetOf<Int>()
    private val idByIndex = mutableMapOf<Int, String>()

    fun resolve(explicit: Int?, rawId: String?): Int {
        val id = rawId?.takeIf { it.isNotBlank() }
        val known = id?.let(byId::get)
        val index = explicit ?: known ?: if (id != null) {
            generateSequence(0) { it + 1 }.first { it !in occupied }
        } else {
            occupied.singleOrNull() ?: throw IOException("Ambiguous streaming tool-call identity")
        }
        if (index < 0 || (known != null && known != index) ||
            (id != null && idByIndex[index]?.let { it != id } == true)) {
            throw IOException("Conflicting streaming tool-call identity")
        }
        occupied += index
        if (id != null) {
            byId[id] = index
            idByIndex[index] = id
        }
        return index
    }
}
