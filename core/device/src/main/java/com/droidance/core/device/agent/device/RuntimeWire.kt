// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.File

/** Large payloads travel through owned descriptors, never Binder's shared transaction buffer. */
object RuntimeWire {
    fun write(directory: File, value: Bundle): ParcelFileDescriptor {
        val parcel = Parcel.obtain()
        val file = File.createTempFile("runtime-", ".parcel", directory.apply { mkdirs() })
        return try {
            parcel.writeBundle(value)
            file.outputStream().use { it.write(parcel.marshall()) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            parcel.recycle()
            // The receiver owns the open descriptor; no named payload or credential file remains.
            file.delete()
        }
    }

    fun read(descriptor: ParcelFileDescriptor): Bundle {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_PAYLOAD_BYTES) { "Runtime payload exceeds the transfer limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(RuntimeWire::class.java.classLoader))
        } finally { parcel.recycle() }
    }

    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
}
