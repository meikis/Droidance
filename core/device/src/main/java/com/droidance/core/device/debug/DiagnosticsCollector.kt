// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.debug

import com.droidance.core.agent.debug.*

import com.droidance.core.device.debug.*

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Focused operational diagnostics that are not task facts.
 *
 * Task identity, lifecycle, replay, and completion live only in AgentEventStore. This collector
 * keeps bounded channel evidence plus the latest CUA frame metadata used by debug export.
 */
@Singleton
class DiagnosticsCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class TokenUsageSnapshot(
        val promptTokens: Int,
        val completionTokens: Int
    )

    private val dataDir: File by lazy {
        val baseDir = context.noBackupFilesDir ?: context.filesDir
        File(baseDir, "droidance-data/diagnostics").also { it.mkdirs() }
    }
    private val inputDir by lazy { File(dataDir, "input").also { it.mkdirs() } }
    private val tsFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
    private val appendLock = Any()

    @Volatile private var activeTaskId: String = ""
    @Volatile private var promptTokenCount: Int = 0
    @Volatile private var completionTokenCount: Int = 0
    @Volatile private var cuaFrameModelGrid: String = ""
    @Volatile private var cuaFramePhysicalSize: String = ""
    @Volatile private var cuaFrameScale: String = ""
    @Volatile private var cuaFrameDetailMode: String = ""
    @Volatile private var cuaFramePerceptionMode: String = ""

    private fun typeTextFile(): File = File(inputDir, "type-text.jsonl")
    private fun injectionFile(): File = File(inputDir, "injection.jsonl")
    private fun envFile(): File = File(dataDir, "env/snapshot.json").also { it.parentFile?.mkdirs() }

    fun modelDiagnosticsDirectory(): File = File(dataDir, "model")

    fun startTaskDiagnostics(taskId: String) {
        activeTaskId = taskId
    }

    fun finishTaskDiagnostics(taskId: String) {
        if (activeTaskId == taskId) activeTaskId = ""
    }

    fun logTypeText(
        textPreview: String,
        path: String,
        success: Boolean,
        durationMs: Long,
        reason: String = ""
    ) {
        appendJson(typeTextFile(), buildString {
            append("{")
            append("\"ts\":\"${tsFormat.format(Date())}\"")
            append(",\"task_id\":\"${escape(activeTaskId)}\"")
            append(",\"text\":\"${escape(textPreview.take(20))}\"")
            append(",\"path\":\"${escape(path)}\"")
            append(",\"success\":$success")
            append(",\"ms\":$durationMs")
            append(",\"reason\":\"${escape(reason)}\"")
            append("}")
        })
    }

    fun recordInjection(op: String, mode: String, path: String, success: Boolean, detail: String = "") {
        appendJson(injectionFile(), buildString {
            append("{")
            append("\"ts\":\"${tsFormat.format(Date())}\"")
            append(",\"task_id\":\"${escape(activeTaskId)}\"")
            append(",\"operation\":\"${escape(op)}\"")
            append(",\"mode\":\"${escape(mode)}\"")
            append(",\"path\":\"${escape(path)}\"")
            append(",\"success\":$success")
            if (detail.isNotBlank()) append(",\"detail\":\"${escape(detail.take(400))}\"")
            append("}")
        })
    }

    fun recordObservation(displayId: Int, stage: String, success: Boolean, detail: String) {
        val file = File(dataDir, "observation/frames.jsonl").also { it.parentFile?.mkdirs() }
        appendJson(file, org.json.JSONObject().apply {
            put("occurred_at", System.currentTimeMillis())
            put("task_id", activeTaskId)
            put("display_id", displayId)
            put("stage", stage)
            put("success", success)
            put("detail", detail.take(1000))
        }.toString())
    }

    fun recordCuaFrame(
        perceptionMode: String,
        modelGrid: Pair<Int, Int>?,
        physicalSize: Pair<Int, Int>?,
        frameScale: Float,
        detailMode: String
    ) {
        cuaFramePerceptionMode = perceptionMode
        cuaFrameModelGrid = modelGrid?.let { "${it.first}x${it.second}" }.orEmpty()
        cuaFramePhysicalSize = physicalSize?.let { "${it.first}x${it.second}" }.orEmpty()
        cuaFrameScale = String.format(Locale.US, "%.4f", frameScale)
        cuaFrameDetailMode = detailMode
    }

    fun recordTokenUsage(promptTokens: Int, completionTokens: Int) {
        promptTokenCount += promptTokens.coerceAtLeast(0)
        completionTokenCount += completionTokens.coerceAtLeast(0)
    }

    fun tokenUsageSnapshot(): TokenUsageSnapshot = TokenUsageSnapshot(
        promptTokens = promptTokenCount,
        completionTokens = completionTokenCount
    )

    fun writeEnvSnapshot(
        deviceModel: String,
        androidVersion: String,
        sdkInt: String,
        adbShellAvailable: Boolean,
        imeId: String?,
        buildType: String,
        airPreviouslyPaired: Boolean = false,
        airPairingState: String = "",
        airPairingTrustState: String = "",
        airWifiTransportAvailable: Boolean? = null,
        airShellReady: Boolean = false,
        airShellState: String = "",
        airShellLastFailure: String = "",
        airShellLastCheckedAtMs: Long = 0L,
        airActiveEndpoint: String = "",
        airEndpointSource: String = "",
        airShellLastProbeOutput: String = ""
    ) {
        envFile().writeText(buildString {
            append("{")
            append("\"exported_at\":\"${tsFormat.format(Date())}\"")
            append(",\"package\":\"${escape(context.packageName)}\"")
            append(",\"build\":\"${escape(buildType)}\"")
            append(",\"device_model\":\"${escape(deviceModel)}\"")
            append(",\"android_version\":\"${escape(androidVersion)}\"")
            append(",\"sdk_int\":\"${escape(sdkInt)}\"")
            append(",\"adb_shell_available\":$adbShellAvailable")
            append(",\"air_previously_paired\":$airPreviouslyPaired")
            append(",\"air_pairing_state\":\"${escape(airPairingState)}\"")
            append(",\"air_pairing_trust_state\":\"${escape(airPairingTrustState)}\"")
            append(",\"air_wifi_transport_available\":$airWifiTransportAvailable")
            append(",\"air_shell_ready\":$airShellReady")
            append(",\"air_shell_state\":\"${escape(airShellState)}\"")
            append(",\"air_shell_last_failure\":\"${escape(airShellLastFailure)}\"")
            append(",\"air_shell_last_checked_at_ms\":$airShellLastCheckedAtMs")
            append(",\"air_active_endpoint\":\"${escape(airActiveEndpoint)}\"")
            append(",\"air_endpoint_source\":\"${escape(airEndpointSource)}\"")
            append(",\"air_last_probe_output\":\"${escape(airShellLastProbeOutput.take(800))}\"")
            append(",\"cua_frame\":{")
            append("\"model_grid\":\"${escape(cuaFrameModelGrid)}\"")
            append(",\"physical_size\":\"${escape(cuaFramePhysicalSize)}\"")
            append(",\"frame_scale\":\"${escape(cuaFrameScale)}\"")
            append(",\"detail_mode\":\"${escape(cuaFrameDetailMode)}\"")
            append(",\"perception_mode\":\"${escape(cuaFramePerceptionMode)}\"")
            append("}")
            if (imeId != null) append(",\"ime_id\":\"${escape(imeId)}\"")
            append("}")
        })
    }

    fun clearAll() {
        synchronized(appendLock) {
            if (dataDir.exists()) {
                java.nio.file.Files.walk(dataDir.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete)
                }
            }
            java.nio.file.Files.createDirectories(dataDir.toPath())
        }
    }

    private fun appendJson(file: File, jsonLine: String) {
        synchronized(appendLock) {
            java.nio.file.Files.createDirectories(file.parentFile.toPath())
            file.appendText(jsonLine + "\n")
        }
    }

    private fun escape(value: String?): String {
        if (value == null) return ""
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
