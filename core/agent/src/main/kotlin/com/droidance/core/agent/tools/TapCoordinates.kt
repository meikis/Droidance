// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Submitted input coordinates are delivery evidence, never a platform hit-test or goal assertion. */
@Serializable
data class TapCoordinates(
    val tapIndex: Int,
    val requestedX: Int?, val requestedY: Int?, val coordinateRef: String?,
    val mappedPhysicalX: Int?, val mappedPhysicalY: Int?,
    val injectedPhysicalX: Int, val injectedPhysicalY: Int,
    val sourceObservationId: String, val executionId: String, val backendSessionId: String,
    val displayId: Int, val rotationDegrees: Int
)

class TapRequest(
    val x: Int?, val y: Int?, val coordinateRef: String?, val physical: ModelCoordinateMapper.Point?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TapRequest>
}
