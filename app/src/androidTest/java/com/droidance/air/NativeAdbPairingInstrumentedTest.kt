// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.air

import com.droidance.core.device.air.*

import com.droidance.air.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.shizuku.manager.adb.PairingContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged JNI library without changing the device's ADB pairing or task state. */
@RunWith(AndroidJUnit4::class)
class NativeAdbPairingInstrumentedTest {
    @Test
    fun clientAndServerExchangeBinaryMessagesInBothDirections() {
        withPeers("synthetic-pairing-secret".toByteArray()) { client, server ->
            assertTrue(client.msg.isNotEmpty())
            assertTrue(server.msg.isNotEmpty())
            assertTrue(client.initCipher(server.msg))
            assertTrue(server.initCipher(client.msg))
            val payloads = listOf(byteArrayOf(), ByteArray(257) { it.toByte() }, "second message".toByteArray())
            for (payload in payloads) {
                val encrypted = requireNotNull(client.encrypt(payload))
                assertArrayEquals(payload, server.decrypt(encrypted))
                val response = requireNotNull(server.encrypt(payload.reversedArray()))
                assertArrayEquals(payload.reversedArray(), client.decrypt(response))
            }
        }
    }

    @Test
    fun tamperedCiphertextIsRejectedWithoutConsumingTheReceiveSequence() {
        withPeers("synthetic-pairing-secret".toByteArray()) { client, server ->
            assertTrue(client.initCipher(server.msg))
            assertTrue(server.initCipher(client.msg))
            val original = requireNotNull(client.encrypt("authenticated payload".toByteArray()))
            val altered = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertNull(server.decrypt(altered))
            assertArrayEquals("authenticated payload".toByteArray(), server.decrypt(original))
        }
    }

    @Test
    fun differentPasswordsDoNotAuthenticate() {
        val client = requireNotNull(PairingContext.create("client-secret".toByteArray()))
        val server = createServer("different-secret".toByteArray())
        try {
            assertTrue(client.initCipher(server.msg))
            assertTrue(server.initCipher(client.msg))
            assertNull(server.decrypt(requireNotNull(client.encrypt("payload".toByteArray()))))
        } finally {
            client.destroy()
            server.destroy()
        }
    }

    @Test
    fun oversizedPeerMessageIsRejected() {
        val client = requireNotNull(PairingContext.create("synthetic-pairing-secret".toByteArray()))
        try {
            assertFalse(client.initCipher(ByteArray(4096)))
        } finally {
            client.destroy()
        }
    }

    private fun withPeers(password: ByteArray, block: (PairingContext, PairingContext) -> Unit) {
        val client = requireNotNull(PairingContext.create(password))
        val server = createServer(password)
        try {
            block(client, server)
        } finally {
            client.destroy()
            server.destroy()
        }
    }

    /** The production API is client-only; use its existing native server role solely in this test. */
    private fun createServer(password: ByteArray): PairingContext {
        val nativeConstructor = PairingContext::class.java.getDeclaredMethod(
            "nativeConstructor", Boolean::class.javaPrimitiveType, ByteArray::class.java
        ).apply { isAccessible = true }
        val pointer = nativeConstructor.invoke(null, false, password) as Long
        assertTrue("Native server construction failed", pointer != 0L)
        return PairingContext::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(pointer).also { assertNotNull(it) }
    }
}
