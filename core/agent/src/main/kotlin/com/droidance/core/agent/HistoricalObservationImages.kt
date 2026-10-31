// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.blob.BlobStoreException
import com.droidance.core.agent.context.DurableContextProjection
import com.droidance.core.agent.context.HistoricalImageSelection

/** Loads only immutable, retained observations from this turn; never captures or changes a screen. */
class HistoricalObservationImages(private val blobs: BlobStore) {
    data class Loaded(
        val images: Map<Long, String>,
        val failures: Map<Long, String>,
        val requiredFailures: Map<String, String> = emptyMap(),
        val currentImageRefs: Set<String> = emptySet(),
        val imageContentRefs: Map<Long, String> = emptyMap()
    )

    fun load(
        projection: DurableContextProjection,
        limit: Int,
        currentImage: String?,
        requiredContentRefs: Set<String> = emptySet(),
        deduplicateCurrentImage: Boolean = false,
        excludedContentRefs: Set<String> = emptySet()
    ): Loaded {
        require(limit >= 0)
        val images = linkedMapOf<Long, String>()
        val failures = linkedMapOf<Long, String>()
        val requiredFailures = linkedMapOf<String, String>()
        val currentImageRefs = linkedSetOf<String>()
        val imageContentRefs = linkedMapOf<Long, String>()
        val candidates = HistoricalImageSelection.candidates(projection.observations)
            .filter { it.contentRef !in excludedContentRefs }
        val required = requiredContentRefs.filter(String::isNotBlank).toSet()
        val candidatesByRef = candidates.flatMap { observation ->
            listOfNotNull(observation.contentRef, observation.modelContentRef).map { it to observation }
        }.toMap()
        required.filter { it !in candidatesByRef }.forEach {
            requiredFailures[it] = REQUIRED_REFERENCE_NOT_ACTIVE
        }
        val activeRequired = required.filter { it in candidatesByRef }.toSet()
        val requestedImages = activeRequired.mapNotNull(candidatesByRef::get).distinctBy { it.contentRef }
        val prioritized = requestedImages + candidates.filter { image -> requestedImages.none { it.contentRef == image.contentRef } }
        // Explicit reads are part of the current model request and must all be attempted,
        // even when the configured recent-image budget is smaller.
        val readLimit = maxOf(limit, requestedImages.size)
        val scanLimit = readLimit + if (currentImage != null) 1 else 0
        // Respect checkpoint image selection and its token estimate.
        // Limit attempted reads too: unavailable blobs cannot trigger an unbounded history scan.
        for (observation in prioritized.take(scanLimit)) {
            if (images.size >= readLimit) break
            val ref = observation.contentRef
            val requiredRefs = setOfNotNull(ref, observation.modelContentRef).intersect(required)
            try {
                // Ordinary history uses the retained model representation; an explicit
                // original read preserves native pixels rather than silently substituting it.
                val imageRef = if (ref in required) ref else observation.modelContentRef ?: ref
                val encoded = blobs.readBase64(imageRef, MAX_IMAGE_BYTES)
                if (deduplicateCurrentImage && encoded == currentImage && requiredRefs.isNotEmpty()) {
                    currentImageRefs += requiredRefs
                } else if (encoded != currentImage || requiredRefs.isNotEmpty()) {
                    images[observation.itemSequence] = encoded
                    imageContentRefs[observation.itemSequence] = imageRef
                }
            } catch (error: BlobStoreException) {
                val reason = error.javaClass.simpleName
                failures[observation.itemSequence] = reason
                requiredRefs.forEach { requiredFailures[it] = reason }
            } catch (error: IllegalArgumentException) {
                val reason = error.javaClass.simpleName
                failures[observation.itemSequence] = reason
                requiredRefs.forEach { requiredFailures[it] = reason }
            }
        }
        return Loaded(images, failures, requiredFailures, currentImageRefs, imageContentRefs)
    }

    companion object {
        const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
        const val REQUIRED_REFERENCE_NOT_ACTIVE = "REQUIRED_REFERENCE_NOT_ACTIVE"
    }
}
