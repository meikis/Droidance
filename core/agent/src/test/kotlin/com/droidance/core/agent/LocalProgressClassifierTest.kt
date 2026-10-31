// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.LocalActionEvidence.ProgressState
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalProgressClassifierTest {
    @Test
    fun explicitRouteWithoutExpectedPackageRequiresObservableTransition() {
        val progress = LocalProgressClassifier.classify(
            toolName = "launch_deeplink",
            expectedPackage = null,
            beforeForegroundPackage = "com.demo",
            foregroundPackage = "com.demo",
            launchedUnverified = true,
            beforeHash = 1,
            afterHash = 2,
            textChanged = false
        )

        assertEquals(ProgressState.ROUTE_LAUNCHED_UNVERIFIED, progress)
    }

    @Test
    fun explicitRouteWithoutExpectedPackageAndWithoutTransitionRequiresObservation() {
        val progress = LocalProgressClassifier.classify(
            toolName = "launch_deeplink",
            expectedPackage = null,
            beforeForegroundPackage = "com.demo",
            foregroundPackage = "com.demo",
            launchedUnverified = true,
            beforeHash = 1,
            afterHash = 1,
            textChanged = false
        )

        assertEquals(ProgressState.ROUTE_LAUNCHED_UNVERIFIED, progress)
    }

    @Test
    fun acceptedRouteThatLeavesSameTargetPageUnchangedRequiresObservation() {
        val beforeState = ScreenSemanticState.from("com.demo", "com.demo/.Main", 1L, null)
        val progress = LocalProgressClassifier.classify(
            toolName = "open_app",
            expectedPackage = "com.demo",
            beforeForegroundPackage = "com.demo",
            foregroundPackage = "com.demo",
            launchedUnverified = true,
            beforeHash = 1,
            afterHash = 1,
            textChanged = false,
            beforeSemanticState = beforeState,
            afterSemanticState = beforeState.copy()
        )

        assertEquals(ProgressState.ROUTE_LAUNCHED_UNVERIFIED, progress)
    }

    @Test
    fun foregroundPackageChangeWithoutRenderedFrameChangeStillRequiresObservation() {
        val progress = LocalProgressClassifier.classify(
            toolName = "open_app",
            expectedPackage = "com.target",
            beforeForegroundPackage = "com.previous",
            foregroundPackage = "com.target",
            launchedUnverified = true,
            beforeHash = 7,
            afterHash = 7,
            textChanged = false
        )

        assertEquals(ProgressState.ROUTE_LAUNCHED_UNVERIFIED, progress)
    }

    @Test
    fun foregroundMetadataAloneStillRequiresRenderedDeepLinkObservation() {
        val beforeState = ScreenSemanticState.from(
            "com.previous",
            "com.previous/.Main",
            7L,
            null
        )
        val afterState = ScreenSemanticState.from(
            "com.target",
            "com.target/.Router",
            7L,
            null
        )
        val progress = LocalProgressClassifier.classify(
            toolName = "launch_deeplink",
            expectedPackage = "com.target",
            beforeForegroundPackage = "com.previous",
            foregroundPackage = "com.target",
            launchedUnverified = true,
            beforeHash = 7,
            afterHash = 7,
            textChanged = false,
            beforeSemanticState = beforeState,
            afterSemanticState = afterState
        )

        assertEquals(ProgressState.ROUTE_LAUNCHED_UNVERIFIED, progress)
    }

    @Test
    fun clickWithOnlyPixelHashChangeIsRawChanged() {
        val progress = LocalProgressClassifier.classify(
            toolName = "click",
            expectedPackage = null,
            beforeForegroundPackage = "com.sankuai.meituan",
            foregroundPackage = "com.sankuai.meituan",
            launchedUnverified = false,
            beforeHash = 1,
            afterHash = 2,
            textChanged = false
        )

        assertEquals(ProgressState.RAW_CHANGED, progress)
    }

    @Test
    fun semantic_fallback_does_not_promote_hash_only_change_to_state_changed() {
        val beforeState = ScreenSemanticState.from("com.demo", null, 1L, null)
        val afterState = ScreenSemanticState.from("com.demo", null, 2L, null)

        val progress = LocalProgressClassifier.classify(
            toolName = "click",
            expectedPackage = null,
            beforeForegroundPackage = "com.demo",
            foregroundPackage = "com.demo",
            launchedUnverified = false,
            beforeHash = 1,
            afterHash = 2,
            textChanged = afterState.hasStateChangeComparedTo(beforeState),
            beforeSemanticState = beforeState,
            afterSemanticState = afterState
        )

        assertEquals(ProgressState.RAW_CHANGED, progress)
    }

    @Test
    fun clickWithSemanticChangeIsStateChanged() {
        val progress = LocalProgressClassifier.classify(
            toolName = "click",
            expectedPackage = null,
            beforeForegroundPackage = "com.sankuai.meituan",
            foregroundPackage = "com.sankuai.meituan",
            launchedUnverified = false,
            beforeHash = 1,
            afterHash = 2,
            textChanged = true
        )

        assertEquals(ProgressState.STATE_CHANGED, progress)
    }

    @Test
    fun scrollWithOnlyPixelHashChangeIsRawChanged() {
        val progress = LocalProgressClassifier.classify(
            toolName = "scroll",
            expectedPackage = null,
            beforeForegroundPackage = "com.sankuai.meituan",
            foregroundPackage = "com.sankuai.meituan",
            launchedUnverified = false,
            beforeHash = 1,
            afterHash = 2,
            textChanged = false
        )

        assertEquals(ProgressState.RAW_CHANGED, progress)
    }

    @Test
    fun foregroundPackageChangeWithoutExpectedTargetIsContextChanged() {
        val progress = LocalProgressClassifier.classify(
            toolName = "scroll",
            expectedPackage = null,
            beforeForegroundPackage = "com.sankuai.meituan",
            foregroundPackage = "com.oplus.gesture",
            launchedUnverified = false,
            beforeHash = 1,
            afterHash = 1,
            textChanged = false
        )

        assertEquals(ProgressState.CONTEXT_CHANGED, progress)
    }

    @Test
    fun goalStageChangeIsGoalProgress() {
        val beforeState = ScreenSemanticState.from(
            foregroundPackage = "com.demo",
            foregroundActivityOrWindow = "MainActivity",
            screenHash = 1,
            screenStateText = "before"
        ).copy(goalStage = "search")
        val afterState = ScreenSemanticState.from(
            foregroundPackage = "com.demo",
            foregroundActivityOrWindow = "MainActivity",
            screenHash = 2,
            screenStateText = "after"
        ).copy(goalStage = "verify")

        val progress = LocalProgressClassifier.classify(
            toolName = "click",
            expectedPackage = null,
            beforeForegroundPackage = "com.demo",
            foregroundPackage = "com.demo",
            launchedUnverified = false,
            beforeHash = 1,
            afterHash = 2,
            textChanged = true,
            beforeSemanticState = beforeState,
            afterSemanticState = afterState
        )

        assertEquals(ProgressState.GOAL_PROGRESS, progress)
    }

    @Test
    fun replacingMostOfTheVisibleContentInsideOneActivityIsOnlyAStateChange() {
        val page = "com.shop/.MainActivity"
        val before = ScreenSemanticState.from(
            foregroundPackage = "com.shop",
            foregroundActivityOrWindow = page,
            screenHash = 1L,
            screenStateText = "sorted list"
        )
        val after = ScreenSemanticState.from(
            foregroundPackage = "com.shop",
            foregroundActivityOrWindow = page,
            screenHash = 2L,
            screenStateText = "filtered list"
        )

        val progress = LocalProgressClassifier.classify(
            toolName = "screen_act",
            expectedPackage = null,
            beforeForegroundPackage = "com.shop",
            foregroundPackage = "com.shop",
            launchedUnverified = false,
            beforeHash = 1,
            afterHash = 2,
            textChanged = true,
            beforeSemanticState = before,
            afterSemanticState = after
        )

        assertEquals(ProgressState.STATE_CHANGED, progress)
    }

    @Test
    fun togglingOneControlInsideOneActivityStaysAnAmbiguousStateChange() {
        val page = "com.shop/.MainActivity"
        val before = ScreenSemanticState.from(
            foregroundPackage = "com.shop",
            foregroundActivityOrWindow = page,
            screenHash = 1L,
            screenStateText = "sort by price"
        )
        val after = ScreenSemanticState.from(
            foregroundPackage = "com.shop",
            foregroundActivityOrWindow = page,
            screenHash = 2L,
            screenStateText = "sort by sales"
        )

        val progress = LocalProgressClassifier.classify(
            toolName = "screen_act",
            expectedPackage = null,
            beforeForegroundPackage = "com.shop",
            foregroundPackage = "com.shop",
            launchedUnverified = false,
            beforeHash = 1,
            afterHash = 2,
            textChanged = true,
            beforeSemanticState = before,
            afterSemanticState = after
        )

        assertEquals(ProgressState.STATE_CHANGED, progress)
    }
}
