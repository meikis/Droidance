// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.update

import org.junit.Assert.*
import org.junit.Test

class VersionComparatorTest {
    @Test fun semverPrecedenceMatchesTheSpecification() {
        val ascending = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.9", "1.0.0-beta.10", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
            "1.0.1", "1.1.0", "2.0.0")
        ascending.forEachIndexed { i, current -> ascending.forEachIndexed { j, remote ->
            assertEquals("$current -> $remote", j > i, VersionComparator.isNewer(remote, current))
        } }
    }

    @Test fun releaseTagsAndBuildMetadataDoNotChangePrecedence() {
        assertTrue(VersionComparator.isNewer("v0.2.0", "0.1.0"))
        assertTrue(VersionComparator.isNewer("V1.0.0", "v0.9.0"))
        assertFalse(VersionComparator.isNewer("1.0.0+new", "1.0.0+old"))
        assertTrue(VersionComparator.isNewer("1.0.0+build", "1.0.0-rc.1+build"))
    }

    @Test fun numericIdentifiersHaveNoMachineIntegerLimit() {
        assertTrue(VersionComparator.isNewer("999999999999999999999999.0.0", "2.0.0"))
        assertTrue(VersionComparator.isNewer("1.0.0-beta.100000000000000000000", "1.0.0-beta.99999999999999999999"))
    }

    @Test fun apkUpdatesRequireAnIncreasingCodeANewerPublicVersionAndASupportedSdk() {
        assertTrue(isSupportedVersionUpgrade(1, "0.2.0-rc.1", 2, "0.2.0", 28, 35))
        assertFalse(isSupportedVersionUpgrade(2, "0.1.0", 2, "0.2.0", 28, 35))
        assertFalse(isSupportedVersionUpgrade(3, "0.1.0", 2, "0.2.0", 28, 35))
        assertFalse(isSupportedVersionUpgrade(1, "0.2.0", 2, "0.1.0", 28, 35))
        assertFalse(isSupportedVersionUpgrade(1, "0.1.0", 2, "0.2.0", 36, 35))
        assertFalse(isSupportedVersionUpgrade(1, "0.1.0", 2, "invalid", 28, 35))
    }

    @Test fun invalidVersionsNeverProduceAnUpdate() {
        for (invalid in listOf("", " ", "20261006-V13", "1.0", "01.0.0", "1.0.0-01", "1.0.0-beta.01",
            "1.0.0-", "1.0.0+", "1.0.0-a..b", "1.0.0+a..b", "1.0.0_foo", "-1.0.0")) {
            assertFalse(invalid, VersionComparator.isValid(invalid))
            assertFalse(invalid, VersionComparator.isNewer(invalid, "1.0.0"))
            assertFalse(invalid, VersionComparator.isNewer("2.0.0", invalid))
        }
    }
}
