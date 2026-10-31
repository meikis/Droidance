// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSemanticStateTest {
    @Test
    fun buildsStablePageAndRouteKeysFromGenericSignals() {
        val state = ScreenSemanticState.from(
            foregroundPackage = "com.demo.app",
            foregroundActivityOrWindow = "com.demo.app/.MainActivity",
            screenHash = 42L,
            screenStateText = "Search page\nInput field"
        ).copy(goalStage = "input_query")

        assertEquals("activity:com.demo.app/.MainActivity", state.pageKey)
        assertEquals("input_query", state.goalStage)

        val routeKey = state.routeKey(
            actionIntent = "type_text",
            targetKey = "focused_input",
            normalizedTextIntent = "query candidate"
        )

        assertTrue(routeKey.contains("page=activity:com.demo.app/.MainActivity"))
        assertTrue(routeKey.contains("stage=input_query"))
        assertTrue(routeKey.contains("intent=type_text"))
        assertTrue(routeKey.contains("target=focused_input"))
        assertTrue(routeKey.contains("text=query candidate"))
    }

    @Test
    fun textFallbackPageKeyUsesDigestInsteadOfVisibleBusinessText() {
        val state = ScreenSemanticState.from(
            foregroundPackage = "com.demo.app",
            foregroundActivityOrWindow = null,
            screenHash = 7L,
            screenStateText = "private visible item name"
        )

        assertTrue(state.pageKey.startsWith("text:com.demo.app:"))
        assertFalse(state.pageKey.contains("private visible item name"))
        assertTrue(state.visibleTextDigest?.startsWith("txt:") == true)
    }

    @Test
    fun raw_pixel_hash_does_not_change_the_fallback_page_identity() {
        val before = ScreenSemanticState.from(
            foregroundPackage = "com.demo.app",
            foregroundActivityOrWindow = null,
            screenHash = 11L,
            screenStateText = null
        )
        val after = ScreenSemanticState.from(
            foregroundPackage = "com.demo.app",
            foregroundActivityOrWindow = null,
            screenHash = 12L,
            screenStateText = null
        )

        assertEquals("foreground:com.demo.app", before.pageKey)
        assertEquals(before.pageKey, after.pageKey)
        assertFalse(after.hasStateChangeComparedTo(before))
    }
}
