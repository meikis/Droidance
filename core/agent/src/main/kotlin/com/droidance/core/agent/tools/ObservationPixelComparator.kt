// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import kotlin.math.abs

/**
 * A small, semantic-free pixel representation used by the action precondition check.
 * The caller is responsible for decoding a frame; this class only samples and compares
 * coordinates that have already been mapped into the same physical display space.
 */
data class ObservationRaster(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    init {
        require(width > 0 && height > 0) { "Observation raster dimensions must be positive" }
        require(pixels.size == width * height) { "Observation raster pixel count does not match dimensions" }
    }

    fun pixelAt(x: Int, y: Int): Int = pixels[y * width + x]
}

data class ObservationMappedSample(
    val modelX: Int,
    val modelY: Int,
    val physicalX: Int,
    val physicalY: Int
)

data class ObservationPixelReference(
    val samples: List<ObservationMappedSample>,
    val colors: IntArray
) {
    init {
        require(samples.size == colors.size) { "Observation reference sample count mismatch" }
    }
}

data class ObservationPixelComparison(
    val sampledPixels: Int,
    val changedPixels: Int,
    val totalChannelDelta: Long,
    val maxChannelDelta: Int,
    val changedSamples: List<ObservationMappedSample> = emptyList()
) {
    val available: Boolean get() = sampledPixels > 0
    val changedFraction: Double
        get() = if (sampledPixels == 0) 1.0 else changedPixels.toDouble() / sampledPixels
}

/**
 * Pure sampling/comparison logic. It deliberately does not infer page identity, overlays,
 * targets, or task progress from pixels. A changed sample is only a freshness signal.
 */
object ObservationPixelComparator {
    data class ChangedRegion(
        val minModelX: Int,
        val minModelY: Int,
        val maxModelX: Int,
        val maxModelY: Int,
        val changedCount: Int
    ) {
        fun containsOrNear(modelX: Int, modelY: Int, margin: Int = 30): Boolean =
            modelX in (minModelX - margin)..(maxModelX + margin) &&
                modelY in (minModelY - margin)..(maxModelY + margin)

        fun overlapsBounds(bounds: List<Int>, margin: Int = 20): Boolean {
            if (bounds.size < 4) return false
            val left = bounds[0] - margin
            val top = bounds[1] - margin
            val right = bounds[2] + margin
            val bottom = bounds[3] + margin
            return !(right < minModelX || left > maxModelX || bottom < minModelY || top > maxModelY)
        }
    }

    fun changedRegion(samples: List<ObservationMappedSample>): ChangedRegion? {
        if (samples.isEmpty()) return null
        return ChangedRegion(
            minModelX = samples.minOf { it.modelX },
            minModelY = samples.minOf { it.modelY },
            maxModelX = samples.maxOf { it.modelX },
            maxModelY = samples.maxOf { it.modelY },
            changedCount = samples.size
        )
    }

    fun exceedsChangeFraction(
        comparison: ObservationPixelComparison,
        threshold: Double
    ): Boolean {
        require(threshold in 0.0..1.0)
        return comparison.available && comparison.changedFraction > threshold
    }

    fun normalizedGrid(sampleSide: Int): List<Pair<Int, Int>> {
        require(sampleSide >= 2)
        return buildList(sampleSide * sampleSide) {
            for (row in 0 until sampleSide) {
                for (column in 0 until sampleSide) {
                    add(
                        (((column * 2 + 1) * ModelCoordinateMapper.NORMALIZED_MAX) /
                            (sampleSide * 2)) to
                            (((row * 2 + 1) * ModelCoordinateMapper.NORMALIZED_MAX) /
                                (sampleSide * 2))
                    )
                }
            }
        }
    }

    fun mappedGrid(
        mapper: ModelCoordinateMapper,
        modelSize: Pair<Int, Int>,
        physicalSize: Pair<Int, Int>,
        sampleSide: Int
    ): List<ObservationMappedSample> {
        require(modelSize.first > 0 && modelSize.second > 0)
        require(physicalSize.first > 0 && physicalSize.second > 0)
        return normalizedGrid(sampleSide).mapNotNull { (normalizedX, normalizedY) ->
            val model = ModelCoordinateMapper.normalizedToFrame(
                normalizedX,
                normalizedY,
                modelSize
            )
            val physical = mapper.toPhysical(normalizedX, normalizedY)
            if (
                model.x !in 0 until modelSize.first ||
                model.y !in 0 until modelSize.second ||
                physical.x !in 0 until physicalSize.first ||
                physical.y !in 0 until physicalSize.second
            ) {
                null
            } else {
                ObservationMappedSample(model.x, model.y, physical.x, physical.y)
            }
        }
    }

