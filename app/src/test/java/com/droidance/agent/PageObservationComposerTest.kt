// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertEquals
import org.junit.Test

class PageObservationComposerTest {
    @Test
    fun publishesOnlyTheModelsReadablePageObservation() {
        val combined = PageObservationComposer.compose(
            modelObservations = listOf("当前结果页展示：Apple iPhone 17 256GB，价格 ¥6798")
        )

        assertEquals("当前结果页展示：Apple iPhone 17 256GB，价格 ¥6798", combined)
    }

    @Test
    fun missingModelObservationDoesNotPublishRawScreenText() {
        assertEquals("", PageObservationComposer.compose(modelObservations = emptyList()))
    }

    @Test
    fun exactRepeatedModelLinesArePublishedOnlyOnce() {
        assertEquals(
            "商品 A ¥100\n商品 B ¥200",
            PageObservationComposer.compose(
                modelObservations = listOf("商品 A ¥100", "商品 B ¥200\n商品 A ¥100")
            )
        )
    }

    @Test
    fun modelCompositionKeepsVisualCoordinatesForActionGrounding() {
        assertEquals(
            "筛选 @(840,356)\n价格 @(620,512)",
            PageObservationComposer.composeForModel(
                modelObservations = listOf("筛选 @(840,356)\n价格 @(620,512)")
            )
        )
        assertEquals(
            "筛选\n价格",
            PageObservationComposer.compose(
                modelObservations = listOf("筛选 @(840,356)\n价格 @(620,512)")
            )
        )
    }
}
