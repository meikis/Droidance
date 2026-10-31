// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

object AirAdbPortPolicy {
    private const val LEGACY_ADB_TCPIP_PORT = 5555

    // lastPort 是同一无线调试 TLS 服务仍存活时的快速候选；WiFi 断开或切换后 Android 可能
    // 已关闭旧服务，因此它只用于快速尝试，失败后仍须依赖 Settings/mDNS 的当前端口。
    fun connectPorts(lastPort: Int?, settingsPort: Int?): List<Int> = buildList {
        addIfValid(lastPort)
        addIfValid(settingsPort)
    }.distinct()

    private fun MutableList<Int>.addIfValid(port: Int?) {
        if (port != null && port in 1..65535 && port != LEGACY_ADB_TCPIP_PORT) add(port)
    }
}
