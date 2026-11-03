// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

/** Geometry only. Collapsing changes presentation, never the task's execution state. */
internal object RuntimeIslandDocking {
    enum class Edge { LEFT, TOP, RIGHT, BOTTOM }

    data class Placement(val left: Int, val top: Int)

    fun nearbyEdge(left: Int, top: Int, width: Int, height: Int,
        screenWidth: Int, screenHeight: Int, threshold: Int): Edge? {
        val distances = listOf(
            Edge.LEFT to left,
            Edge.TOP to top,
            Edge.RIGHT to (screenWidth - left - width),
            Edge.BOTTOM to (screenHeight - top - height)
        )
        return distances.minByOrNull { it.second }?.takeIf { it.second <= threshold }?.first
    }

    fun place(edge: Edge?, centerX: Float, centerY: Float, width: Int, height: Int,
        screenWidth: Int, screenHeight: Int): Placement {
        val maxLeft = (screenWidth - width).coerceAtLeast(0)
        val maxTop = (screenHeight - height).coerceAtLeast(0)
        val left = when (edge) {
            Edge.LEFT -> 0
            Edge.RIGHT -> maxLeft
            else -> (centerX - width / 2f).toInt().coerceIn(0, maxLeft)
        }
        val top = when (edge) {
            Edge.TOP -> 0
            Edge.BOTTOM -> maxTop
            else -> (centerY - height / 2f).toInt().coerceIn(0, maxTop)
        }
        return Placement(left, top)
    }
}
