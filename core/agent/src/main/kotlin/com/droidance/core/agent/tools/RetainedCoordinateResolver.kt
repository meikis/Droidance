// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import kotlin.math.roundToInt

/** Maps an explicitly selected retained view back to its still-current original. No device access. */
object RetainedCoordinateResolver {
    fun resolve(
        reference: String, x: Int, y: Int, currentObservationId: String, currentContentRef: String,
        images: List<DetailAttachmentAddedEventPayload>
    ): ModelCoordinateMapper.Point? {
        if (x !in 0..1000 || y !in 0..1000) return null
        var ref = reference
        var nx = x.toDouble(); var ny = y.toDouble()
        var parentObservationId: String? = null
        val visited = mutableSetOf<String>()
        while (visited.add(ref)) {
            val matches = images.filter { (it.observationId == ref || it.contentRef == ref) &&
                (parentObservationId == null || it.observationId == parentObservationId) }
            if (matches.isEmpty() || matches.map { listOf(it.contentRef, it.sourceContentRef, it.sourceRegion) }.distinct().size != 1) return null
            val image = matches.lastOrNull { it.sourceRegion == null && it.observationId == currentObservationId }
                ?: matches.last()
            val region = image.sourceRegion
            if (region == null) {
                if (image.sourceContentRef != null || image.contentRef != currentContentRef ||
                    matches.none { it.observationId == currentObservationId } ||
                    parentObservationId != null && parentObservationId != currentObservationId) return null
                return ModelCoordinateMapper.Point(nx.roundToInt().coerceIn(0, 1000), ny.roundToInt().coerceIn(0, 1000))
            }
            nx = (region.x + nx * region.width / 1000.0) * 1000.0 / region.sourceWidth
            ny = (region.y + ny * region.height / 1000.0) * 1000.0 / region.sourceHeight
            // Content identities validate every link, including nested crops.
            ref = image.sourceContentRef ?: return null
            parentObservationId = image.sourceObservationId
        }
        return null
    }
}
