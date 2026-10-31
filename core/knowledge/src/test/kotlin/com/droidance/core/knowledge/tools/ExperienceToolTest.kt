// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.knowledge.tools.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceToolTest {
    @Test fun discoveryIsCommittedBeforeCompletionAndSurvivesCancellation() = runBlocking {
        val f = Fixture(); f.observe("discovery")
        val saved = f.save(update())
        assertTrue(saved.isVerifiedSuccess)
        assertTrue(saved.outputText.contains("persistence_confirmed"))
        assertFalse(f.history.any { it.eventKind == AgentEventKind.TURN_COMPLETED })
        f.event(AgentEventKind.TURN_COMPLETED, TurnCompletedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION,
            "canceled", systemNotice = "Task canceled."))
        assertEquals("Open the panel and confirm.", f.store.methods.getValue("panel_filter").update.method)
        assertEquals("unknown", f.store.methods.getValue("panel_filter").update.outcome)
    }

    @Test fun retriesOfTheSameInvocationDoNotCreateAnotherRevision() = runBlocking {
        val f = Fixture(); f.observe("discovery")
        val args = saveArgs(update())
        val first = f.call(args)
        val retry = f.tool.execute(args.toString())
        assertTrue(first.isVerifiedSuccess); assertTrue(retry.isVerifiedSuccess)
        assertEquals(1L, f.store.methods.getValue("panel_filter").revision)
    }

    @Test fun adoptionRequiresExpansionAndDoesNotTreatReadingAsUse() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update())
        assertTrue(f.store.uses.isEmpty())
        val missing = f.adopt()
        assertTrue(missing.outputText.contains("EXPERIENCE_EXPAND_BEFORE_USE"))
        f.expand(); val adopted = f.adopt()
        assertTrue(adopted.isVerifiedSuccess)
        assertEquals("experience:panel_filter:1", f.store.uses.values.single().experienceRef)
        assertEquals("adopted", f.store.uses.values.single().outcome)
    }

    @Test fun assessmentUsesFreshEvidenceAndKeepsTheAdoptedVersionAfterCorrection() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update()); f.expand(); f.adopt()
        val use = f.store.uses.values.single()
        val stale = f.assess(use.useId, "succeeded", listOf("discovery"))
        assertTrue(stale.outputText.contains("EXPERIENCE_SOURCE_NOT_CURRENT_EXTERNAL_EVIDENCE"))
        f.user("Use the confirmation button after choosing the filter.", steer = true)
        f.save(update(revision = 1).copy(method = "Choose the filter, then use the confirmation button."),
            quote = "Use the confirmation button after choosing the filter.")
        f.observe("confirmed")
        assertTrue(f.assess(use.useId, "succeeded", listOf("confirmed")).isVerifiedSuccess)
        assertEquals(2L, f.store.methods.getValue("panel_filter").revision)
        assertEquals("experience:panel_filter:1", f.store.uses.getValue(use.useId).experienceRef)
        assertEquals("succeeded", f.store.uses.getValue(use.useId).outcome)
        assertTrue(f.assess(use.useId, "failed", listOf("confirmed")).outputText.contains("EXPERIENCE_USE_ALREADY_ASSESSED"))
    }

    @Test fun failedAdoptionCanCorrectTheSameMethodWithItsProvenance() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update()); f.expand(); f.adopt()
        val use = f.store.uses.values.single(); f.observe("failed-filter")
        assertTrue(f.assess(use.useId, "failed", listOf("failed-filter")).isVerifiedSuccess)
        val corrected = update(revision = 1, refs = listOf("failed-filter"))
            .copy(method = "Confirm the selected filter; verify the resulting list.")
        assertTrue(f.save(corrected, sourceUse = use.useId).isVerifiedSuccess)
        val latest = f.store.methods.getValue("panel_filter")
        assertEquals(1, f.store.methods.size)
        assertEquals(2L, latest.revision)
        assertEquals(use.useId, latest.sourceUseId)
        assertEquals("unknown", latest.update.outcome)
        assertEquals("failed", f.store.uses.getValue(use.useId).outcome)
    }

    @Test fun aCurrentHumanCorrectionCanBeSavedWithoutPretendingItWasExecuted() = runBlocking {
        val f = Fixture(); f.user("Choose the option and then confirm.", steer = true)
        val corrected = update(refs = emptyList()).copy(method = "Choose the option and then confirm.")
        assertTrue(f.save(corrected, quote = "Choose the option and then confirm.").isVerifiedSuccess)
        val record = f.store.methods.getValue("panel_filter")
        assertEquals("Choose the option and then confirm.", record.userCorrectionQuote)
        assertNotNull(record.userCorrectionEventId)
        assertEquals(listOf(record.userCorrectionEventId), record.sourceEvents)
        assertEquals("unknown", record.update.outcome)
        val inventedSuccess = f.save(corrected.copy(expected_revision = 1, outcome = "succeeded"),
            quote = "Choose the option and then confirm.")
        assertFalse(inventedSuccess.isVerifiedSuccess)
    }

    @Test fun modelTextHistoricalUserTextAndFabricatedQuotesCannotAuthorizeCorrections() = runBlocking {
        for (kind in listOf(AgentEventKind.ASSISTANT_MESSAGE, AgentEventKind.USER_MESSAGE)) {
            val f = Fixture()
            f.event(kind, buildJsonObject { put("text", "Use confirmation.") },
                turn = if (kind == AgentEventKind.USER_MESSAGE) "other-turn" else "turn")
            assertTrue(f.save(update(refs = emptyList()), quote = "Use confirmation.").outputText
                .contains("EXPERIENCE_CORRECTION_SOURCE_REQUIRED"))
            assertTrue(f.store.methods.isEmpty())
        }
        val f = Fixture(); f.user("Use confirmation.")
        assertFalse(f.save(update(refs = emptyList()), quote = "Use the confirmation button.").isVerifiedSuccess)
    }

    @Test fun localReceiptsHistoricalAttachmentsAndOtherTurnObservationsCannotBecomeEvidence() = runBlocking {
        for (source in listOf("local", "historical", "other")) {
            val f = Fixture()
            f.observe("discovery", binding = if (source == "local") ToolBinding.TURN_LOCAL else ToolBinding.DEVICE_BOUND,
                historical = source == "historical", turn = if (source == "other") "other-turn" else "turn")
            assertFalse(f.save(update()).isVerifiedSuccess)
            assertTrue(f.store.methods.isEmpty())
        }
    }

    @Test fun staleCorrectionsCannotOverwriteNewerMethods() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update()); f.expand()
        assertTrue(f.save(update(revision = 1).copy(method = "Newer route.")).isVerifiedSuccess)
        assertTrue(f.save(update(revision = 1).copy(method = "Stale route.")).outputText.contains("EXPERIENCE_REVISION_CONFLICT"))
        assertEquals("Newer route.", f.store.methods.getValue("panel_filter").update.method)
    }

    @Test fun foreignUseCannotBeAssessedOrUsedToCorrectAnotherMethod() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update()); f.expand(); f.adopt()
        val use = f.store.uses.values.single()
        f.store.uses[use.useId] = use.copy(turnId = "other-turn")
        assertTrue(f.assess(use.useId, "unknown", emptyList()).outputText.contains("EXPERIENCE_USE_MISMATCH"))
        assertTrue(f.save(update(revision = 1), sourceUse = use.useId).outputText.contains("EXPERIENCE_USE_MISMATCH"))
    }

    @Test fun unknownCanSettleWithoutEvidenceButSucceededCannot() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update()); f.expand(); f.adopt()
        val use = f.store.uses.values.single()
        assertTrue(f.assess(use.useId, "succeeded", emptyList()).outputText.contains("EXPERIENCE_EVIDENCE_REQUIRED"))
        assertTrue(f.assess(use.useId, "unknown", emptyList()).isVerifiedSuccess)
        assertEquals("unknown", f.store.uses.getValue(use.useId).outcome)
    }

    @Test fun assessedSuccessCanBeSavedWithTheSameEvidence() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update()); f.expand(); f.adopt()
        val use = f.store.uses.values.single(); f.observe("result")
        f.assess(use.useId, "succeeded", listOf("result"))
        val checked = update(revision = 1, refs = listOf("result")).copy(outcome = "succeeded")
        assertTrue(f.save(checked, sourceUse = use.useId).isVerifiedSuccess)
    }

    @Test fun changingTheMethodCannotInheritAnAssessedRoutesSuccess() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update()); f.expand(); f.adopt()
        val use = f.store.uses.values.single(); f.observe("result")
        f.assess(use.useId, "succeeded", listOf("result"))
        val changed = update(revision = 1, refs = listOf("result"))
            .copy(method = "Try an unexecuted alternate route.", outcome = "succeeded")
        assertTrue(f.save(changed, sourceUse = use.useId).outputText.contains("EXPERIENCE_CHANGED_METHOD_NOT_VERIFIED"))
        assertEquals(1L, f.store.methods.values.single().revision)
        assertTrue(f.save(changed.copy(outcome = "unknown"), sourceUse = use.useId).isVerifiedSuccess)
    }

    @Test fun anOldVersionsAssessmentCannotPromoteTheNewVersion() = runBlocking {
        val f = Fixture(); f.observe("discovery"); f.save(update()); f.expand(); f.adopt()
        val use = f.store.uses.values.single(); f.observe("result")
        f.assess(use.useId, "succeeded", listOf("result"))
        f.save(update(revision = 1).copy(method = "Corrected route."), sourceUse = use.useId)
        val current = f.store.methods.values.single().update
        val promotion = current.copy(expected_revision = 2, outcome = "succeeded", evidence_refs = listOf("result"))
        assertTrue(f.save(promotion, sourceUse = use.useId).outputText.contains("EXPERIENCE_ASSESSED_USE_REQUIRED"))
        assertEquals(2L, f.store.methods.values.single().revision)
    }

    @Test fun secretsAreRedactedAndOversizedWritesDoNotPersist() = runBlocking {
        val f = Fixture(); f.user("Use confirmation. password=private-secret")
        val record = update(refs = emptyList()).copy(method = "Confirm. password=private-secret")
        assertTrue(f.save(record, quote = "Use confirmation. password=private-secret").isVerifiedSuccess)
        assertFalse(JSON.encodeToString(f.store.methods.values.single()).contains("private-secret"))
        assertTrue(f.save(record.copy(method = "x".repeat(20_000))).outputText.contains("EXPERIENCE_SIZE_LIMIT"))
        assertEquals(1L, f.store.methods.values.single().revision)
    }

    private class Fixture {
        val history = mutableListOf<AgentEventEnvelope>()
        val store = Store()
        var currentCall = ""
        val eventStore = object : AgentEventStore by InMemoryAgentEventStore() {
            override suspend fun eventsForTurn(turnId: String) = history.filter { it.turnId == turnId }
        }
        val tool = ExperienceTool(store, eventStore, { TurnEventScope("thread", "turn") }, { currentCall },
            { 12_000 }, { mapOf("example.app" to "1:test") }, "Record learning in the current loop.")

        inline fun <reified T> event(kind: AgentEventKind, payload: T, call: String? = null,
                                    turn: String = "turn"): AgentEventEnvelope {
            val sequence = history.size + 1L
            return AgentEventEnvelope(AGENT_EVENT_SCHEMA_VERSION, "event-$sequence", kind, sequence,
                sequence * 10, "thread", turn, "round-$sequence", null, null, call,
                "a".repeat(64), JSON.encodeToString(payload), "key-$sequence").also(history::add)
        }
        fun declaration(name: String, args: JsonObject, call: String, binding: ToolBinding = ToolBinding.TURN_LOCAL,
                        turn: String = "turn") = event(AgentEventKind.TOOL_CALL, ToolCallEventPayload(
            MODEL_ROUND_EVENT_CONTRACT_VERSION, 0, call, name, args.toString(), ToolCallSource.MODEL_REQUESTED,
            binding, if (name == "experience") ToolEffect.STATE_CHANGING else ToolEffect.READ_ONLY,
            "a".repeat(64), ToolPresentationSnapshot("tool.$name", ToolPresentationCategory.SUPPORTING,
                ResultImageRole.NONE), "detail"), call, turn)
        fun observe(ref: String, binding: ToolBinding = ToolBinding.DEVICE_BOUND, historical: Boolean = false,
                    turn: String = "turn") {
            val call = "read-${history.size}"; declaration("android_read", JsonObject(emptyMap()), call, binding, turn)
            event(AgentEventKind.TOOL_FINISHED, ToolFinishedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION,
                ToolExecutionResult("Original current device state", ToolExecutionStatus.SUCCEEDED,
                    ToolEvidenceStatus.CAPTURED, observationId = ref,
                    historicalAttachment = if (historical) attachment() else null), "round"), call, turn)
        }
        fun user(text: String, steer: Boolean = false) = event(
            if (steer) AgentEventKind.USER_STEER else AgentEventKind.USER_MESSAGE,
            UserTextEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION, "message-${history.size}", text))
        fun expand(ref: String = "experience:panel_filter:1") {
            val call = "expand-${history.size}"; declaration("session_search", buildJsonObject { put("ref", ref) }, call)
            event(AgentEventKind.TOOL_FINISHED, ToolFinishedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION,
                ToolProtocolResult.success(buildJsonObject { put("ref", ref); put("record", "Exact method body") }.toString()), "round"), call)
        }
        suspend fun call(args: JsonObject): ToolExecutionResult {
            currentCall = "call-${history.size}"; declaration("experience", args, currentCall)
            return tool.execute(args.toString())
        }
        suspend fun save(update: ExperienceUpdate, quote: String? = null, sourceUse: String? = null) =
            call(saveArgs(update, quote, sourceUse))
        suspend fun adopt() = call(buildJsonObject {
            put("op", "use"); put("ref", "experience:panel_filter:1"); put("summary", "Matches the current panel.")
        })
        suspend fun assess(useId: String, outcome: String, refs: List<String>) = call(buildJsonObject {
            put("op", "assess"); put("use_id", useId); put("outcome", outcome)
            put("summary", "Checked current result; task-scoped assessment."); put("evidence_refs", JsonArray(refs.map(::JsonPrimitive)))
        })
    }

    private class Store : ExperienceLearningStore {
        val methods = linkedMapOf<String, ExperienceRecord>()
        val uses = linkedMapOf<String, ExperienceUseRecord>()
        val receipts = mutableMapOf<String, String>()
        override suspend fun current(ref: String) = methods.values.singleOrNull { it.ref == ref }
        override suspend fun save(key: String, record: ExperienceRecord): String = receipts.getOrPut(key) {
            if ((methods[record.update.id]?.revision ?: 0) != record.update.expected_revision) "EXPERIENCE_REVISION_CONFLICT"
            else { methods[record.update.id] = record; "APPLIED" }
        }
        override suspend fun use(useId: String) = uses[useId]
        override suspend fun beginUse(record: ExperienceUseRecord): String {
            uses.putIfAbsent(record.useId, record); return "APPLIED"
        }
        override suspend fun assessUse(key: String, record: ExperienceUseRecord): String = receipts.getOrPut(key) {
            if (uses[record.useId]?.outcome != "adopted") "EXPERIENCE_USE_ALREADY_ASSESSED"
            else { uses[record.useId] = record; "APPLIED" }
        }
    }

    companion object {
        private val JSON = Json { encodeDefaults = true }
        private fun update(revision: Long = 0, refs: List<String> = listOf("discovery")) = ExperienceUpdate(
            "panel_filter", revision, "Apply a filter", "example.app", listOf("filter"),
            "A matching panel is visible.", "Open the panel and confirm.", "Observe the current controls.",
            "The selected filter is visible.", "Reobserve before retrying.", "", "unknown", "Only the observed route is supported.", refs)
        private fun saveArgs(update: ExperienceUpdate, quote: String? = null, sourceUse: String? = null) = buildJsonObject {
            put("op", "save"); put("update", JSON.encodeToJsonElement(update))
            quote?.let { put("source_quote", it) }; sourceUse?.let { put("source_use_id", it) }
        }
        private fun attachment() = DetailAttachmentAddedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION, "old-image",
            DetailAttachmentRole.INPUT_OBSERVATION, "sha256:${"b".repeat(64)}", "b".repeat(64), 1,
            "image/png", 1, 1, observationId = "historical")
    }
}