    fun captureReference(
        frame: ObservationRaster,
        samples: List<ObservationMappedSample>
    ): ObservationPixelReference = captureReference(
        width = frame.width,
        height = frame.height,
        samples = samples,
        pixelAt = frame::pixelAt
    )

    fun captureReference(
        width: Int,
        height: Int,
        samples: List<ObservationMappedSample>,
        pixelAt: (Int, Int) -> Int
    ): ObservationPixelReference {
        require(width > 0 && height > 0)
        val validSamples = samples.filter {
            it.modelX in 0 until width && it.modelY in 0 until height
        }
        return ObservationPixelReference(
            samples = validSamples,
            colors = IntArray(validSamples.size) { index ->
                pixelAt(validSamples[index].modelX, validSamples[index].modelY)
            }
        )
    }

    fun compare(
        reference: ObservationPixelReference,
        currentFrame: ObservationRaster,
        channelDelta: Int
    ): ObservationPixelComparison = compare(
        reference = reference,
        width = currentFrame.width,
        height = currentFrame.height,
        pixelAt = currentFrame::pixelAt,
        channelDelta = channelDelta
    )

    fun compare(
        reference: ObservationPixelReference,
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int,
        channelDelta: Int
    ): ObservationPixelComparison {
        require(channelDelta in 0..255)
        require(width > 0 && height > 0)
        var sampled = 0
        var changed = 0
        var totalDelta = 0L
        var maxDelta = 0
        val changedSamplesList = mutableListOf<ObservationMappedSample>()
        reference.samples.forEachIndexed { index, sample ->
            if (sample.physicalX !in 0 until width ||
                sample.physicalY !in 0 until height
            ) return@forEachIndexed
            val before = reference.colors[index]
            val after = pixelAt(sample.physicalX, sample.physicalY)
            val delta = channelDelta(before, after)
            sampled++
            totalDelta += delta.toLong()
            maxDelta = maxOf(maxDelta, delta)
            if (delta > channelDelta) {
                changed++
                changedSamplesList.add(sample)
            }
        }
        return ObservationPixelComparison(
            sampledPixels = sampled,
            changedPixels = changed,
            totalChannelDelta = totalDelta,
            maxChannelDelta = maxDelta,
            changedSamples = changedSamplesList
        )
    }

    /** A local patch needs enough textured samples to establish coordinate freshness. */
    fun compareTarget(
        reference: ObservationPixelReference,
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int,
        bounds: IntArray,
        channelDelta: Int
    ): ObservationPixelComparison? {
        require(bounds.size == 4)
        val indices = reference.samples.indices.filter { index ->
            val sample = reference.samples[index]
            sample.physicalX in bounds[0]..bounds[2] && sample.physicalY in bounds[1]..bounds[3]
        }
        val structured = indices.size >= 9 && listOf(0, 8, 16).any { shift ->
            val channels = indices.map { (reference.colors[it] ushr shift) and 255 }
            channels.max() - channels.min() > channelDelta
        }
        if (!structured) return null
        return compare(ObservationPixelReference(indices.map { reference.samples[it] },
            IntArray(indices.size) { reference.colors[indices[it]] }), width, height, pixelAt, channelDelta)
            .takeIf { it.sampledPixels == indices.size }
    }

    /** Stable target pixels permit outside changes; a changed target always stops execution. */
    fun allowsAction(
        whole: ObservationPixelComparison,
        target: ObservationPixelComparison?,
        threshold: Double
    ): Boolean = whole.available && if (target == null) !exceedsChangeFraction(whole, threshold)
        else target.available && !exceedsChangeFraction(target, threshold)

    internal fun channelDelta(before: Int, after: Int): Int =
        maxOf(
            abs(((before ushr 16) and 0xff) - ((after ushr 16) and 0xff)),
            abs(((before ushr 8) and 0xff) - ((after ushr 8) and 0xff)),
            abs((before and 0xff) - (after and 0xff))
        )
}
