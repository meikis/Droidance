// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.event.*
import kotlinx.serialization.json.*

/** Mechanical history facts for the same Agent; a changed file is not semantic progress. */
data class WorkspaceBrowsingProgress(
    val browseCallsSinceRecordChange: Int,
    val recentObservationRefs: List<String>,
    val activeFile: WorkspaceFileVersion?,
    val deviceCallsSinceRecordChange: Int
) {
    fun facts(): JsonObject = buildJsonObject {
        put("browse_calls_since_record_change", browseCallsSinceRecordChange)
        put("device_calls_since_record_change", deviceCallsSinceRecordChange)
        put("active_path", activeFile?.path)
        put("active_content_ref", activeFile?.contentRef)
        put("recent_observation_refs", JsonArray(recentObservationRefs.map(::JsonPrimitive)))
    }

    fun reviewDue(policy: WorkspaceContextPolicy): Boolean =
        deviceCallsSinceRecordChange >= policy.deviceChangesBeforeReview ||
            (activeFile == null && browseCallsSinceRecordChange > 0)

    companion object {
        fun isBrowsing(tool: String, arguments: String, policy: WorkspaceContextPolicy): Boolean {
            val operations = policy.browsingOperations[tool] ?: return false
            val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return false
            if (tool != com.droidance.core.agent.tools.DeviceToolNames.SCREEN_ACT)
                return (args["op"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() in operations
            return (args["actions"] as? JsonArray).orEmpty().any { action ->
                ((action as? JsonObject)?.get("op") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() in operations
            }
        }

        fun project(events: List<AgentEventEnvelope>, scope: TurnEventScope,
                    policy: WorkspaceContextPolicy): WorkspaceBrowsingProgress {
            policy.validate()
            val ordered = events.filter { it.threadId == scope.threadId && it.turnId == scope.turnId }
                .distinctBy { it.eventId }.sortedBy { it.sequence }
            val active = TaskWorkspace.activeFile(ordered, scope)
            val calls = mutableMapOf<String, ToolCallEventPayload>()
            val versions = mutableMapOf<String, String>()
            var selectedPath: String? = null
            var deviceChanges = 0
            var browsing = 0
            val observations = ArrayDeque<String>()
            for ((index, event) in ordered.withIndex()) {
                when (event.eventKind) {
                    AgentEventKind.USER_STEER -> { browsing = 0; observations.clear() }
                    AgentEventKind.WORKSPACE_FOCUS_CHANGED -> {
                        selectedPath = Json.decodeFromString<WorkspaceFocus>(event.payloadJson).path
                    }
                    AgentEventKind.WORKSPACE_FILE_WRITTEN -> {
                        val version = Json.decodeFromString<WorkspaceFileVersion>(event.payloadJson)
                        // activate=true commits these two events atomically. A standalone later
                        // focus must not retroactively credit an unrelated earlier file write.
                        val next = ordered.getOrNull(index + 1)
                        val activatesThisWrite = next?.eventKind == AgentEventKind.WORKSPACE_FOCUS_CHANGED &&
                            next.idempotencyKey == event.idempotencyKey.replaceFirst("workspace:", "workspace-focus:") &&
                            Json.decodeFromString<WorkspaceFocus>(next.payloadJson).path == version.path
                        val changed = versions.put(version.path, version.contentRef) != version.contentRef
                        if ((version.path == selectedPath || activatesThisWrite) && changed && version.chars > 0) {
                            deviceChanges = 0
                            browsing = 0
                            observations.clear()
                        }
                    }
                    AgentEventKind.TOOL_CALL -> event.callId?.let {
                        calls[it] = Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
                    }
                    AgentEventKind.TOOL_FINISHED, AgentEventKind.TOOL_CALL_REJECTED -> {
                        val call = calls[event.callId] ?: continue
                        val result = if (event.eventKind == AgentEventKind.TOOL_FINISHED)
                            Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson).result
                        else Json.decodeFromString<ToolCallRejectedEventPayload>(event.payloadJson).result
                        if (com.droidance.core.agent.tools.UnifiedDeviceToolProtocol.isObservationOnlyAction(call.name, call.argumentsJson)) continue
                        if (result.executionStatus != ToolExecutionStatus.SUCCEEDED || call.binding != ToolBinding.DEVICE_BOUND ||
                            call.effect != ToolEffect.STATE_CHANGING) continue
                        deviceChanges++
                        if (isBrowsing(call.name, call.argumentsJson, policy)) {
                            browsing++
                            result.observationId?.let { observations.addLast(it) }
                            while (observations.size > policy.deviceChangesBeforeReview) observations.removeFirst()
                        } else {
                            browsing = 0
                            observations.clear()
                        }
                    }
                    else -> Unit
                }
            }
            return WorkspaceBrowsingProgress(browsing, observations.toList(), active, deviceChanges)
        }
    }
}
