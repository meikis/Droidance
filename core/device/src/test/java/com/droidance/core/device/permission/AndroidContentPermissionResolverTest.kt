// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.permission

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidContentPermissionResolverTest {
    @Test
    fun mapsPlatformProviderFamiliesWithoutAppSpecificRules() {
        assertEquals(
            setOf(Manifest.permission.READ_SMS),
            permissions("sms", listOf("inbox"))
        )
        assertEquals(
            setOf(Manifest.permission.READ_CONTACTS),
            permissions("com.android.contacts", listOf("contacts"))
        )
        assertEquals(
            setOf(Manifest.permission.READ_CALL_LOG),
            permissions("call_log", listOf("calls"))
        )
        assertEquals(
            setOf(Manifest.permission.READ_CALENDAR),
            permissions("com.android.calendar", listOf("events"))
        )
        assertTrue(permissions("custom.provider", listOf("rows")).isEmpty())
    }

    @Test
    fun mediaUsesScopedAndroidThirteenPermissions() {
        assertEquals(
            setOf(Manifest.permission.READ_MEDIA_IMAGES),
            permissions("media", listOf("external", "images", "media"), sdkInt = 33)
        )
        assertEquals(
            setOf(Manifest.permission.READ_MEDIA_AUDIO),
            permissions("media", listOf("external", "audio", "media"), sdkInt = 33)
        )
        assertEquals(
            setOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            permissions("media", listOf("external", "images", "media"), sdkInt = 32)
        )
    }

    private fun permissions(
        authority: String,
        path: List<String>,
        sdkInt: Int = 36
    ): Set<String> = AndroidContentPermissionResolver.requiredPermissions(authority, path, sdkInt)
}
