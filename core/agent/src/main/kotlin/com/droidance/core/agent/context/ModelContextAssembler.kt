// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role

/** Appends request-local images and the mutable decision tail after immutable history. */
class ModelContextAssembler(
    private val maxTaskNoteChars: Int,
    private val pureVlmMode: String,
    private val text: (String, Map<String, String>) -> String
) {
    private fun prompt(key: String, values: Map<String, String> = emptyMap()): String = text(key, values)

    fun initialStepPrompt(
        taskGoal: String,
        additionalContext: String,
        preloadedScreenState: String?
    ): String = buildString {
        appendLine("<user_goal>")
        appendLine(taskGoal)
        appendLine("</user_goal>")
        appendLine(prompt("task_entry"))
        appendLine()
        if (additionalContext.isNotBlank()) {
            appendLine("## " + prompt("additional_context"))
            appendLine(additionalContext)
        }
        appendLine()
        if (!preloadedScreenState.isNullOrBlank()) {
            appendLine(prompt("preloaded_screen"))
            appendLine("<untrusted_screen_content>")
            appendLine(preloadedScreenState.take(8000))
            appendLine("</untrusted_screen_content>")
        } else {
            appendLine(prompt("missing_screen_state_entry"))
        }
    }


    fun buildMessagesForModel(
        messages: List<ChatMessage>,
        taskGoal: String,
        imageBase64: String?,
        imageBase64List: List<String>,
        imageMode: String,
        frameSize: Pair<Int, Int>?,
        frameOrigin: Pair<Int, Int>,
        freshScreenState: String?,
        taskWorkingMemory: String?,
        taskResearchNote: String?,
        rawObservationId: String?,
        taskExecutionFacts: String = "",
        taskWorkspaceContext: String = "",
        screenUnchanged: Boolean = false,
        userUpdates: List<String> = emptyList()
    ): List<ChatMessage> {
        val attachedImages = imageBase64List.ifEmpty {
            imageBase64?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        }
        val tailMessage = currentModelTailMessage(
            taskGoal = taskGoal,
            userUpdates = userUpdates,
            hasAttachedImage = attachedImages.isNotEmpty(),
            imageMode = imageMode,
            frameSize = frameSize,
            frameOrigin = frameOrigin,
            freshScreenState = freshScreenState,
            taskWorkingMemory = taskWorkingMemory,
            taskResearchNote = taskResearchNote,
            rawObservationId = rawObservationId,
            taskExecutionFacts = taskExecutionFacts,
            taskWorkspaceContext = taskWorkspaceContext,
            screenUnchanged = screenUnchanged
        )
        if (tailMessage.isBlank()) return messages.toList()
        // An image identifies an immutable source. Only the following decision tail binds
        // that source to current controls; neither belongs in the reusable history prefix.
        val image = attachedImages.takeIf { it.isNotEmpty() }?.let {
            ChatMessage(
                role = Role.USER,
                content = rawObservationId?.trim()?.takeIf(String::isNotBlank)
                    ?.let { ref -> "[image_source observation_ref=$ref current_screen=true coordinate_space=normalized_0_1000]" } ?: "[image_source]",
                imageBase64 = it.singleOrNull(),
                imageBase64List = it.takeIf { images -> images.size > 1 }.orEmpty()
            )
        }
        return messages + listOfNotNull(image) + ChatMessage(role = Role.USER, content = tailMessage)
    }

    private fun currentModelTailMessage(
        taskGoal: String,
        userUpdates: List<String>,
        hasAttachedImage: Boolean,
        imageMode: String,
        frameSize: Pair<Int, Int>?,
        frameOrigin: Pair<Int, Int>,
        freshScreenState: String?,
        taskWorkingMemory: String?,
        taskResearchNote: String?,
        rawObservationId: String?,
        taskExecutionFacts: String = "",
        taskWorkspaceContext: String = "",
        screenUnchanged: Boolean = false
    ): String = buildString {
        if (taskGoal.isNotBlank()) {
            appendLine("<active_task>")
            appendLine(taskGoal.trim())
            appendLine("</active_task>")
        }
        if (userUpdates.isNotEmpty()) {
            appendLine("<user_updates>")
            userUpdates.forEach(::appendLine)
            appendLine("</user_updates>")
        }
        val hasScreenContext = hasAttachedImage || screenUnchanged || !freshScreenState.isNullOrBlank()
        if (hasScreenContext) {
            appendLine()
            appendLine(
                currentVisionFrameMessage(
                    hasAttachedImage, imageMode, frameSize, frameOrigin, freshScreenState, screenUnchanged
                )
            )
            rawObservationId?.trim()?.takeIf(String::isNotBlank)?.let { observationId ->
                appendLine(prompt("raw_observation_reference", mapOf("observationId" to observationId)))
            }
        }
        if (taskExecutionFacts.isNotBlank()) appendLine(taskExecutionFacts)
        if (taskWorkspaceContext.isNotBlank()) appendLine(taskWorkspaceContext)
        val note = taskWorkingMemory?.trim().orEmpty().take(maxTaskNoteChars)
        if (note.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("<task_working_memory>")
            appendLine(note)
            appendLine("</task_working_memory>")
        }
        val researchNote = taskResearchNote?.trim().orEmpty()
        if (researchNote.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("<task_research_note>")
            appendLine(prompt("retained_findings"))
            appendLine(researchNote)
            appendLine("</task_research_note>")
        }
    }.trimEnd()


    private fun currentVisionFrameMessage(
        hasAttachedImage: Boolean,
        imageMode: String,
        frameSize: Pair<Int, Int>?,
        frameOrigin: Pair<Int, Int>,
        freshScreenState: String?,
        screenUnchanged: Boolean = false
    ): String = buildString {
        appendLine("[CURRENT_VISION_FRAME]")
        if (screenUnchanged) {
            appendLine(prompt("screen_unchanged_frame"))
        } else if (hasAttachedImage) {
            appendLine(prompt("current_screenshot"))
        } else {
            appendLine(prompt("current_screen_state"))
        }
        if (frameSize != null) {
            appendLine(prompt("frame_dimensions", mapOf("width" to frameSize.first.toString(), "height" to frameSize.second.toString())))
            appendLine(prompt("action_coordinates"))
        }
        if (frameOrigin != (0 to 0)) {
            appendLine(prompt("cropped_frame"))
        }
        when (imageMode) {
            pureVlmMode -> {
                appendLine(prompt("current_vision_pure_vlm"))
            }
            else -> {
                appendLine(prompt("structured_mode"))
            }
        }
        if (!freshScreenState.isNullOrBlank()) {
            appendLine()
            appendLine("<untrusted_screen_content>")
            appendLine(freshScreenState.take(5000))
            appendLine("</untrusted_screen_content>")
        }
    }.trimEnd()
}
