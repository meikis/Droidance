// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.harness.ToolArgumentSchemaValidator
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.WorkspacePolicy
import com.droidance.core.agent.workspace.WorkspaceFileVersion
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.model.llm.models.toJsonSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** The runtime owns serialization/version binding; the same Agent authors and reviews content. */
object CompletionSubmissionProtocol {
    const val ARGUMENT = "completion_submission"
    data class Resolved(val calls: List<ToolCall>, val errors: Map<String, String>, val references: String,
                        val reviews: Map<String, JsonObject>, val errorDetails: Map<String, JsonObject>)

    /** One fixed content shape. Candidate identity and publication state stay runtime-owned. */
    fun parameters(content: Map<String, ToolParameter>) = content +
        ("deliverables" to ToolParameter("array", "Optional task files, linked to their saved versions.", false,
            items = ToolParameter("object", "", properties = linkedMapOf(
                "path" to ToolParameter("string", "Task workspace path."),
                "result_index" to ToolParameter("integer", "Zero-based result/item index.")
            ), additionalProperties = false)))

    fun contentSchema(parameters: Map<String, ToolParameter>): JsonObject =
        ToolParameter("object", "Completion content", properties = parameters, additionalProperties = false).toJsonSchema()

    suspend fun currentCandidateFacts(workspace: TaskWorkspace, scope: TurnEventScope, tool: String): JsonObject? {
        if (tool !in setOf("done", "present_result_card")) return null
        val file = workspace.list(scope).singleOrNull { it.path == ".completion/$tool.json" } ?: return null
        val envelope = Json.parseToJsonElement(workspace.readVersion(file)).jsonObject
        return buildJsonObject {
            put("tool", tool)
            put("current_candidate_id", CompletionReviewCandidate.from(tool, envelope.getValue("body").toString()).id)
            put("submitted_content", envelope.getValue("submission"))
        }
    }

