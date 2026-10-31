// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

/** Combines independently grounded descriptions of the same visible page. */
object PageObservationComposer {
    fun compose(modelObservations: Iterable<String>): String {
        return composeInternal(modelObservations, preserveCoordinates = false)
    }

    /** Model-facing composition keeps grounded visual coordinates as action hints. */
    fun composeForModel(modelObservations: Iterable<String>): String {
        return composeInternal(modelObservations, preserveCoordinates = true)
    }

    private fun composeInternal(
        modelObservations: Iterable<String>,
        preserveCoordinates: Boolean
    ): String {
        val seen = linkedSetOf<String>()
        return modelObservations
            .flatMap { ObservationTextExtractor.extract(it, preserveCoordinates) }
            .asSequence()
            .map(::cleanLine)
            .filter(String::isNotBlank)
            .filter { seen.add(normalize(it)) }
            .joinToString("\n")
    }

    private fun cleanLine(text: String): String =
        text.trim().replace(WHITESPACE_REGEX, " ")

    private fun normalize(text: String): String = cleanLine(text).lowercase()

    private val WHITESPACE_REGEX = Regex("\\s+")
}
