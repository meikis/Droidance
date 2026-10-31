// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.harness

import com.droidance.core.agent.harness.*

import com.droidance.agent.harness.*

import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.event.DetailAttachmentRole
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailAttachmentMaterializerTest {
    @Test
    fun materializesAnImmutableSnapshotWithEventMetadata() = runBlocking {
        val source = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
        val original = source.copyOf()
        val directory = Files.createTempDirectory("detail-attachment-store-")
        try {
            val store = FileBlobStore(directory.toFile())
            val pending = PendingDetailAttachment(
                attachmentId = "call-1:observation:observation-1",
                role = DetailAttachmentRole.CURRENT_OBSERVATION,
                bytes = source,
                mimeType = "image/png",
                width = 1,
                height = 1,
                observationId = "observation-1"
            )
            source[0] = 9

            val result = materializeDetailAttachments(store, listOf(pending)).single()
            val reopened = FileBlobStore(directory.toFile())

            assertArrayEquals(original, reopened.read(result.contentRef, original.size.toLong()))
            assertTrue(result.contentRef in reopened.listAllRefs())
            assertEquals("call-1:observation:observation-1", result.attachmentId)
            assertEquals(DetailAttachmentRole.CURRENT_OBSERVATION, result.role)
            assertEquals("image/png", result.mimeType)
            assertEquals(1, result.width)
            assertEquals(1, result.height)
            assertEquals("observation-1", result.observationId)
            assertEquals(original.size.toLong(), result.sizeBytes)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(java.util.Comparator.reverseOrder<java.nio.file.Path>())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun rejectsIncompleteDimensionsAndInvalidMimeType() {
        assertThrows(IllegalArgumentException::class.java) {
            PendingDetailAttachment("id", DetailAttachmentRole.RESULT, byteArrayOf(1), "image/png", width = 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PendingDetailAttachment("id", DetailAttachmentRole.RESULT, byteArrayOf(1), "invalid")
        }
    }

    @Test fun retainsNativeSourceAndModelRepresentationAsSeparateImmutableBlobs() = runBlocking {
        val source = byteArrayOf(1, 2, 3)
        val model = byteArrayOf(4, 5)
        val originalSource = source.copyOf()
        val originalModel = model.copyOf()
        val directory = Files.createTempDirectory("detail-source-model-")
        try {
            val pending = PendingDetailAttachment("pair", DetailAttachmentRole.INPUT_OBSERVATION,
                source, "image/png", width = 1080, height = 2400, observationId = "source",
                modelImageBytes = model)
            source.fill(0); model.fill(0)
            val store = FileBlobStore(directory.toFile())
            val attachment = materializeDetailAttachments(store, listOf(pending)).single()
            val reopened = FileBlobStore(directory.toFile())
            assertArrayEquals(originalSource, reopened.read(attachment.contentRef, 3))
            assertArrayEquals(originalModel, reopened.read(requireNotNull(attachment.modelContentRef), 2))
            assertEquals(3L, attachment.sizeBytes)
            assertEquals(2L, attachment.modelSizeBytes)
            assertEquals("image/png", attachment.mimeType)
            assertEquals(setOf(attachment.contentRef, attachment.modelContentRef), reopened.listAllRefs())
            val sameBytes = materializeDetailAttachments(store, listOf(PendingDetailAttachment("same",
                DetailAttachmentRole.INPUT_OBSERVATION, originalSource, "image/png", modelImageBytes = originalSource))).single()
            assertEquals(null, sameBytes.modelContentRef)
            assertEquals(null, sameBytes.modelSizeBytes)
        } finally { directory.toFile().deleteRecursively() }
    }
}
