// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.agent.blob.FileBlobStore
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UserAttachmentMaterializerTest {
    @Test
    fun imageBytesAreContentAddressedBeforeTheUserEventIsCreated() {
        val directory = Files.createTempDirectory("droidance-user-attachments")
        try {
            val bytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x01, 0x02, 0x03
            )
            val store = FileBlobStore(directory.toFile())

            val attachments = materializeUserAttachments(
                messageId = "message-1",
                images = listOf(Base64.getEncoder().encodeToString(bytes)),
                blobStore = store
            )

            val attachment = attachments.single()
            assertEquals("message-1:attachment:0", attachment.attachmentId)
            assertEquals("image/png", attachment.mimeType)
            assertEquals(bytes.size.toLong(), attachment.sizeBytes)
            assertArrayEquals(bytes, store.read(attachment.contentRef, bytes.size.toLong()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun malformedOrNonImagePayloadFailsBeforeAUserEventCanReferenceIt() {
        val directory = Files.createTempDirectory("droidance-user-attachments-invalid")
        try {
            val store = FileBlobStore(directory.toFile())
            assertThrows(IllegalArgumentException::class.java) {
                materializeUserAttachments("message-1", listOf("not-base64"), store)
            }
            assertThrows(IllegalArgumentException::class.java) {
                materializeUserAttachments(
                    "message-1",
                    listOf(Base64.getEncoder().encodeToString("plain text".toByteArray())),
                    store
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
