// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

internal object ResidentSidecarProcessCommands {
    fun listPidsCommand(
        uid: Int,
        protocolVersion: Int? = null
    ): String {
        require(uid == 2000) { "unsupported sidecar uid=$uid" }
        val identity = "(${'$'}1 == \"shell\" || ${'$'}1 == \"2000\")"
        if (protocolVersion != null) {
            require(protocolVersion > 0) { "invalid sidecar protocol=$protocolVersion" }
        }
        val protocol = protocolVersion
            ?.let { " && /[d]roidance-sidecar-protocol=$it/" }
            .orEmpty()
        return "ps -A -o UID,PID,ARGS | " +
            "awk '$identity && /[V]irtualSpaceSidecarMain/$protocol { print ${'$'}2 }'"
    }

    fun parsePids(output: String): List<Int> =
        output.lineSequence()
            .mapNotNull { line -> line.trim().toIntOrNull() }
            .filter { pid -> pid > 1 }
            .distinct()
            .toList()

    /**
     * The supervisor restarts a sidecar that was killed or crashed, without needing ADB. Exit code 0
     * means an intentional stop or a duplicate. Sidecar arguments stay in the environment so the
     * process probes, which match on them, never mistake the supervisor for a sidecar.
     */
    fun launchCommand(
        apkPath: String,
        mainClass: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        packageName: String,
        protocolVersion: Int,
        escape: (String) -> String
    ): String {
        val supervisor = "while :; do " +
            "app_process /system/bin \"\$DROIDANCE_SIDECAR_MAIN\" $width $height $densityDpi " +
            "\"\$DROIDANCE_SIDECAR_PACKAGE\" \"\$DROIDANCE_SIDECAR_PROTOCOL\" \"\$DROIDANCE_SIDECAR_SOURCE\"; " +
            "[ \$? -eq 0 ] && break; " +
            "sleep $RESTART_DELAY_SECONDS; " +
            "[ -f \"\$CLASSPATH\" ] || break; " +
            "[ \"\$(settings get global development_settings_enabled 2>/dev/null)\" != 0 ] || break; " +
            "done"
        return buildString {
            // 不能在同一条 sh -c 里按 mainClass 清进程，否则会匹配当前命令文本并杀掉启动过程。
            append("export CLASSPATH=${escape(apkPath)}; ")
            append("export DROIDANCE_SIDECAR_MAIN=${escape(mainClass)}; ")
            append("export DROIDANCE_SIDECAR_PACKAGE=${escape(packageName)}; ")
            append("export DROIDANCE_SIDECAR_PROTOCOL=${escape("droidance-sidecar-protocol=$protocolVersion")}; ")
            append("export DROIDANCE_SIDECAR_SOURCE=${escape("droidance-sidecar-source=$apkPath")}; ")
            // setsid 让 sidecar 脱离 AIR/AirAdbClient 的一次性 shell 会话，避免 shell 连接关闭时
            // SIGHUP 杀掉后台 sidecar 进程（真机 binder timeout 的疑似主因；模拟器手动 adb shell 不复现）。
            // setsid 脱离一次性 shell 会话 + </dev/null 关闭 stdin 防 SIGPIPE，
            // 确保 AirAdbClient 连接关闭后 sidecar 仍存活到完成 binder 广播。
            append("(setsid sh -c ${escape(supervisor)} ")
            append("</dev/null >/data/local/tmp/droidance_vspace.log 2>&1 &)")
        }
    }

    /** Signals the whole process group, otherwise the supervisor would restart the sidecar. */
    fun stopCommand(pids: Collection<Int>, force: Boolean): String? {
        val signal = if (force) "-9 " else ""
        return pids.asSequence()
            .filter { pid -> pid > 1 }
            .distinct()
            .joinToString("; ") { pid ->
                "g=\$(cut -d' ' -f5 /proc/$pid/stat 2>/dev/null); " +
                    "[ \"\${g:-0}\" -gt 1 ] && kill ${signal}-- -\$g 2>/dev/null; " +
                    "kill ${signal}${pid} 2>/dev/null"
            }
            .ifBlank { null }
    }

    private const val RESTART_DELAY_SECONDS = 5
}
