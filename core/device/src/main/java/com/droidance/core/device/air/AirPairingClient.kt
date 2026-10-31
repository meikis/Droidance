// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import android.net.ssl.SSLSockets
import moe.shizuku.manager.adb.PairingContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * Implements the ADB wireless pairing protocol:
 * TLS handshake → TLS keying material export → SPAKE2+ exchange → PeerInfo exchange
 */
class AirPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val adbKey: AirAdbKey
) {

    companion object {
        private const val SPAKE2_MSG: Byte = 0
        private const val PEER_INFO: Byte = 1
        private const val HEADER_VERSION: Byte = 1
        private const val PEER_INFO_SIZE = 8192
        private const val PEER_DATA_SIZE = 8191
        private const val ADB_RSA_PUB_KEY: Byte = 0
        private const val KEYING_MATERIAL_LABEL = "adb-label\u0000"
        private const val KEYING_MATERIAL_LENGTH = 64
    }

    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private var socket: Socket? = null
    private var pairingContext: PairingContext? = null

    fun pair(): Boolean {
        return try {
            setupTlsConnection()
            doExchangeMsgs() && doExchangePeerInfo()
        } finally {
            pairingContext?.destroy()
            runCatching { if (::inputStream.isInitialized) inputStream.close() }
            runCatching { if (::outputStream.isInitialized) outputStream.close() }
            runCatching { socket?.close() }
        }
    }

    private fun setupTlsConnection() {
        val tcpSocket = Socket(host, port).also {
            it.tcpNoDelay = true
            socket = it
        }

        val sslSocket = adbKey.sslContext.socketFactory
            .createSocket(tcpSocket, host, port, true) as SSLSocket
        sslSocket.startHandshake()

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val codeBytes = pairCode.toByteArray(Charsets.UTF_8)
        val keyingMaterial = exportKeyingMaterial(sslSocket)
        val password = codeBytes + keyingMaterial

        pairingContext = PairingContext.create(password)
            ?: throw IllegalStateException("Failed to create SPAKE2+ PairingContext")
    }

    private fun doExchangeMsgs(): Boolean {
        val ctx = pairingContext ?: return false
        writePacket(SPAKE2_MSG, ctx.msg)
        val (type, payload) = readPacket() ?: return false
        if (type != SPAKE2_MSG) return false
        return ctx.initCipher(payload)
    }

    private fun doExchangePeerInfo(): Boolean {
        val ctx = pairingContext ?: return false

        val peerInfoBuf = ByteBuffer.allocate(PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        peerInfoBuf.put(ADB_RSA_PUB_KEY)
        val dataBuf = ByteArray(PEER_DATA_SIZE)
        val copyLen = minOf(adbKey.publicKeyBytes.size, PEER_DATA_SIZE)
        System.arraycopy(adbKey.publicKeyBytes, 0, dataBuf, 0, copyLen)
        peerInfoBuf.put(dataBuf)

        val encrypted = ctx.encrypt(peerInfoBuf.array()) ?: return false
        writePacket(PEER_INFO, encrypted)

        val (type, payload) = readPacket() ?: return false
        if (type != PEER_INFO) return false
        val decrypted = ctx.decrypt(payload) ?: return false
        return decrypted.size == PEER_INFO_SIZE
    }

    private fun writePacket(type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        header.put(HEADER_VERSION)
        header.put(type)
        header.putInt(payload.size)
        outputStream.write(header.array())
        outputStream.write(payload)
        outputStream.flush()
    }

    private fun readPacket(): Pair<Byte, ByteArray>? {
        val headerBuf = ByteArray(6)
        inputStream.readFully(headerBuf)
        val buf = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get()
        val type = buf.get()
        val size = buf.getInt()
        if (version != HEADER_VERSION || size <= 0 || size > 16384) return null
        val payload = ByteArray(size)
        inputStream.readFully(payload)
        return Pair(type, payload)
    }

    @Throws(IOException::class)
    private fun exportKeyingMaterial(socket: SSLSocket): ByteArray {
        return SSLSockets.exportKeyingMaterial(
            socket,
            KEYING_MATERIAL_LABEL,
            null,
            KEYING_MATERIAL_LENGTH
        ) ?: throw IOException("SSLSockets.exportKeyingMaterial returned null")
    }
}
