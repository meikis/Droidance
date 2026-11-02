// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AirAdbClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun key() = AirAdbKey.loadOrCreate(temporary.root.resolve("key"), temporary.root.resolve("key.pub"))

    @Test fun localTransportCanRequestUserApprovalWithoutReplacingThePairedKey() {
        val key = key()
        withDaemon({ peer ->
            assertEquals(CNXN, peer.read().command)
            peer.write(AUTH, 1, 0, ByteArray(20) { it.toByte() })
            val signature = peer.read()
            assertEquals(AUTH, signature.command)
            assertEquals(2, signature.arg0)
            assertEquals(256, signature.payload.size)
            peer.write(AUTH, 1, 0, ByteArray(20) { it.toByte() })
            val publicKey = peer.read()
            assertEquals(AUTH, publicKey.command)
            assertEquals(3, publicKey.arg0)
            assertArrayEquals(key.publicKeyBytes, publicKey.payload)
            peer.write(CNXN, 0x01000000, 1024 * 1024)
        }) { port ->
            AirAdbClient("127.0.0.1", port, key, allowUserAuthorization = true).use { it.connect() }
        }
    }

    @Test fun wirelessTransportStillTreatsARejectedKeyAsAnAuthorizationFailure() {
        val key = key()
        withDaemon({ peer ->
            peer.read()
            peer.write(AUTH, 1, 0, ByteArray(20))
            peer.read()
            peer.write(AUTH, 1, 0, ByteArray(20))
            assertEquals(-1, peer.input.read())
        }) { port ->
            AirAdbClient("127.0.0.1", port, key).use { client ->
                assertTrue(runCatching { client.connect() }.exceptionOrNull() is AirAdbAuthorizationException)
            }
        }
    }

    @Test fun daemonRestartUsesRawAdbServicesAndReadsTheirAcknowledgements() {
        withDaemon({ peer ->
            assertEquals(CNXN, peer.read().command)
            peer.write(CNXN, 0x01000000, 1024 * 1024)
            for ((destination, response) in listOf("tcpip:57291" to "restarting in TCP mode port: 57291\n",
                "usb:" to "restarting in USB mode\n")) {
                val request = peer.read()
                assertEquals(OPEN, request.command)
                assertEquals(destination, request.payload.toString(Charsets.UTF_8).trimEnd('\u0000'))
                peer.write(OKAY, 42, request.arg0)
                peer.write(WRTE, 42, request.arg0, response.toByteArray())
                assertEquals(OKAY, peer.read().command)
                peer.write(CLSE, 42, request.arg0)
                assertEquals(CLSE, peer.read().command)
            }
        }) { port ->
            AirAdbClient("127.0.0.1", port, key()).use { client ->
                assertTrue(client.restartTcpip(57291).contains("57291"))
                assertEquals("restarting in USB mode\n", client.restartUsb())
            }
        }
    }

    @Test fun shellV2FramesNeverLeakIntoProcessIdsOrDiagnostics() {
        val text = "57291\n" + "diagnostic ".repeat(30) + "中文\n"
        withDaemon({ peer ->
            peer.read()
            peer.write(CNXN, 0x01000000, 1024 * 1024)
            val request = peer.read()
            assertEquals("shell,v2:ps", request.payload.toString(Charsets.UTF_8).trimEnd('\u0000'))
            peer.write(OKAY, 42, request.arg0)
            val stdout = text.toByteArray()
            val stderr = "warning\n".toByteArray()
            val framed = ByteBuffer.allocate(5 + stdout.size + 5 + stderr.size + 6).order(ByteOrder.LITTLE_ENDIAN)
                .put(1).putInt(stdout.size).put(stdout).put(2).putInt(stderr.size).put(stderr)
                .put(3).putInt(1).put(0).array()
            // The shell frame header itself can straddle ADB transport packets.
            for (chunk in listOf(framed.copyOfRange(0, 3), framed.copyOfRange(3, framed.size))) {
                peer.write(WRTE, 42, request.arg0, chunk)
                assertEquals(OKAY, peer.read().command)
            }
            peer.write(CLSE, 42, request.arg0)
            peer.read()
        }) { port ->
            AirAdbClient("127.0.0.1", port, key()).use { client ->
                assertEquals(text + "warning\n", client.shell("ps", "shell,v2"))
            }
        }
    }

    @Test fun refusedRestartCannotBeReportedAsSuccess() {
        withDaemon({ peer ->
            peer.read()
            peer.write(CNXN, 0x01000000, 1024 * 1024)
            val request = peer.read()
            peer.write(CLSE, 0, request.arg0)
        }) { port ->
            AirAdbClient("127.0.0.1", port, key()).use { client ->
                assertTrue(runCatching { client.restartUsb() }.exceptionOrNull() is IllegalStateException)
            }
        }
    }

    private fun withDaemon(server: (Peer) -> Unit, client: (Int) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { listener ->
            val future = executor.submit { listener.accept().use { socket ->
                socket.soTimeout = 3_000
                server(Peer(socket))
            } }
            try { client(listener.localPort); future.get(5, TimeUnit.SECONDS) }
            finally { executor.shutdownNow() }
        }
    }

    private data class Packet(val command: Int, val arg0: Int, val payload: ByteArray)
    private class Peer(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        fun read(): Packet {
            val header = ByteBuffer.wrap(ByteArray(24).also { input.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            header.int
            val payload = ByteArray(header.int)
            input.readFully(payload)
            return Packet(command, arg0, payload)
        }
        fun write(command: Int, arg0: Int, arg1: Int, payload: ByteArray = byteArrayOf()) {
            output.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command xor -1).array())
            output.write(payload)
            output.flush()
        }
    }
    private companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
    }
}
