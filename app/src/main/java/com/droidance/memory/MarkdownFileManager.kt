// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Markdown 文件管理器
 * 负责 Droidance 本地记忆系统的文件读写操作
 * 所有记忆数据以 Markdown 文件存储（File-First 理念）
 */
@Singleton
class MarkdownFileManager @Inject constructor(
    @ApplicationContext private val context: Context
) : MemoryDocumentStorage, com.droidance.core.knowledge.skill.SkillDocumentStorage {
    private val ioLock = ReentrantReadWriteLock()

    // 数据根目录：权威位置收敛至内部私有目录（noBackupFilesDir）
    private val dataDir: File by lazy {
        val baseDir = context.noBackupFilesDir ?: context.filesDir
        val internalRoot = File(baseDir, "droidance-data")
        migrateExternalDataIfNeeded(internalRoot)
        internalRoot
    }

    private fun migrateExternalDataIfNeeded(targetDir: File) {
        val externalBase = context.getExternalFilesDir(null) ?: return
        val externalDir = File(externalBase, "droidance-data")
        if (externalDir.exists() && externalDir.isDirectory) {
            externalDir.walkTopDown().forEach { sourceFile ->
                val relPath = sourceFile.relativeTo(externalDir).path
                val destFile = File(targetDir, relPath)
                if (sourceFile.isDirectory) {
                    destFile.mkdirs()
                } else if (!destFile.exists()) {
                    destFile.parentFile?.mkdirs()
                    sourceFile.copyTo(destFile, overwrite = false)
                }
            }
        }
    }

    private fun resolve(relativePath: String): File {
        require(!File(relativePath).isAbsolute) { "Absolute paths are not allowed: $relativePath" }
        val root = dataDir.canonicalFile
        val file = File(root, relativePath).canonicalFile
        val isInsideRoot = file.path == root.path || file.path.startsWith(root.path + File.separator)
        require(isInsideRoot) { "Path escapes the managed root: $relativePath" }
        return file
    }

    /** 读取指定路径的 Markdown 文件 */
    override fun readFile(relativePath: String): String? {
        return ioLock.read {
            val file = resolve(relativePath)
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        }
    }

    /** Reads only the leading YAML front matter and stops before the Markdown body. */
    override fun readFrontMatter(relativePath: String): String? = ioLock.read {
        val file = resolve(relativePath)
        if (!file.exists() || !file.isFile) return@read null
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            if (reader.readLine()?.trim() != "---") return@use null
            buildString {
                while (true) {
                    val line = reader.readLine() ?: return@use null
                    if (line.trim() == "---") break
                    appendLine(line)
                }
            }.trimEnd()
        }
    }

    /** 写入/覆盖指定路径的 Markdown 文件 */
    // Readers and atomic replacements share the lock. In-place and directory mutations
    // take the exclusive lock, so they cannot race staged replacements.
    override fun writeFile(relativePath: String, content: String) = ioLock.read {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile(".${file.name}.", ".tmp", file.parentFile)
        try {
            tmp.writeText(content, Charsets.UTF_8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally {
            tmp.delete()
        }
    }

    /** Writes binary evidence such as action-bound learning keyframes atomically. */
    fun writeBytes(relativePath: String, content: ByteArray) {
        ioLock.read {
            val file = resolve(relativePath)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
            try {
                tmp.writeBytes(content)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
        }
    }

    fun readBytes(relativePath: String): ByteArray? {
        return ioLock.read {
            val file = resolve(relativePath)
            if (file.exists() && file.isFile) file.readBytes() else null
        }
    }

    /** 追加内容到指定文件 */
    fun appendFile(relativePath: String, content: String) {
        ioLock.write {
            val file = resolve(relativePath)
            file.parentFile?.mkdirs()
            file.appendText(content, Charsets.UTF_8)
        }
    }

    /** 检查文件是否存在 */
    override fun exists(relativePath: String): Boolean {
        return ioLock.read { resolve(relativePath).exists() }
    }

    /** 返回文件 mtime；不存在时返回 -1。 */
    fun lastModified(relativePath: String): Long {
        return ioLock.read {
            val file = resolve(relativePath)
            if (file.exists()) file.lastModified() else -1L
        }
    }

    /** 列出目录下的所有文件 */
    override fun listFiles(directoryPath: String): List<String> {
        return ioLock.read {
            val dir = resolve(directoryPath)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.map { it.name } ?: emptyList()
            } else {
                emptyList()
            }
        }
    }

    /** 删除文件 */
    fun deleteFile(relativePath: String): Boolean {
        return ioLock.write { Files.deleteIfExists(resolve(relativePath).toPath()) }
    }

    /** Atomically replace one managed directory with a validated set of direct child files. */
    override fun replaceDirectory(relativePath: String, files: Map<String, String>) {
        require(files.isNotEmpty()) { "Directory replacement requires at least one file" }
        require(files.keys.all { name ->
            name.isNotBlank() && '\\' !in name && name.split('/').all { it.isNotBlank() && it != "." && it != ".." }
        }) { "Directory replacement only accepts direct child file names" }
        ioLock.write {
            val target = resolve(relativePath)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
            val backup = File(target.parentFile, ".${target.name}.${System.nanoTime()}.backup")
            check(temp.mkdirs()) { "Cannot create temporary directory for $relativePath" }
            try {
                files.forEach { (name, content) ->
                    File(temp, name).also { it.parentFile?.mkdirs() }.writeText(content, Charsets.UTF_8)
                }
                if (target.exists()) {
                    check(target.renameTo(backup)) { "Cannot stage existing directory $relativePath" }
                }
                if (!temp.renameTo(target)) {
                    if (backup.exists()) backup.renameTo(target)
                    error("Cannot install directory $relativePath")
                }
                if (backup.exists()) backup.deleteRecursively()
            } catch (error: Throwable) {
                temp.deleteRecursively()
                if (!target.exists() && backup.exists()) backup.renameTo(target)
                throw error
            }
        }
    }

    override fun deleteDirectory(relativePath: String): Boolean {
        return ioLock.write {
            val directory = resolve(relativePath)
            directory.exists() && directory.isDirectory && directory.deleteRecursively()
        }
    }

    /** 获取数据根目录的绝对路径 */
    fun getDataDirPath(): String = dataDir.absolutePath
}
