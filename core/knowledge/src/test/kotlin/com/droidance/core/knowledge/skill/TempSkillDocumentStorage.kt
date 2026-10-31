// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A real on-disk storage used by SkillEngine tests; no fake directory state is involved. */
internal class TempSkillDocumentStorage : SkillDocumentStorage, AutoCloseable {
    val root: Path = Files.createTempDirectory("droidance-skill-test-")

    override fun readFile(relativePath: String): String? {
        val path = resolve(relativePath)
        return path.takeIf(Files::isRegularFile)?.let { Files.readString(it) }
    }

    override fun writeFile(relativePath: String, content: String) {
        val path = resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    override fun exists(relativePath: String): Boolean = Files.exists(resolve(relativePath))

    override fun readFrontMatter(relativePath: String): String? {
        val content = readFile(relativePath)?.replace("\r\n", "\n") ?: return null
        if (!content.startsWith("---\n")) return null
        val end = content.indexOf("\n---", startIndex = 4)
        return end.takeIf { it >= 0 }?.let { content.substring(4, it).trim() }
    }

    override fun listFiles(directoryPath: String): List<String> {
        val directory = resolve(directoryPath)
        if (!Files.isDirectory(directory)) return emptyList()
        Files.list(directory).use { children ->
            return children.iterator().asSequence()
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
    }

    override fun replaceDirectory(relativePath: String, files: Map<String, String>) {
        deleteDirectory(relativePath)
        val directory = resolve(relativePath)
        Files.createDirectories(directory)
        files.forEach { (path, content) -> writeFile("$relativePath/$path", content) }
    }

    override fun deleteDirectory(relativePath: String): Boolean {
        val directory = resolve(relativePath)
        if (!Files.exists(directory)) return false
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        return true
    }

    override fun close() {
        deleteDirectory("")
    }

    private fun resolve(relativePath: String): Path {
        val path = root.resolve(relativePath).normalize()
        require(path.startsWith(root)) { "test storage path escaped root: $relativePath" }
        return path
    }
}