    suspend fun resolve(calls: List<ToolCall>, workspace: TaskWorkspace, scope: TurnEventScope,
                        policy: WorkspacePolicy, contentParameters: Map<String, Map<String, ToolParameter>>,
                        canOpenLink: suspend (String) -> Boolean = { false }): Resolved {
        val errors = linkedMapOf<String, String>()
        val details = linkedMapOf<String, JsonObject>()
        val refs = linkedMapOf<String, JsonElement>()
        val reviews = linkedMapOf<String, JsonObject>()
        val resolved = calls.map { call ->
            if (call.name !in setOf("done", "present_result_card")) return@map call
            val path = ".completion/${call.name}.json"
            try {
                val args = Json.parseToJsonElement(call.arguments).jsonObject
                val expected = parameters(contentParameters.getValue(call.name)) +
                    ("action_title" to ToolParameter("string", "", false))
                ToolArgumentSchemaValidator.mismatchDetail(expected, call.arguments)?.let { error(it) }
                require((args.keys - expected.keys).isEmpty()) { "completion_unknown_fields" }
                val bodyKey = if (call.name == "done") "results" else "items"
                val previous = workspace.list(scope).singleOrNull { it.path == path }
                val previousEnvelope = previous?.let { Json.parseToJsonElement(workspace.readVersion(it)).jsonObject }
                val submission = JsonObject(args - "action_title")
                val body = (submission - "deliverables").toMutableMap()
                val entries = body.getValue(bodyKey).jsonArray.toMutableList()
                val files = workspace.list(scope)
                val attachments = (submission["deliverables"] as? JsonArray).orEmpty().mapIndexed { position, element ->
                    val item = element.jsonObject
                    val filePath = item.getValue("path").jsonPrimitive.content
                    require(!filePath.startsWith(".completion/")) { "completion_draft_is_not_a_deliverable" }
                    val file = files.singleOrNull { it.path == filePath } ?: error("deliverable_not_found:$filePath")
                    val text = workspace.readVersion(file)
                    val index = item.getValue("result_index").jsonPrimitive.int
                    if (index !in entries.indices) {
                        details[call.id] = buildJsonObject {
                            put("field", "deliverables[$position].result_index")
                            put("received_value", index); put("result_field", bodyKey)
                            put("result_count", entries.size)
                            put("valid_indices", JsonArray(entries.indices.map(::JsonPrimitive)))
                        }
                        error("deliverable_result_index_out_of_range")
                    }
                    val entry = entries[index].jsonObject
                    val link = WorkspaceDeliverable.link(scope.turnId, file.path, file.contentRef)
                    val entryText = entry.getValue("text").jsonPrimitive.content
                    if (link !in CompletionLinks.destinations(entryText)) {
                        val label = file.path.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                        entries[index] = JsonObject(entry + ("text" to JsonPrimitive(entryText + "\n\n[$label]($link)")))
                    }
                    buildJsonObject { put("path", file.path); put("content_ref", file.contentRef); put("text", text) }
                }
                body[bodyKey] = JsonArray(entries)
                val envelope = buildJsonObject {
                    put("body", JsonObject(body)); put("submission", submission)
                    put("deliverables", JsonArray(attachments.map { JsonObject(it - "text") }))
                }
                val unchanged = previousEnvelope == envelope
                val stored = if (unchanged) checkNotNull(previous) else workspace.write(scope, path,
                    envelope.toString(), false, null, "completion-${call.id}", policy,
                    activate = false, runtimeOwned = true)
                refs[call.id] = buildJsonObject {
                    put("path", stored.path); put("content_ref", stored.contentRef)
                    put("native_submission", submission)
                    put("deliverables_to_verify", JsonArray(attachments))
                }
                // Retain the latest proposal before link checks, but never publish a rejected one.
                validateLinks(JsonObject(body), attachments, scope, canOpenLink)?.let {
                    details[call.id] = it; error("completion_link_unresolvable")
                }
                if (unchanged) reviews[call.id] = buildJsonObject {
                    put("candidate_id", CompletionReviewCandidate.from(call.name, JsonObject(body).toString()).id)
                }
                call.copy(arguments = JsonObject(body + args.filterKeys { it == "action_title" }).toString())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                errors[call.id] = error.message ?: "completion_submission_unavailable"
                currentCandidateFacts(workspace, scope, call.name)?.let {
                    details[call.id] = JsonObject(it + details[call.id].orEmpty())
                }
                call
            }
        }
        return Resolved(resolved, errors, JsonObject(refs).toString(), reviews, details)
    }

    private suspend fun validateLinks(body: JsonObject, files: List<JsonObject>, scope: TurnEventScope,
                                      canOpenLink: suspend (String) -> Boolean, prefix: String = ""): JsonObject? {
        val generated = files.map { WorkspaceDeliverable.link(scope.turnId,
            it.getValue("path").jsonPrimitive.content, it.getValue("content_ref").jsonPrimitive.content) }.toSet()
        val bodyKey = if ("results" in body) "results" else "items"
        val entries = body.getValue(bodyKey).jsonArray
        val links = entries.flatMapIndexed { index, element ->
            val entry = element.jsonObject
            CompletionLinks.destinations(entry.getValue("text").jsonPrimitive.content).map { "$bodyKey[$index].text" to it } +
                listOfNotNull(entry["deeplink"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                    ?.let { "$bodyKey[$index].deeplink" to it })
        } + files.flatMapIndexed { index, file ->
            file["text"]?.jsonPrimitive?.content?.let(CompletionLinks::destinations).orEmpty()
                .map { "deliverables[$index].content" to it }
        }
        for ((field, link) in links.distinctBy { it.second }) {
            if (link !in generated && (WorkspaceDeliverable.isLink(link) || !canOpenLink(link))) {
                // Identify the repair location without echoing a URI that may contain a protected value.
                return buildJsonObject { put("field", prefix + field) }
            }
        }
        return null
    }
}
