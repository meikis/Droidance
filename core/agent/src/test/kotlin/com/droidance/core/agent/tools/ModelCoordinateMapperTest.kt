// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCoordinateMapperTest {
    @Test fun fullDisplayCropDoesNotAccidentallyCropThePreviousMagnifiedRegion() {
        val mapper = ModelCoordinateMapper(2f, 1080 to 1200, 1440 to 3000, ModelCoordinateMapper.Point(300, 600))
        val bounds = ModelCoordinateMapper.Bounds(100, 100, 500, 900)
        assertEquals(ModelCoordinateMapper.Bounds(144, 300, 720, 2700),
            mapper.cropBounds(CropSourceSpace.FULL_DISPLAY, bounds, 1440 to 3000))
        assertEquals(ModelCoordinateMapper.Bounds(354, 660, 570, 1140),
            mapper.cropBounds(CropSourceSpace.CURRENT_VIEW, bounds, 1440 to 3000))
        // Action coordinates remain relative to the magnified view.
        assertEquals(ModelCoordinateMapper.Point(570, 900), mapper.toPhysical(500, 500))
    }
    @Test
    fun mapsNormalizedCoordinatesBackToPhysicalPixels() {
        val mapper = ModelCoordinateMapper(
            scale = 0.5f,
            modelSize = 500 to 1200,
            physicalSize = 1000 to 2400
        )

        val point = mapper.toPhysical(500, 500)

        assertEquals(ModelCoordinateMapper.Point(500, 1200), point)
    }

    @Test
    fun mapsPhysicalAuxiliaryCoordinatesIntoNormalizedSpace() {
        val mapper = ModelCoordinateMapper(
            scale = 500f / 1080f,
            modelSize = 500 to 1100,
            physicalSize = 1080 to 2376
        )

        val point = mapper.toModel(963, 1188)

        assertEquals(ModelCoordinateMapper.Point(892, 500), point)
        assertTrue(point.x in 0..1000)
        assertTrue(point.y in 0..1000)
    }

    @Test
    fun modelAndPhysicalCoordinatesRoundTripWithinOnePhysicalPixel() {
        val mapper = ModelCoordinateMapper(
            scale = 500f / 1080f,
            modelSize = 500 to 1100,
            physicalSize = 1080 to 2376
        )

        val physical = ModelCoordinateMapper.Point(963, 1188)
        val model = mapper.toModel(physical.x, physical.y)
        val roundTripped = mapper.toPhysical(model.x, model.y)

        assertTrue(kotlin.math.abs(physical.x - roundTripped.x) <= 2)
        assertTrue(kotlin.math.abs(physical.y - roundTripped.y) <= 2)
    }

    @Test
    fun meituanNormalizedExternalTabCoordinateMapsToItsVisibleTarget() {
        val mapper = ModelCoordinateMapper(
            scale = 500f / 1080f,
            modelSize = 500 to 1100,
            physicalSize = 1080 to 2376
        )

        val mapped = mapper.mapToPhysical(106, 210)

        assertEquals(
            ModelCoordinateMapper.MappingResult.Valid(ModelCoordinateMapper.Point(114, 499)),
            mapped
        )
    }

    @Test
    fun meituanNormalizedSearchCoordinateIsAcceptedAndMappedToRightEdgeTarget() {
        val mapper = ModelCoordinateMapper(
            scale = 500f / 1080f,
            modelSize = 500 to 1100,
            physicalSize = 1080 to 2376
        )

        val mapped = mapper.mapToPhysical(890, 130)

        assertEquals(
            ModelCoordinateMapper.MappingResult.Valid(ModelCoordinateMapper.Point(961, 309)),
            mapped
        )
    }

    @Test
    fun jdNormalizedSearchCoordinatesFromLatestFailuresMapInsideTheRightSearchButton() {
        val mapper = ModelCoordinateMapper(
            scale = 500f / 1080f,
            modelSize = 500 to 1100,
            physicalSize = 1080 to 2376
        )

        assertEquals(
            ModelCoordinateMapper.MappingResult.Valid(ModelCoordinateMapper.Point(946, 339)),
            mapper.mapToPhysical(875, 143)
        )
        assertEquals(
            ModelCoordinateMapper.MappingResult.Valid(ModelCoordinateMapper.Point(937, 330)),
            mapper.mapToPhysical(868, 139)
        )
    }

    @Test
    fun rejectsCoordinatesOutsideNormalizedContract() {
        val mapper = ModelCoordinateMapper(
            scale = 713f / 1080f,
            modelSize = 713 to 1568,
            physicalSize = 1080 to 2376
        )

        assertRejected(mapper.mapToPhysical(1001, 500), 1001, 500)
    }

    @Test
    fun rejectsNegativeNormalizedCoordinates() {
        val mapper = ModelCoordinateMapper(
            scale = 713f / 1080f,
            modelSize = 713 to 1568,
            physicalSize = 1080 to 2376
        )

        assertRejected(mapper.mapToPhysical(-1, 500), -1, 500)
    }

    @Test
    fun mapsNormalizedCenterBackToPhysicalPixelsWithFrameBounds() {
        val mapper = ModelCoordinateMapper(
            scale = 713f / 1080f,
            modelSize = 713 to 1568,
            physicalSize = 1080 to 2376
        )

        val mapped = mapper.mapToPhysical(500, 500)

        assertEquals(ModelCoordinateMapper.MappingResult.Valid(ModelCoordinateMapper.Point(541, 1188)), mapped)
    }

    @Test
    fun mapsCropZoomCoordinatesBackToPhysicalPixelsWithCropOrigin() {
        val mapper = ModelCoordinateMapper(
            scale = 2f,
            modelSize = 400 to 300,
            physicalSize = 1080 to 2400,
            physicalOrigin = ModelCoordinateMapper.Point(100, 200)
        )

        val mapped = mapper.mapToPhysical(200, 400)

        assertEquals(ModelCoordinateMapper.MappingResult.Valid(ModelCoordinateMapper.Point(140, 260)), mapped)
    }

    @Test
    fun mapsCropZoomBoundsBackToPhysicalPixelsWithCropOrigin() {
        val mapper = ModelCoordinateMapper(
            scale = 2f,
            modelSize = 400 to 300,
            physicalSize = 1080 to 2400,
            physicalOrigin = ModelCoordinateMapper.Point(100, 200)
        )

        val mapped = mapper.toPhysicalBounds(200, 400, 500, 800)

        assertEquals(ModelCoordinateMapper.Bounds(140, 260, 200, 320), mapped)
    }

    @Test
    fun normalizedFullCropMapsToTheWholePhysicalFrame() {
        val mapper = ModelCoordinateMapper(
            scale = 0.5f,
            modelSize = 500 to 1000,
            physicalSize = 1000 to 2000
        )

        val mapped = mapper.toPhysicalBounds(0, 0, 1000, 1000)

        assertEquals(ModelCoordinateMapper.Bounds(0, 0, 1000, 2000), mapped)
    }

    @Test
    fun acceptsNormalizedEdgeAndMapsItToLastVisiblePixel() {
        val mapper = ModelCoordinateMapper(
            scale = 500f / 1080f,
            modelSize = 500 to 1100,
            physicalSize = 1080 to 2376
        )

        val mapped = mapper.mapToPhysical(1000, 1000)

        assertEquals(ModelCoordinateMapper.MappingResult.Valid(ModelCoordinateMapper.Point(1078, 2374)), mapped)
    }

    @Test
    fun identityMapperLeavesCoordinatesUnchangedWithoutFrameSize() {
        val point = ModelCoordinateMapper.identity().toPhysical(42, 99)

        assertEquals(ModelCoordinateMapper.Point(42, 99), point)
    }

    private fun assertRejected(
        mapped: ModelCoordinateMapper.MappingResult,
        x: Int,
        y: Int
    ) {
        assertTrue(mapped is ModelCoordinateMapper.MappingResult.Invalid)
        val invalid = mapped as ModelCoordinateMapper.MappingResult.Invalid
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, invalid.result.executionStatus)
        assertEquals(ToolEvidenceStatus.NOT_REQUIRED, invalid.result.evidenceStatus)
        assertEquals("COORDINATE_OUT_OF_RANGE", invalid.result.diagnosticCode)
        assertTrue(invalid.result.outputText.contains("\"execution_status\":\"NOT_EXECUTED\""))
        assertTrue(invalid.result.outputText.contains("\"code\":\"COORDINATE_OUT_OF_RANGE\""))
        assertTrue(invalid.result.outputText.contains("\"x\":$x,\"y\":$y"))
        assertTrue(invalid.result.outputText.contains("\"allowed_max\":1000"))
    }
}
