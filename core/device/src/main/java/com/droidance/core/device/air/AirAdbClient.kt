// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

class AirAdbClient(
    private val host: String,
    private val port: Int,
    private val adbKey: AirAdbKey,
    private val allowUserAuthorization: Boolean = false
) : Closeable {
    private var socket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private val nextLocalId = AtomicInteger(1)
    @Volatile private var connected = false
    // 整条 ADB socket 是单连接复用，多线程同时 shell() 会让 header/payload 在 stream 上交错，
    // 导致一个命令读到另一个命令的 stdout（典型表现：base64 输出里夹着 dumpsys 输出和
    // __DROIDANCE_EXIT_ marker）。这把锁让 shell() 调用一次只跑一个，避免命令交错。
    private val shellLock = Any()

    fun connect() {
        if (connected) return
        val tcpSocket = Socket().also {
            it.tcpNoDelay = true
            it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // 关键修复：给 socket 设读超时。半开连接(对端 wifi 掉线/睡眠且未发 RST)下，
            // readFully 没有读超时会一直阻塞到 OS 级 TCP 超时(数分钟)——这是后台任务
            // "执行得非常慢、基本不成功"的真凶(一次截图/注入卡死 6 分钟直到 wall 超时)。
            // 设了 soTimeout 后任何卡死的读会在秒级抛 SocketTimeoutException，上层据此
            // markShellDown + 重连，循环不再被一次读永久挂死。
            it.soTimeout = READ_TIMEOUT_MS
            socket = it
        }
        inputStream = DataInputStream(tcpSocket.inputStream)
        outputStream = DataOutputStream(tcpSocket.outputStream)

        writeMessage(A_CNXN, A_VERSION, A_MAXDATA, "host::".adbPayload())
        var message = readMessage()
        when (message.command) {
            A_STLS -> {
                writeMessage(A_STLS, A_VERSION, 0, null)
                val sslSocket = adbKey.sslContext.socketFactory
                    .createSocket(tcpSocket, host, port, true) as SSLSocket
                tlsSocket = sslSocket
                sslSocket.soTimeout = READ_TIMEOUT_MS
                try {
                    sslSocket.startHandshake()
                } catch (error: SSLHandshakeException) {
                    if (AirAdbConnectionFailurePolicy.isExplicitTlsAuthorizationRejection(error)) {
                        throw AirAdbAuthorizationException(
                            "Wireless debugging rejected the paired ADB certificate",
                            error
                        )
                    }
                    throw error
                }
                inputStream = DataInputStream(sslSocket.inputStream)
                outputStream = DataOutputStream(sslSocket.outputStream)
                message = readMessage()
            }
            A_AUTH -> {
                message = authenticate(message)
            }
        }
        if (message.command != A_CNXN) {
            if (message.command == A_AUTH) {
                throw AirAdbAuthorizationException("Wireless debugging rejected the paired ADB key")
            }
            throw IllegalStateException("ADB connection failed: command=${message.command}")
        }
        connected = true
    }

    fun shell(command: String, service: String = "shell"): String {
        val bytes = requestService("$service:$command")
        return if (service.split(',').contains("v2")) decodeShellV2(bytes) else bytes.toString(Charsets.UTF_8)
    }

    fun restartTcpip(port: Int): String {
        require(port in 49152..65535)
        return requestService("tcpip:$port").toString(Charsets.UTF_8)
    }

    fun restartUsb(): String = requestService("usb:").toString(Charsets.UTF_8)

    private fun requestService(destination: String): ByteArray = synchronized(shellLock) {
        connect()
        val localId = nextLocalId.getAndIncrement()
        val output = ByteArrayOutputStream()
        writeMessage(A_OPEN, localId, 0, destination.adbPayload())
        var accepted = false

        while (true) {
            val message = readMessage()
            // ADB 协议中 server → client 的所有响应包 arg1 = client 的 local-id。
            // 如果不是我们这条流的消息，说明是上一次 shell() 留在 socket 缓冲里的
            // 残留 A_WRTE / A_CLSE（典型表现：dumpsys 的输出泄漏到 screencap+base64 里）。
            // 直接跳过，不要拌进 output；不需要 ACK，server 那边的流早已关闭。
            if (message.arg1 != localId) continue
            when (message.command) {
                A_OKAY -> accepted = true
                A_WRTE -> {
                    output.write(message.payload)
                    writeMessage(A_OKAY, localId, message.arg0, null)
                }
                A_CLSE -> {
                    check(accepted) { "ADB service refused: $destination" }
                    writeMessage(A_CLSE, localId, message.arg0, null)
                    return output.toByteArray()
                }
                else -> throw IllegalStateException("Unexpected ADB shell packet: command=${message.command}")
            }
        }
        @Suppress("UNREACHABLE_CODE") byteArrayOf()
    }

    /** shell,v2 wraps stdout/stderr in binary frames, even for a single PID line. */
    private fun decodeShellV2(bytes: ByteArray): String {
        val frames = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteArrayOutputStream()
        while (frames.hasRemaining()) {
            check(frames.remaining() >= 5) { "Truncated ADB shell v2 header" }
            val stream = frames.get().toInt() and 255
            val length = frames.int
            check(length >= 0 && length <= frames.remaining()) { "Invalid ADB shell v2 payload length" }
            val payload = ByteArray(length).also { frames.get(it) }
            when (stream) {
                1, 2 -> output.write(payload)
                3 -> Unit // Exit status is a control frame, never command output.
                else -> error("Unexpected ADB shell v2 stream: $stream")
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun authenticate(firstAuth: AdbMessage): AdbMessage {
        if (firstAuth.arg0 != ADB_AUTH_TOKEN) {
            throw IllegalStateException("Unsupported ADB auth type: ${firstAuth.arg0}")
        }
        writeMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, adbKey.signToken(firstAuth.payload))
        var message = readMessage()
        if (message.command == A_AUTH && allowUserAuthorization) {
            // A TLS-paired key may still need one explicit approval for the classic TCP transport.
            writeMessage(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, adbKey.publicKeyBytes)
            message = readMessage()
        }
        if (message.command == A_AUTH) {
            throw AirAdbAuthorizationException("ADB rejected the key or is awaiting user authorization")
        }
        return message
    }

    private fun writeMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray?) {
        val bytes = payload ?: ByteArray(0)
        val header = ByteBuffer.allocate(ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(bytes.size)
            .putInt(bytes.checksum())
            .putInt(command xor -1)
            .array()
        outputStream.write(header)
        if (bytes.isNotEmpty()) outputStream.write(bytes)
        outputStream.flush()
    }

    private fun readMessage(): AdbMessage {
        val header = ByteArray(ADB_HEADER_LENGTH)
        inputStream.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val payloadLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        if (magic != (command xor -1)) {
            throw IllegalStateException("Invalid ADB packet magic")
        }
        if (payloadLength < 0 || payloadLength > A_MAXDATA) {
            throw IllegalStateException("Invalid ADB payload length: $payloadLength")
        }
        val payload = ByteArray(payloadLength)
        if (payloadLength > 0) inputStream.readFully(payload)
        if (payload.checksum() != checksum) {
            throw IllegalStateException("Invalid ADB payload checksum")
        }
        return AdbMessage(command, arg0, arg1, payload)
    }

    override fun close() {
        connected = false
        runCatching { if (::inputStream.isInitialized) inputStream.close() }
        runCatching { if (::outputStream.isInitialized) outputStream.close() }
        runCatching { tlsSocket?.close() }
        runCatching { socket?.close() }
        tlsSocket = null
        socket = null
    }

    private data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453
        const val A_VERSION = 0x01000000
        const val A_MAXDATA = 1024 * 1024
        const val ADB_HEADER_LENGTH = 24
        const val ADB_AUTH_TOKEN = 1
        const val ADB_AUTH_SIGNATURE = 2
        const val ADB_AUTH_RSAPUBLICKEY = 3
        const val CONNECT_TIMEOUT_MS = 5_000
        // 单次 socket 读的硬上限。正常 screencap 首字节 ~1-2s、健康检查近乎瞬时，
        // 10s 足以覆盖任何合法读，又能把半死连接的卡死收敛到秒级而非数分钟。
        // 截图/注入等热路径另有更紧的 wrapper 超时(8s/15s)，此值是覆盖全部读路径的兜底地板。
        const val READ_TIMEOUT_MS = 10_000

        fun String.adbPayload(): ByteArray = toByteArray(Charsets.UTF_8) + 0.toByte()

        fun ByteArray.checksum(): Int {
            var sum = 0
            forEach { sum += it.toInt() and 0xff }
            return sum
        }
    }
}
