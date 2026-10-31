// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpElementTest {
    @Test
    fun text_match_is_narrowed_inside_a_combined_visual_line() {
        val line = DumpElement(
            text = "综合 销量 全部 天猫 店铺",
            desc = "",
            resourceId = "",
            cx = 500,
            cy = 120,
            width = 800,
            height = 42,
            clickable = false,
            editable = false,
            scrollable = false,
            className = "VisualText",
            semanticLabel = ""
        )

        val target = line.targetForText("天猫")

        assertEquals("天猫", target?.text)
        assertTrue(target != null && target.width < line.width)
        assertTrue(target != null && target.cx > line.cx)
        assertEquals(line.cy, target?.cy)
    }

    @Test
    fun non_text_semantic_fields_keep_the_original_element_bounds() {
        val element = DumpElement(
            text = "",
            desc = "Open menu",
            resourceId = "",
            cx = 200,
            cy = 300,
            width = 80,
            height = 80,
            clickable = true,
            editable = false,
            scrollable = false,
            className = "Button",
            semanticLabel = ""
        )

        val target = element.targetForText("menu")

        assertEquals(element, target)
    }

    @Test
    fun unmatched_text_does_not_create_a_target() {
        val element = DumpElement(
            text = "当前页面",
            desc = "",
            resourceId = "",
            cx = 200,
            cy = 300,
            width = 80,
            height = 40,
            clickable = false,
            editable = false,
            scrollable = false,
            className = "VisualText",
            semanticLabel = ""
        )

        assertNull(element.targetForText("不存在"))
    }
}
