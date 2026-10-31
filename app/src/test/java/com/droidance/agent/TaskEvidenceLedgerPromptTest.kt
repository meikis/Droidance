// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEvidenceLedgerPromptTest {
    @Test fun guardStopRetainsLatestFindingsInsteadOfFillingItsBudgetWithEarlyNavigation() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("Read sources")
        repeat(9) {
            ledger.recordObservation(toolName = "model_screen_evidence", rawText = "Earlier page $it",
                sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION, screenshotRef = "frame_$it")
        }
        ledger.recordObservation(toolName = "model_screen_evidence", rawText = "Source A value 12; Source B remains unread",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION, screenshotRef = "latest")
        assertEquals("Source A value 12; Source B remains unread", ledger.partialObservationTexts().first())
        assertFalse(ledger.partialObservationTexts().contains("Earlier page 0"))
    }
    @Test fun partialReportPreservesSourcedFindingsWithoutPromotingReceiptsOrUnsourcedText() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("Inspect modules")
        ledger.recordObservation(toolName = "screen_act", rawText = "ACTION_DELIVERED",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT)
        ledger.recordObservation(toolName = "model_screen_evidence", rawText = "Unsourced claim",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION)
        ledger.recordObservation(toolName = "model_screen_evidence", rawText = "Visible module A\nVisible module B",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION, screenshotRef = "frame_1")
        assertEquals(listOf("Visible module A", "Visible module B"), ledger.partialObservationTexts())
        assertEquals(listOf("Visible module A"), ledger.partialObservationTexts(1))
        ledger.startTask("Another task")
        assertTrue(ledger.partialObservationTexts().isEmpty())
    }

    @Test
    fun screenToolCanReuseItsDurableObservationId() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("Read the current screen")

        val observation = ledger.recordObservation(
            observationId = "call_frame_42",
            toolName = "android_read",
            rawText = "[SCREEN_STATE]",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        assertEquals("call_frame_42", observation.id)
        assertEquals("call_frame_42", ledger.completionEvidenceReferences())
        assertTrue(ledger.hasCompletionEvidence("call_frame_42"))
    }

    @Test
    fun recentTaskResearchNotePrioritizesTheCurrentStageWhenBounded() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("complete a multi-stage task")
        repeat(12) { index ->
            ledger.recordObservation(
                toolName = "model_screen_evidence",
                rawText = "stage-${index + 1} ${"x".repeat(70)}",
                sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
                screenshotRef = "round_${index + 1}"
            )
        }

        val note = ledger.buildRecentTaskResearchNote(maxChars = 420)

        assertTrue(note.length <= 420)
        assertTrue(note.contains("stage-12"))
        assertFalse(note.contains("stage-1 "))
        assertTrue(note.indexOf("stage-12") < note.indexOf("stage-11"))
    }

    @Test
    fun coordinatorContextExpandsStructuredObservationTextsIntoReadablePreview() {
        val prompt = EvidenceSnapshot(
            taskGoal = "在京东搜索苹果手机型号和价格",
            observations = listOf(
                ObservationFrame(
                    id = "obs_1",
                    toolName = "observe_screen",
                    timestamp = 1L,
                    screenHash = null,
                    rawText = """{"padding":"${"x".repeat(900)}","textBlocks":[{"text":"iPhone 16 Pro Max"},{"text":"¥7999"}]}"""
                )
            ),
            facts = emptyList()
        ).toPrompt(maxFacts = 10, maxChars = 2000)

        assertTrue(prompt.contains("iPhone 16 Pro Max"))
        assertTrue(prompt.contains("¥7999"))
    }

    @Test
    fun pureVisionTransportDoesNotSupplyVisibleTextEvidence() {
        val lines = ObservationTextExtractor.extract(
            """[PURE_VLM_SCREEN]
            A clean Android screenshot is attached.
            [AUXILIARY_TEXT_SIDE_CHANNEL]
            Readable text digest:
            - Search results
            - Model X 1999
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun coordinatorContextExcludesModelOutputAndActionReceiptsFromGrounding() {
        val snapshot = EvidenceSnapshot(
            taskGoal = "collect visible facts",
            observations = listOf(
                ObservationFrame(
                    id = "obs_1",
                    toolName = "model_observation",
                    timestamp = 1L,
                    screenHash = null,
                    rawText = "MODEL_GUESS_MUST_NOT_GROUND",
                    sourceKind = EvidenceSourceKind.MODEL_OUTPUT
                ),
                ObservationFrame(
                    id = "obs_2",
                    toolName = "click",
                    timestamp = 2L,
                    screenHash = null,
                    rawText = "ACTION_RECEIPT_MUST_NOT_GROUND",
                    sourceKind = EvidenceSourceKind.ACTION_RECEIPT
                )
            ),
            facts = emptyList()
        )

        val prompt = snapshot.toPrompt(maxFacts = 10, maxChars = 2000)

        assertFalse(prompt.contains("MODEL_GUESS_MUST_NOT_GROUND"))
        assertFalse(prompt.contains("ACTION_RECEIPT_MUST_NOT_GROUND"))
    }

    @Test
    fun coordinatorContextIncludesScreenshotBoundModelEvidence() {
        val prompt = EvidenceSnapshot(
            taskGoal = "collect visible facts",
            observations = listOf(
                ObservationFrame(
                    id = "obs_1",
                    toolName = "model_screen_evidence",
                    timestamp = 1L,
                    screenHash = null,
                    rawText = "VISIBLE_TITLE VISIBLE_VALUE",
                    sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
                    screenshotRef = "llm_round_1"
                )
            ),
            facts = emptyList()
        ).toPrompt(maxFacts = 10, maxChars = 2000)

        assertTrue(prompt.contains("VISIBLE_TITLE VISIBLE_VALUE"))
        assertTrue(prompt.contains("llm_round_1"))
    }

    @Test
    fun completionReferencesMustPointToExistingLedgerEntries() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("verify a visible result")
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "visible result",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        assertTrue(ledger.validateEvidenceReferences("obs_1"))
        assertFalse(ledger.validateEvidenceReferences("obs_2"))
        assertFalse(ledger.validateEvidenceReferences(""))
    }

    @Test
    fun completionCanUseControllerSelectedGroundedReferencesWhenModelOmitsThem() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("summarize visible results")
        val observation = ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "visible result",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        assertFalse(ledger.hasCompletionEvidence(""))

        ledger.addFacts(listOf(EvidenceFact(
            id = "fact_1",
            type = "visible_text",
            text = "A grounded result",
            verbatim = "A grounded result",
            sourceObservationId = observation.id,
            confidence = 0.9,
            relevance = 0.9
        )))

        assertFalse(ledger.hasCompletionEvidence(""))
        assertTrue(ledger.hasCompletionEvidence("fact_1"))
        assertEquals("fact_1,obs_1", ledger.completionEvidenceReferences())
        assertTrue(ledger.hasCompletionEvidence(ledger.completionEvidenceReferences()))
        assertFalse(ledger.hasCompletionEvidence("unknown"))
        assertFalse(ledger.hasCompletionEvidence("fact_99"))
        assertFalse(ledger.hasCompletionEvidence("fact_1, obs_xx"))
        assertFalse(ledger.validateEvidenceReferences("fact_1, obs_xx"))
    }

    @Test
    fun completionAcceptsOnlyGroundedCurrentTaskReferences() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("verify current result")
        ledger.recordObservation(
            toolName = "click",
            rawText = "Tap injected at 100,200",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT
        )
        ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "Visible item: Model X1, price 1999",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_2"
        )

        assertFalse(ledger.hasCompletionEvidence("obs_1"))
        assertTrue(ledger.hasCompletionEvidence("obs_2"))
    }

    @Test
    fun completionAcceptsAProviderActionAliasOnlyWhenItLeadsToGroundedObservation() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("verify the current result after an action")
        val receipt = ledger.recordObservation(
            toolName = "screen_act",
            rawText = "action receipt",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            actionCallId = "call_scroll_1",
            linksNextObservation = true
        )
        val grounded = ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "visible result after scroll",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        assertEquals(receipt.id, grounded.actionReceiptId)
        assertTrue(ledger.hasCompletionEvidence("call_scroll_1"))
        assertFalse(ledger.hasCompletionEvidence("call_unrelated"))

        val receiptOnly = TaskEvidenceLedger()
        receiptOnly.startTask("do not treat an unverified action as a result")
        receiptOnly.recordObservation(
            toolName = "screen_act",
            rawText = "action receipt",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            actionCallId = "call_without_after",
            linksNextObservation = true
        )
        assertFalse(receiptOnly.hasCompletionEvidence("call_without_after"))
    }

    @Test
    fun completeTaskLedgerSupportsAResultCollectedAcrossMultipleScreens() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("collect visible results")
        ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "Model A 100; Model B 200",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_1"
        )
        ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "Model C 300; Model D 400",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_2"
        )

        assertEquals("obs_1,obs_2", ledger.completionEvidenceReferences())
        assertTrue(ledger.hasCompletionEvidence(ledger.completionEvidenceReferences()))
    }

    @Test
    fun repeatedFactsAcrossChangingScreensDoNotDisplaceDistinctEvidence() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("collect visible results")
        val first = ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "Model A 100",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_1"
        )
        val second = ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "Model A 100",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_2"
        )
        ledger.addFacts(
            listOf(
                EvidenceFact(
                    type = "text",
                    text = "Model A 100",
                    verbatim = "Model A 100",
                    sourceObservationId = first.id,
                    confidence = 0.8,
                    relevance = 0.8
                ),
                EvidenceFact(
                    type = "text",
                    text = "Model A 100",
                    verbatim = "Model A 100",
                    sourceObservationId = second.id,
                    confidence = 0.8,
                    relevance = 0.8
                ),
                EvidenceFact(
                    type = "text",
                    text = "Model B 200",
                    verbatim = "Model B 200",
                    sourceObservationId = second.id,
                    confidence = 0.8,
                    relevance = 0.8
                )
            )
        )

        assertEquals(listOf("Model A 100", "Model B 200"), ledger.verifiedFactTexts(maxFacts = 10))
    }

    @Test
    fun verifiedFactTextsCanExcludeFactsUnrelatedToTheActiveGoal() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("collect task-relevant visible results")
        val observation = ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "relevant result and stale previous-task result",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_1"
        )
        ledger.addFacts(
            listOf(
                EvidenceFact(
                    type = "text",
                    text = "Relevant result",
                    verbatim = "Relevant result",
                    sourceObservationId = observation.id,
                    confidence = 0.8,
                    relevance = 0.9
                ),
                EvidenceFact(
                    type = "text",
                    text = "Stale previous-task result",
                    verbatim = "Stale previous-task result",
                    sourceObservationId = observation.id,
                    confidence = 0.9,
                    relevance = 0.35
                )
            )
        )

        assertEquals(
            listOf("Relevant result"),
            ledger.verifiedFactTexts(maxFacts = 10, minimumRelevance = 0.55)
        )
    }

    @Test
    fun latestGroundedObservationIsNotFilteredByHarnessRelevanceScoring() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("collect task-relevant visible results")
        val observation = ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "stale previous-task result",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_1"
        )
        ledger.addFacts(
            listOf(
                EvidenceFact(
                    type = "text",
                    text = "Stale previous-task result",
                    verbatim = "Stale previous-task result",
                    sourceObservationId = observation.id,
                    confidence = 0.9,
                    relevance = 0.35
                )
            )
        )

        assertEquals(observation.id, ledger.latestGroundedObservation()?.id)
    }

    @Test
    fun observationsAloneDoNotPretendToBeVerifiedFacts() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("collect visible results")
        val observation = ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "Visible item A",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_1"
        )

        assertTrue(ledger.hasEvidence())
        assertFalse(ledger.hasSourceBoundFacts())

        ledger.addFacts(
            listOf(
                EvidenceFact(
                    type = "text",
                    text = "Visible item A",
                    verbatim = "Visible item A",
                    sourceObservationId = observation.id,
                    confidence = 0.8,
                    relevance = 0.8
                )
            )
        )

        assertTrue(ledger.hasSourceBoundFacts())
    }

    @Test
    fun earlyGroundedFactsRemainCitableAfterTheirObservationFrameIsCompacted() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("compare visible items and values")
        val first = ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "Model A 100",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_1"
        )
        ledger.addFacts(
            listOf(
                EvidenceFact(
                    type = "text",
                    text = "Model A 100",
                    verbatim = "Model A 100",
                    sourceObservationId = first.id,
                    confidence = 0.8,
                    relevance = 0.8
                )
            )
        )
        repeat(31) { index ->
            ledger.recordObservation(
                toolName = "model_screen_evidence",
                rawText = "Later item $index",
                sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
                screenshotRef = "round_${index + 2}"
            )
        }

        assertFalse(ledger.observationsSnapshot().any { it.id == first.id })
        assertTrue(ledger.hasCompletionEvidence("fact_1"))
    }

    @Test
    fun routeVerificationRejectsASettledLandingThatDoesNotMatchTheCatalogContract() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("open the profile route")
        ledger.recordObservation(
            toolName = "android_act",
            rawText = "[DEEPLINK_ROUTE_EXPECTATION] route_id=route-1 action=profile " +
                "activity_contains=com.example%2FMainActivity text_any=Profile",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            linksNextObservation = true
        )
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "Foreground: com.example/MainActivity\nHome",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        val issues = ledger.routeVerificationIssues()

        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("Profile"))
    }

    @Test
    fun routeVerificationAcceptsTheFirstGroundedLandingThatMatchesTheCatalogContract() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("open the cart route")
        ledger.recordObservation(
            toolName = "android_act",
            rawText = "[DEEPLINK_ROUTE_EXPECTATION] route_id=route-1 action=cart " +
                "activity_contains=com.example%2FMainActivity text_any=Cart",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            linksNextObservation = true
        )
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "Foreground: com.example/MainActivity\nCart",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        assertTrue(ledger.routeVerificationIssues().isEmpty())
    }

    @Test
    fun routeVerificationUsesTheLatestGroundedLandingAfterAnAsyncLoadingFrame() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("open the cart route")
        ledger.recordObservation(
            toolName = "android_act",
            rawText = "[DEEPLINK_ROUTE_EXPECTATION] route_id=route-1 action=cart " +
                "activity_contains=com.example%2FMainActivity text_any=Cart",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            linksNextObservation = true
        )
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "Foreground: com.example/MainActivity\nLoading…",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )
        ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "Cart\nItem 100",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_2"
        )

        assertTrue(ledger.routeVerificationIssues().isEmpty())
    }

    @Test
    fun routeVerificationRejectsTheLatestExplicitActivityAfterAnEarlierMatch() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("open the cart route")
        ledger.recordObservation(
            toolName = "android_act",
            rawText = "[DEEPLINK_ROUTE_EXPECTATION] route_id=route-1 action=cart " +
                "activity_contains=com.example%2FMainActivity text_any=Cart",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            linksNextObservation = true
        )
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "Foreground: com.example/MainActivity\nCart",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "Foreground: com.example/OtherActivity\nCart",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        val issues = ledger.routeVerificationIssues()

        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("MainActivity"))
    }

    @Test
    fun routeVerificationPreservesLandingVerificationWhenSubsequentActionsNavigateToDifferentActivities() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("open home then search")
        ledger.recordObservation(
            toolName = "android_act",
            rawText = "[DEEPLINK_ROUTE_EXPECTATION] route_id=route-1 action=home " +
                "activity_contains=com.example%2FMainActivity",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            linksNextObservation = true
        )
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "Foreground: com.example/MainActivity\nHome",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )
        // Subsequent user action: tapping search box
        ledger.recordObservation(
            toolName = "screen_act",
            rawText = "Action: tap search",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            linksNextObservation = true
        )
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "Foreground: com.example/SearchActivity\nSearch Results",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        assertTrue(ledger.routeVerificationIssues().isEmpty())
    }

    @Test
    fun routeVerificationIgnoresFailedActionReceipts() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("open search route that fails")
        ledger.recordObservation(
            toolName = "android_act",
            rawText = "failed to launch\nDEEPLINK_LAUNCH_FAILED\n[DEEPLINK_ROUTE_EXPECTATION] route_id=route-1 action=search " +
                "activity_contains=com.example%2FSearchActivity",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT,
            progressState = "FAILED",
            linksNextObservation = true
        )
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "Foreground: com.example/MainActivity\nHome",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        assertTrue(ledger.routeVerificationIssues().isEmpty())
    }

    @Test
    fun operatorEvidenceDoesNotRepeatTheSeparatelyAnchoredActiveGoal() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("UNIQUE_ACTIVE_GOAL")
        ledger.recordObservation(
            toolName = "observe_screen",
            rawText = "visible state",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )

        assertFalse(ledger.buildOperatorEvidenceText().contains("UNIQUE_ACTIVE_GOAL"))
    }

    @Test
    fun completionEvidenceStaysValidAfterTheObservationWindowCompacts() {
        // Source evidence must survive eviction of the recent observation payload cache.
        val ledger = TaskEvidenceLedger()
        ledger.startTask("收到新消息后自动回复")
        val first = ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "对话 137251937 的新消息：你好",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_1"
        )
        repeat(40) { index ->
            ledger.recordObservation(
                toolName = "click",
                rawText = "action receipt $index",
                sourceKind = EvidenceSourceKind.ACTION_RECEIPT
            )
        }
        ledger.recordObservation(
            toolName = "model_screen_evidence",
            rawText = "已发送回复：你好呀 137251937",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION,
            screenshotRef = "round_2"
        )

        val refs = ledger.completionEvidenceReferences()

        assertTrue(refs.split(',').contains(first.id))
        assertTrue(ledger.hasCompletionEvidence(first.id))
        assertTrue(ledger.observationsSnapshot().size <= 30)
        assertTrue(ledger.hasCompletionEvidence(refs))
    }
    @Test
    fun evictedProvenanceRetainsAliasesButNeverGroundsReceiptOrModelOnlyText() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("Read then navigate")
        val receipt = ledger.recordObservation(toolName = "screen_act", rawText = "delivered",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT, actionCallId = "call_original", linksNextObservation = true)
        val source = ledger.recordObservation(toolName = "android_read", rawText = "source",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE, screenshotRef = "frame_original")
        val ungrounded = ledger.recordObservation(toolName = "model", rawText = "unsupported",
            sourceKind = EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION)
        repeat(80) { ledger.recordObservation(toolName = "screen_act", rawText = "receipt",
            sourceKind = EvidenceSourceKind.ACTION_RECEIPT) }
        assertTrue(ledger.hasCompletionEvidence(source.id + ",call_original"))
        assertFalse(ledger.hasCompletionEvidence(receipt.id))
        assertFalse(ledger.hasCompletionEvidence(ungrounded.id))
        assertEquals(listOf("invented"), ledger.unresolvedCompletionReferences(source.id + ",invented"))
        assertFalse(ledger.hasCompletionEvidence(source.id + ",invented"))
        ledger.startTask("Another task")
        assertFalse(ledger.hasCompletionEvidence(source.id + ",call_original"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun evictedObservationIdsCannotBeReboundToDifferentEvidence() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("Read")
        repeat(40) { index -> ledger.recordObservation(observationId = "frame_$index", toolName = "read",
            rawText = "source", sourceKind = EvidenceSourceKind.OBSERVED_STATE) }
        ledger.recordObservation(observationId = "frame_0", toolName = "model", rawText = "replacement",
            sourceKind = EvidenceSourceKind.MODEL_OUTPUT)
    }

    @Test
    fun derivedComputationSourceGroundsCompletionWithoutFakeDeviceActions() {
        val ledger = TaskEvidenceLedger()
        ledger.startTask("Calculate SMS aggregate")
        val obs = ledger.recordObservation(
            toolName = "android_read",
            rawText = "sms count: 5",
            sourceKind = EvidenceSourceKind.OBSERVED_STATE
        )
        ledger.recordDerivedSource(
            sourceRef = "prog_calc_123",
            toolName = "program",
            inputSourceRefs = listOf(obs.id),
            dataRef = "data_ref_1",
            outputPreview = "sum = 42"
        )
        assertTrue(ledger.validateEvidenceReferences("prog_calc_123"))
        assertTrue(ledger.hasCompletionEvidence("prog_calc_123"))
        assertTrue(ledger.completionEvidenceReferences().contains("prog_calc_123"))
        assertEquals(emptyList<String>(), ledger.unresolvedCompletionReferences("prog_calc_123"))
    }

}
