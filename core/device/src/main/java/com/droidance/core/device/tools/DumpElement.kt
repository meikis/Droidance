// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

data class DumpElement(
    val text: String,
    val desc: String,
    val resourceId: String,
    val cx: Int,
    val cy: Int,
    val width: Int,
    val height: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val className: String,
    val semanticLabel: String,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val depth: Int = 0,
    val focused: Boolean = false
)

/**
 * Resolve a text query inside a visual text box without assuming that the box itself is one
 * control. A visual text box can contain several adjacent labels; narrowing the target
 * to the matched substring keeps semantic lookup independent of any App or task vocabulary.
 */
fun DumpElement.targetForText(query: String): DumpElement? {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return null
    val sourceText = text
    val textIndex = sourceText.indexOf(normalizedQuery, ignoreCase = true)
    if (textIndex >= 0 && sourceText.isNotBlank() && width > 0) {
        val left = (cx - width / 2).coerceAtLeast(0)
        val right = (left + width).coerceAtLeast(left + 1)
        val sourceLength = sourceText.length
        val matchLeft = left + ((right - left) * textIndex / sourceLength)
        val matchRight = left + ((right - left) * (textIndex + normalizedQuery.length) / sourceLength)
        val boundedRight = matchRight.coerceAtLeast(matchLeft + 1).coerceAtMost(right)
        val boundedLeft = matchLeft.coerceIn(left, boundedRight - 1)
        return copy(
            text = normalizedQuery,
            cx = (boundedLeft + boundedRight) / 2,
            width = boundedRight - boundedLeft
        )
    }
    if (
        desc.contains(normalizedQuery, ignoreCase = true) ||
        resourceId.contains(normalizedQuery, ignoreCase = true) ||
        semanticLabel.contains(normalizedQuery, ignoreCase = true)
    ) {
        return this
    }
    return null
}
