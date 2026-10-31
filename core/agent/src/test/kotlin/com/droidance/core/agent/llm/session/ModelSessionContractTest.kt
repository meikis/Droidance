// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.llm.session

import com.droidance.core.model.llm.session.*

import com.droidance.core.agent.llm.session.*

import com.droidance.core.agent.prompt.StablePrefixBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelSessionContractTest {
    private val prefix = StablePrefixBuilder.build("You are Droidance.", emptyList(), emptyList())
    @Test
    fun equivalent_transport_config_has_a_deterministic_hash() {
        val first = FrozenTransportConfig.create(
            normalizedEndpoint = "https://example.com/v1",
            adapterVersion = "responses-v1",
            semanticOptions = linkedMapOf("z" to "last", "a" to "first")
        )
        val second = FrozenTransportConfig.create(
            normalizedEndpoint = "https://example.com/v1",
            adapterVersion = "responses-v1",
            semanticOptions = linkedMapOf("a" to "first", "z" to "last")
        )

        assertEquals(first, second)
        assertEquals(first.hash(ModelTransport.OPENAI_RESPONSES), second.hash(ModelTransport.OPENAI_RESPONSES))
        assertNotEquals(
            first.hash(ModelTransport.OPENAI_RESPONSES),
            first.hash(ModelTransport.GENERIC_CHAT_COMPLETIONS)
        )
    }

    @Test
    fun every_identity_dimension_participates_in_equality() {
        val config = FrozenTransportConfig.create("https://example.com/v1", "adapter-v1")
        val baseline = identity(config = config)

        assertNotEquals(baseline, baseline.copy(threadId = "thread-2"))
        assertNotEquals(baseline, baseline.copy(providerId = "provider-2"))
        assertNotEquals(
            baseline,
            baseline.copy(transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS)
        )
        assertNotEquals(baseline, baseline.copy(transportConfigHash = "a".repeat(64)))
        assertNotEquals(baseline, baseline.copy(authScopeHash = "b".repeat(64)))
        assertNotEquals(baseline, baseline.copy(modelId = "model-2"))
        assertNotEquals(baseline, baseline.copy(stablePrefixHash = "c".repeat(64)))
    }

    @Test
    fun epoch_advances_for_explicit_protocol_boundaries_without_changing_identity() {
        val key = ModelSessionKey(identity(), epoch = 4)

        val triggers = listOf<EpochAdvanceTrigger>(
            ContextCheckpointApplied("checkpoint-event"),
            ProviderHandleInvalidated("PROVIDER_HANDLE_GONE", "sha256:${"a".repeat(64)}")
        )
        triggers.forEach { trigger ->
            val transition = ModelSessionEpochPolicy.advance(key, trigger)
            assertEquals(key.identity, transition.current.identity)
            assertEquals(5, transition.current.epoch)
            assertEquals(trigger.evidenceRef, transition.evidenceRef)
        }
        assertEquals(
            setOf("CONTEXT_CHECKPOINT", "PROVIDER_HANDLE_INVALIDATED", "PROCESS_RECOVERY"),
            EpochAdvanceReason.entries.map { it.name }.toSet()
        )
    }

    @Test
    fun turn_snapshot_keeps_the_original_identity_and_config_reference() {
        val original = identity()
        val snapshot = FrozenTurnModelSession(
            turnId = "turn-1",
            providerConfigSnapshotRef = "provider-snapshot-1",
            key = ModelSessionKey(original, epoch = 0)
        )
        val changedForNextTurn = original.copy(modelId = "model-2")

        assertEquals("model-1", snapshot.key.identity.modelId)
        assertEquals("provider-snapshot-1", snapshot.providerConfigSnapshotRef)
        assertNotEquals(changedForNextTurn, snapshot.key.identity)
    }

    @Test
    fun raw_hashes_and_invalid_endpoints_are_rejected_at_the_boundary() {
        assertThrows(IllegalArgumentException::class.java) {
            identity().copy(authScopeHash = "raw-api-key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrozenTransportConfig.create(" https://example.com/v1", "adapter-v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextCheckpointApplied("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHandleInvalidated("PROVIDER_HANDLE_GONE", "raw-provider-handle")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrozenTransportConfig.create("https://user:secret@example.com/v1", "adapter-v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrozenTransportConfig.create("https://example.com/v1", "adapter-v1", extraBodyJson = " ")
        }
    }

    @Test
    fun frozen_transport_config_does_not_expose_or_accept_mutable_semantic_state() {
        val sourceOptions = linkedMapOf("stream" to "true")
        val config = FrozenTransportConfig.create(
            "https://example.com/v1",
            "adapter-v1",
            semanticOptions = sourceOptions
        )
        val before = config.hash(ModelTransport.OPENAI_RESPONSES)

        sourceOptions["stream"] = "false"

        assertEquals(before, config.hash(ModelTransport.OPENAI_RESPONSES))
        assertFalse(config.toString().contains("stream"))
    }

    @Test
    fun model_session_key_has_no_data_class_copy_bypass() {
        assertFalse(ModelSessionKey::class.java.declaredMethods.any { it.name == "copy" })
    }

    private fun identity(
        config: FrozenTransportConfig = FrozenTransportConfig.create(
            "https://example.com/v1",
            "adapter-v1"
        )
    ): ModelSessionIdentity = ModelSessionIdentity.create(
        threadId = "thread-1",
        providerId = "provider-1",
        transportKind = ModelTransport.OPENAI_RESPONSES,
        transportConfig = config,
        authScopeHash = "d".repeat(64),
        modelId = "model-1",
        stablePrefix = prefix
    )
}
