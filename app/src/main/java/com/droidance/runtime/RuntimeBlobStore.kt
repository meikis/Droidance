// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.os.ParcelFileDescriptor
import com.droidance.core.agent.blob.*
import com.droidance.core.device.agent.device.RuntimeWire
import java.io.File
import java.io.OutputStream

/** Artifact bytes and hashes belong to the same resident owner as their event references. */
internal class RuntimeBlobStore(private val client: RuntimeProcessClient) : BlobStore {
    override fun put(bytes: ByteArray): BlobMetadata {
        val file = File.createTempFile("runtime-blob-", ".bin", client.cacheDir)
        return try {
            file.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val result = try { RuntimeWire.read(client.owner().blob("put", "", bytes.size.toLong(), descriptor)) }
                finally { descriptor.close() }
            BlobMetadata(result.getString("ref")!!, result.getString("hash")!!, result.getLong("size"))
        } finally { file.delete() }
    }

    override fun read(contentRef: String, maxBytes: Long): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(client.owner().blob("read", contentRef, maxBytes, null)).use { it.readBytes() }

    override fun copyTo(contentRef: String, output: OutputStream): Long =
        ParcelFileDescriptor.AutoCloseInputStream(client.owner().blob("read", contentRef, Long.MAX_VALUE, null)).use { it.copyTo(output) }

    override fun delete(contentRef: String): Boolean =
        RuntimeWire.read(client.owner().blob("delete", contentRef, 0, null)).getBoolean("deleted")

    override fun listAllRefs(): Set<String> =
        RuntimeWire.read(client.owner().blob("list", "", 0, null)).getStringArrayList("refs").orEmpty().toSet()
}
