// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.context.ModelContextAssembler
import com.droidance.config.markdownSection
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContextAssemblerTest {
    @Test fun decisionTailKeepsOriginalGoalAndOrderedUserCorrectionsTogether() {
        val original = ChatMessage(Role.USER, "Read the first three objects")
        val updates = listOf("Use the second page", "Read two objects instead; keep the original exclusions")
        val tail = assembler.buildMessagesForModel(listOf(original), original.content, null, emptyList(),
            "pure-vlm", null, 0 to 0, null, null, null, null, userUpdates = updates).last()
        assertTrue(tail.content.contains("<active_task>\n${original.content}\n</active_task>"))
        assertTrue(tail.content.indexOf(updates[0]) < tail.content.indexOf(updates[1]))
        assertTrue(tail.content.contains("<user_updates>"))
        assertEquals("Read the first three objects", original.content)
    }
    private val modelContextDocument = File("src/main/assets/rules/model_context.md").readText()

    private val assembler = ModelContextAssembler(
        maxTaskNoteChars = 4000,
        pureVlmMode = "pure-vlm",
        text = { key, values ->
            values.entries.fold(markdownSection(modelContextDocument, key)) { template, (name, value) ->
                template.replace("{$name}", value)
            }
        }
    )

    @Test
    fun entryContractPrecedesUntrustedScreenAndSurvivesHistoryRebuild() {
        val entry = markdownSection(modelContextDocument, "task_entry")
        val initial = assembler.initialStepPrompt("inspect device", "", "untrusted current page")
        assertTrue(initial.indexOf(entry) > initial.indexOf("</user_goal>"))
        assertTrue(initial.indexOf(entry) < initial.indexOf("untrusted current page"))
        val messages = assembler.buildMessagesForModel(
            listOf(ChatMessage(Role.USER, initial)), "inspect device", "current-image", emptyList(),
            "pure-vlm", 500 to 1100, 0 to 0, "fresh state", null, null, "current-observation")
        assertEquals(initial, messages.first().content)
        assertEquals("current-image", messages[messages.lastIndex - 1].imageBase64)
        assertEquals(null, messages.last().imageBase64)
        assertEquals(
            "[image_source observation_ref=current-observation current_screen=true coordinate_space=normalized_0_1000]",
            messages[messages.lastIndex - 1].content
        )
        assertTrue(messages.last().content.contains("current-observation"))
    }

    @Test
    fun durableResponsesReplayUsesTheProviderCallIdentity() {
        val call = com.droidance.core.agent.harness.PlannedToolCall(
            0, "round:call:0",
            com.droidance.core.agent.harness.ProviderToolCall("provider-call", "read", "{}"),
            com.droidance.core.agent.harness.RegisteredToolContract(
                "read", "Read", com.droidance.core.agent.event.ToolBinding.TURN_LOCAL,
                com.droidance.core.agent.event.ToolEffect.READ_ONLY, emptyMap()
            )
        )
        val units = com.droidance.core.agent.context.ContextUnitBuilder.build(listOf(
            com.droidance.core.agent.context.UserInputAgentItem("user", 1, "round",
                com.droidance.core.agent.context.UserInputKind.MESSAGE, "task"),
            com.droidance.core.agent.context.ToolCallAgentItem("call", 2, "round", call),
            com.droidance.core.agent.context.ToolResultAgentItem("result", 3, "round", "round:call:0",
                com.droidance.core.agent.tools.ToolProtocolResult.success("value"))
        ))
        val messages = com.droidance.core.agent.context.DurableContextMessageProjector.build(
            com.droidance.core.agent.context.DurableContextProjection(units, emptyList()), "task"
        )
        for (continuation in listOf(false, true)) {
            val state = com.droidance.core.model.llm.OpenAIResponsesTurnState()
            if (continuation) state.commitResponse("response")
            val request = com.droidance.core.model.llm.OpenAIResponsesProtocol.buildRequest(
                "model", messages, emptyList(), null, false, 1024, state
            )
            val input = request.getValue("input").toString()
            assertTrue(input.contains("provider-call"))
            assertFalse(input.contains("round:call:0"))
            assertTrue(input.contains("function_call_output"))
            assertTrue(input.contains("value"))
        }
        assertEquals("round:call:0", call.callId)
    }

    @Test
    fun responsesContinuationPreservesAllRequirementsAndLatestFrame() {
        val goal = "Read field A on page one, field B on page two, and field C on page three."
        val messages = assembler.buildMessagesForModel(
            messages = listOf(
                ChatMessage(role = Role.USER, content = goal),
                ChatMessage(role = Role.ASSISTANT, content = "previous navigation"),
                ChatMessage(role = Role.TOOL, content = "page changed", toolCallId = "call-1")
            ),
            taskGoal = goal, imageBase64 = "latest-frame", imageBase64List = emptyList(),
            imageMode = "pure-vlm", frameSize = 500 to 1100, frameOrigin = 0 to 0,
            freshScreenState = null, taskWorkingMemory = "A observed; B and C remain unobserved",
            taskResearchNote = null, rawObservationId = "current-observation"
        )
        for (continuation in listOf(false, true)) {
            val state = com.droidance.core.model.llm.OpenAIResponsesTurnState()
            if (continuation) state.commitResponse("previous-response")
            val request = com.droidance.core.model.llm.OpenAIResponsesProtocol.buildRequest(
                model = "test-model", messages = messages, tools = emptyList(),
                systemPrompt = "system", supportsVision = true, maxOutputTokens = 1024,
                state = state
            )
            val input = request.getValue("input").toString()
            assertTrue(input.contains(goal))
            assertTrue(input.contains("A observed; B and C remain unobserved"))
            assertTrue(input.contains("current-observation"))
            assertTrue(input.contains("latest-frame"))
            if (continuation) assertFalse(input.contains("previous navigation"))
        }
    }

    @Test
    fun immutableWirePrefixSurvivesFrameReplacementWithoutRetainingPreviousBindings() {
        val history = com.droidance.core.agent.context.CacheableRequestHistory()
        val goal = ChatMessage(Role.USER, "read the requested sources")
        val firstPrefix = history.prepare(listOf(goal), emptyList(), null)
        val first = assembler.buildMessagesForModel(firstPrefix, goal.content, "first-frame", emptyList(),
            "pure-vlm", 1080 to 1920, 0 to 0, null, null, null, "first-binding")
        history.recordSent(firstPrefix)
        val call = ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "read", "{}")))
        val receipt = ChatMessage(Role.TOOL, "committed receipt", toolCallId = "call")
        val secondPrefix = history.prepare(listOf(goal, call, receipt), emptyList(), null)
        val second = assembler.buildMessagesForModel(secondPrefix, goal.content, "second-frame", emptyList(),
            "pure-vlm", 1080 to 1920, 0 to 0, null, null, null, "second-binding")
        fun wire(messages: List<ChatMessage>) = com.droidance.core.model.llm.OpenAIResponsesProtocol.buildRequest(
            "model", messages, emptyList(), "system", true, 1024, com.droidance.core.model.llm.OpenAIResponsesTurnState()
        ).getValue("input") as kotlinx.serialization.json.JsonArray
        assertEquals(wire(first).first(), wire(second).first())
        assertFalse(wire(second).toString().contains("first-binding"))
        assertFalse(second.any { it.imageBase64 == "first-frame" })
        assertEquals(firstPrefix, secondPrefix.take(firstPrefix.size))
        assertEquals(listOf(call, receipt), secondPrefix.drop(firstPrefix.size))
    }

    @Test
    fun neutralImageSourceKeepsHistoricalOrderAndNativeComputerUsesTheCurrentFrame() {
        val retained = listOf(
            ChatMessage(Role.USER, "historical parent", imageBase64 = "parent-image", historicalImage = true),
            ChatMessage(Role.USER, "historical detail", imageBase64 = "detail-image", historicalImage = true)
        )
        val messages = assembler.buildMessagesForModel(retained, "continue", "current-image", emptyList(),
            "pure-vlm", 1080 to 1920, 0 to 0, null, null, null, "current-binding")
        assertEquals(retained, messages.take(retained.size))
        assertEquals(listOf("parent-image", "detail-image", "current-image"), messages.mapNotNull { it.imageBase64 })
        val state = com.droidance.core.model.llm.OpenAIResponsesTurnState().apply { rememberComputerCall("computer-call") }
        val request = com.droidance.core.model.llm.OpenAIResponsesProtocol.buildRequest(
            "model", messages, emptyList(), "system", true, 1024, state,
            includeComputerTool = true, computerFrameSize = 1080 to 1920
        )
        val input = request.getValue("input") as kotlinx.serialization.json.JsonArray
        val computerOutput = input.first().toString()
        assertTrue(computerOutput.contains("computer_call_output"))
        assertTrue(computerOutput.contains("current-image"))
        assertFalse(computerOutput.contains("parent-image"))
        assertFalse(computerOutput.contains("detail-image"))
        assertTrue(messages.last().content.contains("current-binding"))
        assertFalse(messages[messages.lastIndex - 1].content.contains("CURRENT_VISION_FRAME"))
    }

    @Test
    fun appendsCurrentUserTailWithoutMutatingHistoricalMessages() {
        val system = ChatMessage(role = Role.SYSTEM, content = "stable system prompt")
        val assistant = ChatMessage(role = Role.ASSISTANT, content = "previous answer")
        val history = listOf(system, assistant)

        val result = assembler.buildMessagesForModel(
            messages = history,
            taskGoal = "continue the task",
            imageBase64 = null,
            imageBase64List = emptyList(),
            imageMode = "structured",
            frameSize = null,
            frameOrigin = 0 to 0,
            freshScreenState = null,
            taskWorkingMemory = null,
            taskResearchNote = null,
            rawObservationId = null
        )

        assertEquals(3, result.size)
        assertSame(system, result[0])
        assertSame(assistant, result[1])
        assertEquals("stable system prompt", result[0].content)
        assertEquals("previous answer", result[1].content)
        assertEquals(Role.USER, result[2].role)
        assertTrue(result[2].content.contains("<active_task>\ncontinue the task\n</active_task>"))
    }

    @Test
    fun preservesMultipleImageOrderAndKeepsSingleImageFieldsMutuallyExclusive() {
        val images = listOf("first-image", "second-image", "third-image")
        val multipleMessages = assembler.buildMessagesForModel(
            messages = emptyList(),
            taskGoal = "inspect the images",
            imageBase64 = "legacy-single-image",
            imageBase64List = images,
            imageMode = "pure-vlm",
            frameSize = 1080 to 1920,
            frameOrigin = 0 to 0,
            freshScreenState = null,
            taskWorkingMemory = null,
            taskResearchNote = null,
            rawObservationId = null
        )
        val multiple = multipleMessages.first()

        assertEquals(images, multiple.imageBase64List)
        assertEquals(null, multiple.imageBase64)
        assertEquals("[image_source]", multiple.content)
        assertTrue(multipleMessages.last().content.contains(markdownSection(modelContextDocument, "current_screenshot")))
        assertTrue(multipleMessages.last().imageBase64List.isEmpty())

        val single = assembler.buildMessagesForModel(
            messages = emptyList(),
            taskGoal = "inspect the image",
            imageBase64 = "single-image",
            imageBase64List = emptyList(),
            imageMode = "pure-vlm",
            frameSize = null,
            frameOrigin = 0 to 0,
            freshScreenState = null,
            taskWorkingMemory = null,
            taskResearchNote = null,
            rawObservationId = null
        ).first()

        assertEquals("single-image", single.imageBase64)
        assertTrue(single.imageBase64List.isEmpty())
    }

    @Test
    fun keepsObservationIdAndCropCoordinateGuidanceInTheSameTailMessage() {
        val tail = assembler.buildMessagesForModel(
            messages = emptyList(),
            taskGoal = "inspect this frame",
            imageBase64 = "current-image",
            imageBase64List = emptyList(),
            imageMode = "pure-vlm",
            frameSize = 1080 to 1920,
            frameOrigin = 24 to 48,
            freshScreenState = "Current screen text",
            taskWorkingMemory = null,
            taskResearchNote = null,
            rawObservationId = "observation-42"
        ).last()

        val observationReference = markdownSection(modelContextDocument, "raw_observation_reference")
            .replace("{observationId}", "observation-42")
        assertTrue(tail.content.contains(observationReference))
        assertTrue(tail.content.contains("Attached frame dimensions: 1080x1920 pixels"))
        assertTrue(tail.content.contains("Action coordinate space: normalized integers x=0..1000 and y=0..1000"))
        assertTrue(tail.content.contains("This is a cropped frame. Keep using its complete normalized 0..1000 action space"))
        assertTrue(tail.content.contains("Current screen text"))
        assertTrue(tail.content.indexOf("observation-42") > tail.content.indexOf("Current screen text"))
    }

    @Test
    fun omitsFrameAndObservationReferenceWithoutContextAndUsesMissingStateInitialTemplate() {
        val withoutContext = assembler.buildMessagesForModel(
            messages = emptyList(),
            taskGoal = "answer from conversation",
            imageBase64 = null,
            imageBase64List = emptyList(),
            imageMode = "structured",
            frameSize = null,
            frameOrigin = 0 to 0,
            freshScreenState = null,
            taskWorkingMemory = null,
            taskResearchNote = null,
            rawObservationId = "stale-observation"
        ).single()

        assertFalse(withoutContext.content.contains("[CURRENT_VISION_FRAME]"))
        assertFalse(withoutContext.content.contains("stale-observation"))
        assertFalse(withoutContext.content.contains(markdownSection(modelContextDocument, "raw_observation_reference")
            .replace("{observationId}", "stale-observation")))

        val initial = assembler.initialStepPrompt(
            taskGoal = "answer from conversation",
            additionalContext = "Keep the existing conversation context.",
            preloadedScreenState = null
        )
        val missingScreenState = markdownSection(modelContextDocument, "missing_screen_state_entry")

        assertTrue(initial.contains("## Additional context\nKeep the existing conversation context."))
        assertTrue(initial.contains(missingScreenState))
        assertFalse(initial.contains("[CURRENT_VISION_FRAME]"))
    }

    @Test
    fun activeWorkspaceBodyBypassesFourThousandCharacterTaskNoteLimit() {
        val bodyMarker = "\uE000"
        val workspaceContext = "<model_authored_active_work_file>\n" +
            "workspace-start\n" + bodyMarker.repeat(5000) + "\nworkspace-end\n" +
            "</model_authored_active_work_file>"
        val note = "n".repeat(4000) + "TASK_NOTE_TRUNCATED_SENTINEL"
        val tail = assembler.buildMessagesForModel(
            messages = emptyList(),
            taskGoal = "continue research",
            imageBase64 = null,
            imageBase64List = emptyList(),
            imageMode = "structured",
            frameSize = null,
            frameOrigin = 0 to 0,
            freshScreenState = null,
            taskWorkingMemory = note,
            taskResearchNote = null,
            rawObservationId = null,
            taskWorkspaceContext = workspaceContext
        ).single().content

        assertTrue(tail.contains("workspace-start"))
        assertTrue(tail.contains("workspace-end"))
        assertEquals(5000, tail.count { it == bodyMarker.single() })
        assertFalse(tail.contains("TASK_NOTE_TRUNCATED_SENTINEL"))
    }

    @Test
    fun activeWorkspaceBodyIsRetainedWithCompletionAndRecoverySourceContexts() {
        val bodyMarker = "\uE001"
        val workspaceContext = "<model_authored_active_work_file>\n" +
            "record-prefix\n" + bodyMarker.repeat(5000) + "\nrecord-suffix\n" +
            "</model_authored_active_work_file>"
        val candidate = ToolCall("candidate", "done", "{}")
        for (feedbackKey in listOf("review_feedback", "recovery_feedback")) {
            val sourceContext = TaskSourceContext(feedbackKey)
            sourceContext.begin(candidate, "source review receipt")
            val projected = sourceContext.project(
                originalRequest = "original multi-view request",
                userSteers = listOf("inspect the current state"),
                history = listOf(ChatMessage(Role.TOOL, "blocked", toolCallId = candidate.id)),
                instruction = "$feedbackKey instruction",
                sourceIndex = "original-source-index",
                taskRequirements = "retained-requirements"
            )
            val tail = assembler.buildMessagesForModel(
                messages = projected,
                taskGoal = "original multi-view request",
                imageBase64 = null,
                imageBase64List = emptyList(),
                imageMode = "structured",
                frameSize = null,
                frameOrigin = 0 to 0,
                freshScreenState = null,
                taskWorkingMemory = "m".repeat(4000) + "WORKING_NOTE_SENTINEL",
                taskResearchNote = null,
                rawObservationId = null,
                taskWorkspaceContext = workspaceContext
            ).last().content

            assertTrue(tail.contains("record-prefix"))
            assertTrue(tail.contains("record-suffix"))
            assertEquals(5000, tail.count { it == bodyMarker.single() })
            assertTrue(projected.any { it.content.contains(feedbackKey) })
            assertFalse(tail.contains("WORKING_NOTE_SENTINEL"))
        }
    }
}
