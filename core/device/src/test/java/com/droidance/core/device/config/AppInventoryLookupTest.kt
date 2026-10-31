// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.config

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AppInventoryLookupTest {
    private val policy = AppInventoryPolicy(30000, 4, 6144, 20)
    private fun snapshot(apps: List<AppInfo> = emptyList()) = AppInventorySnapshot(
        0, 1, apps.map { it.packageName }.toSet(), emptySet(), apps,
        "fixture", true, true, 1000, 1000, false, null, 900)

    @Test fun cachedHitsNeverScanEvenWhenCollectionWasInvalidated() = runBlocking {
        var scans = 0
        val value = snapshot(listOf(AppInfo("sample.reader", "Reader"))).copy(dirty = true)
        val lookup = AppInventoryLookup({ value }, { scans++ }, { policy })
        lookup.ensure("Reader", "")
        lookup.ensure("", "sample.reader")
        lookup.ensure("", "")
        assertEquals(0, scans)
    }

    @Test fun aMissInACompleteCacheRefreshesOnceAndReturnsNewlyInstalledFacts() = runBlocking {
        var value = snapshot()
        var scans = 0
        val lookup = AppInventoryLookup({ value }, {
            scans++
            value = snapshot(listOf(AppInfo("sample.reader", "Reader"))).copy(generation = 2)
        }, { policy })
        lookup.ensure("Reader", "")
        lookup.ensure("Reader", "")
        assertEquals(1, scans)
        assertEquals("sample.reader", AppNameMatcher.findBestPackage("Reader", value.apps))
    }

    @Test fun concurrentMissesShareOneRefreshAndNegativeResultsExpire() = runBlocking {
        var value = snapshot()
        var clock = 1000L
        var scans = 0
        val lookup = AppInventoryLookup({ value }, {
            scans++
            delay(10)
            value = value.copy(generation = value.generation + 1)
        }, { policy }, { clock })
        (1..8).map { async { lookup.ensure("Missing", "") } }.awaitAll()
        assertEquals(1, scans)
        lookup.ensure("missing", "")
        assertEquals(1, scans)
        clock += policy.retryAfterMs
        lookup.ensure("Missing", "")
        assertEquals(2, scans)
        value = value.copy(generation = value.generation + 1)
        lookup.ensure("Missing", "")
        assertEquals(3, scans)
    }

    @Test fun aDifferentMissingTargetDoesNotReuseAnotherTargetsNegativeResult() = runBlocking {
        var scans = 0
        val value = snapshot()
        val lookup = AppInventoryLookup({ value }, { scans++ }, { policy })
        lookup.ensure("", "sample.first")
        lookup.ensure("", "sample.second")
        lookup.ensure("", "sample.first")
        assertEquals(2, scans)
    }

    @Test fun cachedHitsDoNotWaitForAnUnrelatedScanAndUnchangedScansKeepOtherNegativeResults() = runBlocking {
        var value = snapshot(listOf(AppInfo("sample.reader", "Reader")))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var scans = 0
        val lookup = AppInventoryLookup({ value }, {
            scans++
            entered.complete(Unit)
            finish.await()
            value = value.copy(generation = value.generation + 1)
        }, { policy })
        val missing = async { lookup.ensure("First missing", "") }
        entered.await()
        withTimeout(1000) { lookup.ensure("Reader", "") }
        assertEquals(1, scans)
        finish.complete(Unit)
        missing.await()
        lookup.ensure("Second missing", "")
        lookup.ensure("First missing", "")
        assertEquals(2, scans)
    }

    @Test fun firstEmptyInventoryAndChannelRecoveryAreCollectedButHealthyRecoveryIsFree() = runBlocking {
        var value = snapshot().copy(complete = false, capturedAtMs = 0)
        var scans = 0
        val lookup = AppInventoryLookup({ value }, { scans++; value = snapshot() }, { policy })
        lookup.ensure("", "")
        lookup.ensure("", "", refreshIncomplete = true)
        assertEquals(1, scans)
    }
}
