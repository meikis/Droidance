// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable source files only. Execution records and private demonstration images never travel. */
internal object SkillPackageArchive {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_FILES = 48

    fun validatePath(path: String) {
        require(path.count { it == '/' } <= 8) { "SKILL_PATH_DEPTH" }
        require(path.length in 1..180 && '\\' !in path && '\u0000' !in path) { "SKILL_PATH_INVALID" }
        require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "SKILL_PATH_INVALID" }
        require(path == "SKILL.md" ||
            (path.startsWith("scripts/") && path.endsWith(".js")) ||
            (path.startsWith("references/") && path.endsWith(".md"))) { "SKILL_FILE_UNSUPPORTED: $path" }
    }

    fun validate(files: Map<String, String>) {
        require(files.size in 1..MAX_FILES && !files["SKILL.md"].isNullOrBlank()) { "SKILL_DOCUMENT_REQUIRED" }
        var total = 0
        files.forEach { (path, text) ->
            validatePath(path)
            val size = text.toByteArray(Charsets.UTF_8).size
            require(size <= MAX_FILE_BYTES) { "SKILL_FILE_LIMIT" }
            total += size
        }
        require(total <= MAX_BYTES) { "SKILL_PACKAGE_LIMIT" }
    }

    fun decode(input: InputStream): Map<String, String> {
        val bytes = readBounded(input, MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "SKILL_PACKAGE_LIMIT" }
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): Map<String, String> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "SKILL_PACKAGE_LIMIT" }
        if (!bytes.take(2).toByteArray().contentEquals(byteArrayOf(80, 75))) {
            return mapOf("SKILL.md" to utf8(bytes)).also(::validate)
        }
        val files = linkedMapOf<String, String>()
        var total = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    val directory = entry.name.removeSuffix("/")
                    require(directory == "scripts" || directory == "references" || directory.startsWith("scripts/") || directory.startsWith("references/")) { "SKILL_PATH_INVALID" }
                    require(directory.length <= 180 && '\\' !in directory && '\u0000' !in directory &&
                        directory.split('/').all { it.isNotBlank() && it !in setOf(".", "..") }) { "SKILL_PATH_INVALID" }
                    continue
                }
                validatePath(entry.name)
                require(entry.name !in files && files.size < MAX_FILES) { "SKILL_DUPLICATE_OR_FILE_LIMIT" }
                val data = readBounded(zip, MAX_FILE_BYTES + 1)
                require(data.size <= MAX_FILE_BYTES) { "SKILL_FILE_LIMIT" }
                total += data.size
                require(total <= MAX_BYTES) { "SKILL_PACKAGE_LIMIT" }
                files[entry.name] = utf8(data)
            }
        }
        return files.also(::validate)
    }

    fun encode(files: Map<String, String>): ByteArray {
        validate(files)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.toSortedMap().forEach { (path, value) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        } }
        return out.toByteArray()
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            if (count == 0) continue
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}

data class SkillInstallResult(val skillId: String, val name: String, val version: Int, val updated: Boolean)
