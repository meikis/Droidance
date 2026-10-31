// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNameMatcherTest {
    @Test
    fun aLongerRequestedNameDoesNotIdentifyAShorterInstalledLabel() {
        val pkg = AppNameMatcher.findBestPackage(
            query = "美团外卖",
            apps = listOf(AppInfo("com.sankuai.meituan", "美团"))
        )

        assertNull(pkg)
    }

    @Test
    fun exactLongerInstalledLabelWins() {
        val pkg = AppNameMatcher.findBestPackage(
            query = "美团外卖",
            apps = listOf(
                AppInfo("com.sankuai.meituan", "美团"),
                AppInfo("com.sankuai.meituan.takeoutnew", "美团外卖")
            )
        )

        assertEquals("com.sankuai.meituan.takeoutnew", pkg)
    }

    @Test
    fun singleCharacterLabelDoesNotMatchLongQuery() {
        val pkg = AppNameMatcher.findBestPackage(
            query = "帮我打开一下",
            apps = listOf(AppInfo("example.pkg", "我"))
        )

        assertNull(pkg)
    }

    @Test
    fun localizedLabelCanMatchStablePackageIdentity() {
        val pkg = AppNameMatcher.findBestPackage(
            query = "com.sankuai.meituan",
            apps = listOf(AppInfo("com.sankuai.meituan", "美团"))
        )

        assertEquals("com.sankuai.meituan", pkg)
    }

    @Test
    fun multiWordLatinQueryDoesNotResolveToOneWordSiblingLabel() {
        val pkg = AppNameMatcher.findBestPackage(
            query = "Google Maps",
            apps = listOf(
                AppInfo("com.google.android.googlequicksearchbox", "Google")
            )
        )

        assertNull(pkg)
    }

    @Test
    fun exactMultiWordLatinLabelStillWins() {
        val pkg = AppNameMatcher.findBestPackage(
            query = "Google Maps",
            apps = listOf(
                AppInfo("com.google.android.googlequicksearchbox", "Google"),
                AppInfo("com.google.android.apps.maps", "Google Maps")
            )
        )

        assertEquals("com.google.android.apps.maps", pkg)
    }

    @Test
    fun aSharedNamespaceDoesNotIdentifyAnInstalledApplication() {
        val pkg = AppNameMatcher.findBestPackage(
            query = "vendor",
            apps = listOf(
                AppInfo("com.vendor.side", "辅助应用"),
                AppInfo("com.vendor.vendor", "主应用")
            )
        )

        assertNull(pkg)
    }

    @Test fun anUnknownCompoundNameDoesNotBecomeContainedInstalledLabels() {
        val apps = listOf(AppInfo("sample.first", "QA"), AppInfo("sample.second", "QA"))
        assertEquals(emptyList<AppInfo>(), AppNameMatcher.findCandidates("Missing QA application", apps))
        assertEquals(2, AppNameMatcher.findCandidates(" QA ", apps).size)
        assertEquals("sample.second", AppNameMatcher.findBestPackage("sample.second", apps))
        assertNull(AppNameMatcher.findBestPackage("sample", apps))
    }
}
