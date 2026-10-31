// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import org.junit.Assert.*
import org.junit.Test

class FrameLeaseStoreTest {
    private class Frame : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun replacementDoesNotCloseAnEncodingFrameAndReadersDrainOnce() {
        var drained = 0
        val store = FrameLeaseStore<Frame>({ drained++ }, { throw AssertionError(it) })
        val first = Frame()
        val second = Frame()
        store.publish(first)
        val png = store.acquire()!!
        val preview = store.acquire()!!
        store.publish(second)
        assertEquals(0, first.closes)
        assertSame(second, store.acquire()!!.use { it.value() })
        png.close()
        assertEquals(0, first.closes)
        store.close()
        assertNull(store.acquire())
        assertEquals(1, second.closes)
        assertEquals(0, drained)
        preview.close()
        preview.close()
        assertEquals(1, first.closes)
        assertEquals(1, drained)
        val late = Frame()
        store.publish(late)
        assertEquals(1, late.closes)
        assertFalse(store.hasFrame())
    }

    @Test fun pendingEncoderDoesNotBlockNewFramePublication() {
        val store = FrameLeaseStore<Frame>({}, { throw AssertionError(it) })
        val first = Frame()
        store.publish(first)
        val encoding = store.acquire()!!
        val next = Frame()
        val published = java.util.concurrent.CountDownLatch(1)
        val publisher = Thread { store.publish(next); published.countDown() }
        publisher.start()
        assertTrue(published.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, first.closes)
        encoding.close()
        store.close()
        publisher.join()
        assertEquals(1, first.closes)
        assertEquals(1, next.closes)
    }

    @Test fun failedRetiredFrameCloseIsReportedWithoutLosingItsReplacement() {
        val failures = mutableListOf<Exception>()
        var drained = 0
        val store = FrameLeaseStore<AutoCloseable>({ drained++ }, failures::add)
        val error = IllegalStateException("close failed")
        store.publish(AutoCloseable { throw error })
        val next = Frame()
        store.publish(next)
        assertSame(error, failures.single())
        assertSame(next, store.acquire()!!.use { it.value() })
        store.close()
        assertEquals(1, next.closes)
        assertEquals(1, drained)
    }

    @Test fun failedLeasedFrameCloseStillDrainsExactlyOnce() {
        val failures = mutableListOf<Exception>()
        var drained = 0
        val store = FrameLeaseStore<AutoCloseable>({ drained++ }, failures::add)
        val error = IllegalStateException("close failed")
        store.publish(AutoCloseable { throw error })
        val lease = store.acquire()!!
        store.close()
        assertEquals(0, drained)
        lease.close()
        lease.close()
        assertSame(error, failures.single())
        assertEquals(1, drained)
    }
}
