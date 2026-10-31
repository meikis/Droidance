// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedTargetCacheTest {

    @Test
    fun resolvesModelPointByExactAndAliasIds() {
        val cache = ObservedTargetCache()
        cache.cacheTargets(
            listOf(
                ObservedTargetCache.TargetEntry(
                    id = "text_1",
                    role = "text",
                    text = "搜索",
                    bounds = listOf(100, 200, 300, 400),
                    modelX = 200,
                    modelY = 300
                ),
                ObservedTargetCache.TargetEntry(
                    id = "text_2",
                    role = "button",
                    text = "确定",
                    bounds = listOf(500, 800, 700, 900),
                    modelX = 600,
                    modelY = 850
                )
            )
        )

        // Exact match
        assertEquals(200 to 300, cache.resolveModelPoint("text_1"))
        // Case-insensitive
        assertEquals(200 to 300, cache.resolveModelPoint("TEXT_1"))
        // Short alias "t1"
        assertEquals(200 to 300, cache.resolveModelPoint("t1"))
        // Numeric alias "1"
        assertEquals(200 to 300, cache.resolveModelPoint("1"))

        // Target 2
        assertEquals(600 to 850, cache.resolveModelPoint("text_2"))
        assertEquals(600 to 850, cache.resolveModelPoint("t2"))

        // Non-existent
        assertNull(cache.resolveModelPoint("text_99"))
        assertNull(cache.resolveModelPoint("unknown"))
        assertNull(cache.resolveModelPoint(""))
    }

    @Test
    fun describeAtMatchesPhysicalBoundingBox() {
        val cache = ObservedTargetCache()
        cache.cacheTargets(
            listOf(
                ObservedTargetCache.TargetEntry(
                    id = "text_1",
                    role = "text",
                    text = "搜索设置",
                    bounds = listOf(100, 200, 300, 400),
                    modelX = 200,
                    modelY = 300
                )
            )
        )

        // Point inside bounds (100..300, 200..400)
        val description = cache.describeAt(150, 250)
        assertNotNull(description)
        assertTrue(description!!.contains("搜索设置"))
        assertTrue(description.contains("#text_1"))

        // Clear invalidates targets
        cache.clear()
        assertNull(cache.describeAt(150, 250))
        assertNull(cache.resolveModelPoint("text_1"))
    }

    @Test
    fun cacheFromScreenStateParsesJsonBackwardCompatibly() {
        val cache = ObservedTargetCache()
        val json = """
            {
                "pkg": "com.example.app",
                "targets": [
                    {
                        "id": "t_btn",
                        "role": "button",
                        "text": "登录",
                        "bounds": [100, 200, 300, 400]
                    }
                ]
            }
        """.trimIndent()

        cache.cacheFromScreenState(json)
        assertEquals(200 to 300, cache.resolveModelPoint("t_btn"))
        val description = cache.describeAt(200, 300)
        assertNotNull(description)
        assertTrue(description!!.contains("登录"))
    }
}
