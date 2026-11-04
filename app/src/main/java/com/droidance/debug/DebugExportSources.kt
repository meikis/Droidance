// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.event.AgentEventEnvelope
import java.io.File
import java.nio.file.Files

/** Enumerates retained diagnostics without following symlinks or exporting provider settings. */
internal object DebugExportSources {
    /** The captured paths remain live until the archive has consumed every supplement. */
    suspend fun withSources(root: File, cacheDir: File, snapshot: List<AgentEventEnvelope>,
                            write: suspend (List<DebugArchiveWriter.Supplement>) -> Unit) {
        try {
            write(collect(root, snapshot))
        } finally {
            if (root.parentFile?.canonicalFile == cacheDir.canonicalFile && root.name.startsWith("runtime-export-")) {
                root.deleteRecursively()
            }
        }
    }

    fun collect(root: File, snapshot: List<AgentEventEnvelope>): List<DebugArchiveWriter.Supplement> = buildList {
        val retainedScopes = snapshot.mapNotNull { it.turnId }.toSet().map(ProviderExchangeRecorder::scopeDirectory).toSet()
        addAll(files(File(root, "diagnostics"), "diagnostics").filter { file ->
            val scope = file.path.removePrefix("diagnostics/model/").substringBefore('/')
            !file.path.startsWith("diagnostics/model/") || scope == "unscoped" ||
                scope == "recording-errors.jsonl" || scope in retainedScopes
        })
        addAll(files(File(root, "debug"), "logs"))
        listOf("global", "rules", "skills").forEach { directory ->
            addAll(files(File(root, directory), "current/$directory", markdownOnly = true))
        }
    }

    private fun files(root: File, prefix: String, markdownOnly: Boolean = false): List<DebugArchiveWriter.Supplement> {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
        val canonicalRoot = root.canonicalFile
        return root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                file.canonicalFile.toPath().startsWith(canonicalRoot.toPath()) &&
                (!markdownOnly || file.extension == "md")
        }.map { file ->
            DebugArchiveWriter.Supplement("$prefix/${file.relativeTo(root).invariantSeparatorsPath}", file)
        }.sortedBy { it.path }.toList()
    }
}
