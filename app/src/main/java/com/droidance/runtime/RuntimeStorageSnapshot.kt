// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One initial transfer of the existing private storage into its resident owner. */
internal object RuntimeStorageSnapshot {
    private val directories = listOf("files", "no_backup", "shared_prefs", "databases")

    @Synchronized
    fun installationId(context: Context): String {
        val file = File(context.noBackupFilesDir, "runtime-installation-id")
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            check(file.createNewFile())
            file.writeText(java.util.UUID.randomUUID().toString())
        }
        return java.util.UUID.fromString(file.readText().trim()).toString()
    }

    fun create(context: Context): ParcelFileDescriptor {
        val temporary = File.createTempFile("runtime-storage-", ".zip", context.cacheDir)
        try {
            val root = File(context.applicationInfo.dataDir)
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                for (name in directories) {
                    val directory = File(root, name)
                    if (!directory.isDirectory) continue
                    directory.walkTopDown().filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            return ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally { temporary.delete() }
    }

    fun managedFiles(context: Context): ParcelFileDescriptor {
        val temporary = File.createTempFile("runtime-files-", ".zip", context.cacheDir)
        try {
            val root = File(context.noBackupFilesDir, "droidance-data")
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally { temporary.delete() }
    }

    fun restore(root: File, source: ParcelFileDescriptor) {
        check(root.mkdirs() || root.isDirectory)
        android.system.Os.chmod(root.path, 448) // 0700; only the resident shell owner.
        var total = 0L
        ZipInputStream(ParcelFileDescriptor.AutoCloseInputStream(source).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name.substringBefore('/') in directories)
                val destination = File(root, entry.name).canonicalFile
                require(destination.path.startsWith(root.canonicalPath + File.separator))
                destination.parentFile!!.mkdirs()
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "Runtime storage exceeds the transfer limit" }
                        output.write(buffer, 0, count)
                    }
                }
                android.system.Os.chmod(destination.path, 384) // 0600
                zip.closeEntry()
            }
        }
    }
}
