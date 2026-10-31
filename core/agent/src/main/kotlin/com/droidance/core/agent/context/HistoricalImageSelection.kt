// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

/** Shared ordering for loading and budgeting immutable historical image attachments. */
object HistoricalImageSelection {
    fun candidates(observations: Collection<ObservationProjectionRef>): List<ObservationProjectionRef> {
        val recent = observations.asSequence()
            .filter { it.mode == ObservationProjectionMode.IMAGE }
            .sortedByDescending(ObservationProjectionRef::itemSequence)
            .distinctBy(ObservationProjectionRef::contentRef)
            .toList()
        val byRef = recent.associateBy(ObservationProjectionRef::contentRef)
        val selected = linkedMapOf<String, ObservationProjectionRef>()
        recent.forEach { image ->
            selected.putIfAbsent(image.contentRef, image)
            var parent = image.sourceContentRef
            val visited = mutableSetOf(image.contentRef)
            while (parent != null && visited.add(parent)) {
                val source = byRef[parent] ?: break
                selected.putIfAbsent(parent, source)
                parent = source.sourceContentRef
            }
        }
        return selected.values.toList()
    }

    /** Loading may omit the current frame or an unavailable blob, so this is an upper bound. */
    fun budgetedSequences(observations: Collection<ObservationProjectionRef>, limit: Int): Set<Long> {
        require(limit >= 0)
        return candidates(observations).take(limit).mapTo(linkedSetOf(), ObservationProjectionRef::itemSequence)
    }
}
