// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Locale

class AirMdnsDiscovery(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val serviceType: String,
    private val onResolved: (host: String, port: Int) -> Unit,
    private val onDiagnostic: (String) -> Unit = {}
) {
    private val nsdManager: NsdManager
        get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            nsdDiscoveryRequested = false
            onDiagnostic("nsd_start_failed service=$serviceType code=$errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            onDiagnostic("nsd_stop_failed service=$serviceType code=$errorCode")
        }
        override fun onDiscoveryStarted(serviceType: String) {
            registered = true
            onDiagnostic("nsd_started service=$serviceType")
        }
        override fun onDiscoveryStopped(serviceType: String) {
            registered = false
            nsdDiscoveryRequested = false
            onDiagnostic("nsd_stopped service=$serviceType")
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            onDiagnostic("service_found service=$serviceType name=${serviceInfo.serviceName}")
            synchronized(this@AirMdnsDiscovery) {
                resolveQueue.addLast(serviceInfo)
                processQueue()
            }
        }
    }

    @Volatile private var running = false
    @Volatile private var registered = false
    @Volatile private var nsdDiscoveryRequested = false
    @Volatile private var discovered = false
    @Volatile private var isResolving = false
    @Volatile private var rawMdnsJob: Job? = null
    @Volatile private var endpointProbeJob: Job? = null
    @Volatile private var endpointProbePort: Int = 0
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        discovered = false
        nsdDiscoveryRequested = true
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { error ->
            nsdDiscoveryRequested = false
            onDiagnostic(
                "nsd_start_exception service=$serviceType ${error::class.java.simpleName}: " +
                    error.message.orEmpty().take(120)
            )
        }
        startRawMdnsListener()
    }

    fun stop() {
        running = false
        synchronized(this) {
            resolveQueue.clear()
            isResolving = false
        }
        if (nsdDiscoveryRequested || registered) {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                .onFailure { error ->
                    onDiagnostic(
                        "nsd_stop_exception service=$serviceType ${error::class.java.simpleName}: " +
                            error.message.orEmpty().take(120)
                    )
                }
        }
        registered = false
        nsdDiscoveryRequested = false
        rawMdnsJob?.cancel()
        rawMdnsJob = null
        endpointProbeJob?.cancel()
        endpointProbeJob = null
        endpointProbePort = 0
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun processQueue() {
        if (isResolving || !running || discovered) return
        val serviceInfo = resolveQueue.removeFirstOrNull() ?: return
        isResolving = true
        runCatching {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    onDiagnostic("nsd_resolve_failed service=$serviceType code=$errorCode")
                    synchronized(this@AirMdnsDiscovery) {
                        isResolving = false
                        processQueue()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: LOCAL_ADB_HOST
                    if (isLocalMdnsHost(host)) {
                        probeLocalEndpointWhenReady(serviceInfo.port)
                    } else {
                        onDiagnostic(
                            "resolved_non_local service=$serviceType host=$host port=${serviceInfo.port}"
                        )
                    }
                    synchronized(this@AirMdnsDiscovery) {
                        isResolving = false
                        processQueue()
                    }
                }
            })
        }.onFailure { error ->
            onDiagnostic(
                "nsd_resolve_exception service=$serviceType ${error::class.java.simpleName}: " +
                    error.message.orEmpty().take(120)
            )
            synchronized(this@AirMdnsDiscovery) {
                isResolving = false
                processQueue()
            }
        }
    }

    private fun notifyResolved(host: String, port: Int) {
        val accepted = synchronized(this) {
            if (!running || discovered || port <= 0) {
                false
            } else {
                discovered = true
                true
            }
        }
        if (!accepted) return
        onDiagnostic("resolved service=$serviceType host=$host port=$port")
        onResolved(host, port)
    }

    private fun probeLocalEndpointWhenReady(port: Int) {
        if (port <= 0 || !running || discovered) return
        if (endpointProbePort == port && endpointProbeJob?.isActive == true) return
        endpointProbeJob?.cancel()
        endpointProbePort = port
        endpointProbeJob = appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repeat(ENDPOINT_READY_ATTEMPTS) { attempt ->
                if (!running || discovered) return@launch
                if (isLocalPortOccupied(port)) {
                    notifyResolved(LOCAL_ADB_HOST, port)
                    return@launch
                }
                if (attempt == 0) {
                    onDiagnostic("endpoint_not_ready service=$serviceType port=$port")
                }
                delay(ENDPOINT_READY_RETRY_MS)
            }
            onDiagnostic("endpoint_probe_exhausted service=$serviceType port=$port")
        }
    }

    private fun startRawMdnsListener() {
        acquireMulticastLock()
        rawMdnsJob?.cancel()
        rawMdnsJob = appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var socket: MulticastSocket? = null
            try {
                val group = InetAddress.getByName(MDNS_ADDR)
                socket = MulticastSocket(MDNS_PORT).apply {
                    reuseAddress = true
                    joinGroup(group)
                    soTimeout = RAW_MDNS_TIMEOUT_MS
                }
                val buffer = ByteArray(RAW_MDNS_PACKET_SIZE)
                while (running && !discovered) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val port = parseMdnsResponse(packet.data, packet.length)
                        if (port > 0) {
                            probeLocalEndpointWhenReady(port)
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onDiagnostic(
                    "raw_listener_failed service=$serviceType ${error::class.java.simpleName}: " +
                        error.message.orEmpty().take(120)
                )
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("droidance_air_mdns")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { error ->
                    onDiagnostic(
                        "multicast_lock_failed service=$serviceType ${error::class.java.simpleName}: " +
                            error.message.orEmpty().take(120)
                    )
                }
        }
    }

    private fun isLocalMdnsHost(host: String): Boolean {
        if (host == LOCAL_ADB_HOST) return true
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrNull()
            ?: return false
        return interfaces
            .filter { networkInterface ->
                LOCAL_WIFI_INTERFACE_MARKERS.any { marker ->
                    networkInterface.name.contains(marker, ignoreCase = true)
                }
            }
            .flatMap { networkInterface ->
                runCatching { networkInterface.inetAddresses.toList() }.getOrDefault(emptyList())
            }
            .any { address -> address.hostAddress == host }
    }

    private fun isLocalPortOccupied(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(LOCAL_ADB_HOST, port), 1)
            }
            false
        } catch (_: Exception) {
            true
        }
    }

    private fun parseMdnsResponse(data: ByteArray, length: Int): Int {
        if (length < DNS_HEADER_LENGTH) return -1
        val packet = data.copyOf(length)
        val buffer = ByteBuffer.wrap(packet)
        buffer.short
        val flags = buffer.short.toInt() and 0xffff
        if ((flags and DNS_RESPONSE_FLAG) == 0) return -1
        val questionCount = buffer.short.toInt() and 0xffff
        val answerCount = buffer.short.toInt() and 0xffff
        val authorityCount = buffer.short.toInt() and 0xffff
        val additionalCount = buffer.short.toInt() and 0xffff

        repeat(questionCount) {
            if (!skipDnsName(buffer) || buffer.remaining() < 4) return -1
            buffer.position(buffer.position() + 4)
        }

        val expectedSuffix = serviceType
            .lowercase(Locale.ROOT)
            .removeSuffix(".local")
            .let { "$it.local" }
        repeat(answerCount + authorityCount + additionalCount) {
            if (buffer.remaining() < 12) return -1
            val name = readDnsName(packet, buffer)?.lowercase(Locale.ROOT) ?: return -1
            if (buffer.remaining() < 10) return -1
            val type = buffer.short.toInt() and 0xffff
            buffer.short
            buffer.int
            val dataLength = buffer.short.toInt() and 0xffff
            if (buffer.remaining() < dataLength) return -1
            val dataStart = buffer.position()
            if (type == DNS_TYPE_SRV && name.endsWith(expectedSuffix) && dataLength >= 6) {
                buffer.short
                buffer.short
                return buffer.short.toInt() and 0xffff
            }
            buffer.position(dataStart + dataLength)
        }
        return -1
    }

    private fun skipDnsName(buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            val len = buffer.get().toInt() and 0xff
            when {
                len == 0 -> return true
                (len and DNS_POINTER_MASK) == DNS_POINTER_MASK -> {
                    if (!buffer.hasRemaining()) return false
                    buffer.get()
                    return true
                }
                buffer.remaining() < len -> return false
                else -> buffer.position(buffer.position() + len)
            }
        }
        return false
    }

    private fun readDnsName(data: ByteArray, buffer: ByteBuffer): String? {
        val labels = mutableListOf<String>()
        var offset = buffer.position()
        var jumped = false
        var jumpReturn = -1
        repeat(MAX_DNS_NAME_LABELS) {
            if (offset >= data.size) return null
            val len = data[offset].toInt() and 0xff
            when {
                len == 0 -> {
                    offset += 1
                    buffer.position(if (jumped && jumpReturn >= 0) jumpReturn else offset)
                    return labels.joinToString(".")
                }
                (len and DNS_POINTER_MASK) == DNS_POINTER_MASK -> {
                    if (offset + 1 >= data.size) return null
                    if (!jumped) jumpReturn = offset + 2
                    offset = ((len and DNS_POINTER_VALUE_MASK) shl 8) or (data[offset + 1].toInt() and 0xff)
                    jumped = true
                }
                else -> {
                    val start = offset + 1
                    val end = start + len
                    if (end > data.size) return null
                    labels += String(data, start, len, Charsets.UTF_8)
                    offset = end
                    if (!jumped) buffer.position(offset)
                }
            }
        }
        return null
    }

    private companion object {
        const val LOCAL_ADB_HOST = "127.0.0.1"
        const val MDNS_ADDR = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val DNS_HEADER_LENGTH = 12
        const val DNS_RESPONSE_FLAG = 0x8000
        const val DNS_POINTER_MASK = 0xc0
        const val DNS_POINTER_VALUE_MASK = 0x3f
        const val DNS_TYPE_SRV = 33
        const val MAX_DNS_NAME_LABELS = 64
        const val RAW_MDNS_PACKET_SIZE = 1500
        const val RAW_MDNS_TIMEOUT_MS = 2_000
        const val ENDPOINT_READY_ATTEMPTS = 20
        const val ENDPOINT_READY_RETRY_MS = 150L
        val LOCAL_WIFI_INTERFACE_MARKERS = listOf("wlan", "wifi", "wl")
    }
}
