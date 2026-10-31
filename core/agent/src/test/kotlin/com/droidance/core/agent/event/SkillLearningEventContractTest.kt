// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SkillLearningEventContractTest {
    @Test
    fun semanticSkillSuccessRequiresTypedEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            completedRun(semanticSuccess = true, evidenceRefs = emptyList())
        }

        val payload = completedRun(
            semanticSuccess = true,
            evidenceRefs = listOf("skill-run:run-1:verify:2")
        )
        assertEquals(0, payload.localModelRounds)
        assertEquals(0, payload.localPromptTokens)
    }

    @Test
    fun learningAndCompiledRunEventsUseHiddenThreadScopeWithoutATurn() {
        listOf(
            AgentEventKind.LEARNING_SESSION_STARTED,
            AgentEventKind.LEARNING_ACTION_RECORDED,
            AgentEventKind.LEARNING_SESSION_COMPLETED,
            AgentEventKind.LEARNING_SKILL_COMPILED,
            AgentEventKind.COMPILED_SKILL_RUN_STARTED,
            AgentEventKind.COMPILED_SKILL_RUN_COMPLETED
        ).forEach { kind ->
            AgentEventSchema.validate(
                AgentEventDraft(
                    eventKind = kind,
                    threadId = "hidden-scope",
                    payloadJson = "{}",
                    idempotencyKey = "test:${kind.name}"
                )
            )
        }
    }

    @Test
    fun userDemonstrationActionCarriesActionBoundVisualEvidence() {
        val payload = LearningActionRecordedEventPayload(
            contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
            sessionId = "session-1",
            actionIndex = 1,
            source = LearningActionSource.USER_DEMONSTRATION,
            sourceCallId = null,
            toolName = "user_demonstration",
            argumentsJson = "{\"op\":\"tap\"}",
            resultSummary = "injected",
            success = true,
            evidenceRef = "learning/session-1/frames/001-after.jpg",
            packageName = "com.example",
            activityName = ".MainActivity",
            actionType = "TAP",
            beforeFrameRef = "learning/session-1/frames/001-before.jpg",
            afterFrameRef = "learning/session-1/frames/001-after.jpg",
            displayWidth = 1080,
            displayHeight = 2376,
            durationMillis = 95L
        )

        assertEquals(LearningActionSource.USER_DEMONSTRATION, payload.source)
        assertEquals("TAP", payload.actionType)
        assertEquals(1080, payload.displayWidth)
        assertEquals("learning/session-1/frames/001-after.jpg", payload.afterFrameRef)
    }

    private fun completedRun(
        semanticSuccess: Boolean,
        evidenceRefs: List<String>
    ) = CompiledSkillRunCompletedEventPayload(
        contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
        runId = "run-1",
        skillId = "skill-1",
        mode = CompiledSkillRunMode.REUSE,
        durationMillis = 120L,
        plannedSteps = 2,
        executedSteps = 2,
        divergenceCount = 0,
        semanticSuccess = semanticSuccess,
        localModelRounds = 0,
        localPromptTokens = 0,
        resultHashes = listOf("a".repeat(64)),
        verificationEvidenceRefs = evidenceRefs
    )
}
