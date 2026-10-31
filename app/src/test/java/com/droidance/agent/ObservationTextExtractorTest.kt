// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationTextExtractorTest {
    @Test
    fun extractsStructuredScreenTextsBeforeFallingBackToRawLines() {
        val lines = ObservationTextExtractor.extract(
            """
            {
              "targets":[{"text":"苹果手机"}],
              "textBlocks":[
                {"text":"iPhone 16 Pro Max"},
                {"text":"¥7999"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("iPhone 16 Pro Max", "¥7999", "苹果手机"), lines)
    }

    @Test
    fun fallsBackToPlainVisibleLinesWhenObservationIsNotStructuredJson() {
        val lines = ObservationTextExtractor.extract(
            """
            - 「iPhone 16」
            - ¥5999
            """.trimIndent()
        )

        assertEquals(listOf("iPhone 16", "¥5999"), lines)
    }

    @Test
    fun ignoresPureVisionTransportProtocolWithoutVisibleText() {
        val lines = ObservationTextExtractor.extract(
            """
            [PURE_VLM_SCREEN] Screenshot attached to the model request.
            Screenshot grid: 720x1584
            No element ids are available in this mode.
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun plainTextWithBracesDoesNotGetMisparsedAsStructuredJson() {
        val lines = ObservationTextExtractor.extract(
            "observe_screen · {} completed without visible text"
        )

        assertEquals(listOf("observe_screen · {} completed without visible text"), lines)
    }

    @Test
    fun modelExtractionPreservesGroundedVisualCoordinates() {
        val raw = """
            [Tappable text elements; coordinates are grounded visual centers]
            筛选 @(840,356)
            价格 @(620,512)
        """.trimIndent()

        assertEquals(
            listOf(
                "[Tappable text elements; coordinates are grounded visual centers]",
                "筛选 @(840,356)",
                "价格 @(620,512)"
            ),
            ObservationTextExtractor.extract(raw, preserveCoordinates = true)
        )
        assertEquals(
            listOf(
                "[Tappable text elements; coordinates are grounded visual centers]",
                "筛选",
                "价格"
            ),
            ObservationTextExtractor.extract(raw)
        )
    }

    @Test
    fun modelExtractionHandlesTargetIdPrefix() {
        val raw = """
            [Tappable text elements; coordinates are grounded visual centers]
            - [text_1] 筛选 @(840,356)
            - [text_2] 价格 @(620,512)
        """.trimIndent()

        assertEquals(
            listOf(
                "[Tappable text elements; coordinates are grounded visual centers]",
                "[text_1] 筛选 @(840,356)",
                "[text_2] 价格 @(620,512)"
            ),
            ObservationTextExtractor.extract(raw, preserveCoordinates = true)
        )
        assertEquals(
            listOf(
                "[Tappable text elements; coordinates are grounded visual centers]",
                "筛选",
                "价格"
            ),
            ObservationTextExtractor.extract(raw)
        )
    }
}
