// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.workspace.*
import com.droidance.core.model.llm.models.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Task-local files need neither an Android connection nor a running interpreter. */
class WorkspaceTool(
    private val workspace: TaskWorkspace,
    private val scope: () -> TurnEventScope,
    private val rules: (String) -> String,
    private val readArtifact: suspend (String) -> ToolExecutionResult,
    private val toolHelp: (String) -> ToolDefinition? = { null },
    private val sourceExists: (String) -> Boolean = { false },
    private val toolGuidance: (String) -> String = { "" }
) : Tool {
    companion object {
        fun isWorkingStateMutation(arguments: String): Boolean = runCatching {
            (Json.parseToJsonElement(arguments).jsonObject["op"] as? JsonPrimitive)?.contentOrNull in
                setOf("write", "append", "edit", "records", "focus")
        }.getOrDefault(false)
    }

    override val name = "workspace"
    override val tier = ToolTier.CORE
    override val category = ToolCategory.FILE
    override val definition get() = ToolDefinition(name, rules("description"), linkedMapOf(
        "op" to ToolParameter("string", "Workspace operation", true, listOf("list", "read", "write", "append", "edit", "focus", "artifact", "journal", "help", "records", "objects")),
        "records" to WorkspaceRecordProtocol.parameter,
        "record_ids" to ToolParameter("array", "For objects: exact IDs to read cumulative records. Omit for the complete paginated object index.", false,
            items = ToolParameter("string", "Exact record_id.", minLength = 1), allowedWhen = mapOf("op" to listOf("objects"))),
        "path" to ToolParameter("string", "Task-relative file path", required = false, requiredWhen = mapOf("op" to listOf("read","write","append","edit","focus","records"))),
        "text" to ToolParameter("string", "Required for write/append/edit. UTF-8 content; for edit, replacement of the exact old_text span. An empty string deletes that span.", required = false, requiredWhen = mapOf("op" to listOf("write","append","edit"))),
        "old_text" to ToolParameter("string", "For edit: exact nonempty text occurring once in the specified version", required = false, requiredWhen = mapOf("op" to listOf("edit"))),
        "activate" to ToolParameter("boolean", "For write/append/edit: select this file as the active working record; its latest content is attached to subsequent decisions.", required = false),
        "expected_ref" to ToolParameter("string", "Required for edit and updates to an existing records file: copy content_ref from the inspected version. For other operations: optional version precondition.", required = false, requiredWhen = mapOf("op" to listOf("edit"))),
        "ref" to ToolParameter("string", "Retained source reference for artifact", required = false, requiredWhen = mapOf("op" to listOf("artifact","help"))),
        "through_sequence" to ToolParameter("integer", "Journal or objects snapshot returned by the first page; retain it with the same record_ids when paging.", required = false),
        "source_turn_id" to ToolParameter("string", "Explicit earlier task in this conversation, for read/list/artifact/journal/objects only. Omit or leave empty for the current task.", required = false),
        "offset" to ToolParameter("integer", "UTF-16 text offset or list offset, default zero", required = false)
    ))
    override suspend fun execute(arguments: String): ToolExecutionResult {
        val policy = Json.decodeFromString<WorkspacePolicy>(rules("policy")).also { it.validate() }
        return try {
            val args = Json.parseToJsonElement(arguments).jsonObject
            validateMutationArguments(args)?.let { return it }
            fun value(key: String) = args[key]?.jsonPrimitive?.contentOrNull
                ?: throw MissingWorkspaceArgument(key)
            fun optionalReference(key: String) = args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            fun requiredReference(key: String) = optionalReference(key)
                ?: throw MissingWorkspaceArgument(key)
            val offset = args["offset"]?.jsonPrimitive?.int ?: 0
            require(offset >= 0) { "INVALID_CURSOR" }
            val owner = scope()
            val activate = args["activate"]?.let {
                (it as? JsonPrimitive)?.lenientBooleanOrNull()
                    ?: error("INVALID_ACTIVATE")
            } ?: false
            require(!activate || value("op") in setOf("write", "append", "edit", "records")) { "WORKSPACE_ACTIVATE_REQUIRES_WRITE" }
            val sourceTurn = optionalReference("source_turn_id")
            require(sourceTurn == null || value("op") in setOf("list", "read", "artifact", "journal", "objects")) { "WORKSPACE_HISTORY_READ_ONLY" }
            val readable = workspace.readScope(owner, sourceTurn)
            var stateChanged = false
            val output = when (value("op")) {
                "help" -> {
                    val name = value("ref")
                    val definition = toolHelp(name) ?: return ToolProtocolResult.invalidArgument("ref", "unknown_tool", name)
                    val documented = Json.encodeToJsonElement(definition).jsonObject
                    val guidance = toolGuidance(name)
                    if (guidance.isBlank()) documented else JsonObject(documented + ("guidance" to JsonPrimitive(guidance)))
                }
                "records" -> {
                    val path = value("path")
                    val existing = workspace.list(owner).firstOrNull { it.path == path }
                    val expected = optionalReference("expected_ref")
                    if (existing != null && expected != existing.contentRef) return ToolProtocolResult.notExecuted(
                        "WORKSPACE_VERSION_CHANGED", retryable = true, details = buildJsonObject {
                            put("path", path); put("current_ref", existing.contentRef)
                            put("recovery_tool_call", buildJsonObject {
                                put("name", name); put("arguments", buildJsonObject { put("op", "read"); put("path", path) })
                            })
                        })
                    val prior = existing?.let {
                        runCatching { Json.parseToJsonElement(workspace.readVersion(it)) as? JsonObject }.getOrNull()
                            ?: return ToolProtocolResult.notExecuted("RECORD_FILE_SHAPE_INVALID", retryable = true,
                                details = buildJsonObject {
                                    put("path", path); put("current_ref", it.contentRef)
                                    put("required_format", "JSON object with a records array; use a separate new file for records")
                                })
                    }
                    val merged = WorkspaceRecordProtocol.merge(
                        prior,
                        args["records"] as? JsonArray ?: error("RECORDS_ARRAY_REQUIRED"), sourceExists)
                    val written = workspace.write(owner, path, merged.toString(), false, expected,
                        checkNotNull(currentCoroutineContext()[ToolInvocation]).callId, policy, activate = activate)
                    stateChanged = existing?.contentRef != workspace.list(owner).firstOrNull { it.path == path }?.contentRef
                    Json.encodeToJsonElement(written)
                }
                "journal" -> workspace.journal(readable, args["through_sequence"]?.jsonPrimitive?.long, offset, policy.pageChars)
                "objects" -> {
                    require("record_ids" !in args || args["record_ids"] is JsonArray) { "MODEL_OBJECT_IDS_ARRAY_REQUIRED" }
                    workspace.objects(readable, (args["record_ids"] as? JsonArray).orEmpty().map {
                        val id = it as? JsonPrimitive
                        require(id?.isString == true && id.content.isNotBlank()) { "MODEL_OBJECT_ID_REQUIRED" }
                        id.content
                    }, args["through_sequence"]?.jsonPrimitive?.long, offset, policy.pageChars)
                }
                "focus" -> {
                    val previous = workspace.activeFile(owner)
                    val selected = workspace.focus(owner, value("path"),
                        checkNotNull(currentCoroutineContext()[ToolInvocation]).callId)
                    stateChanged = previous?.path != workspace.activeFile(owner)?.path
                    Json.encodeToJsonElement(selected)
                }
                "artifact" -> return readArtifact(buildJsonObject {
                    put("mode", "artifact"); put("ref", value("ref")); put("offset", offset)
                    sourceTurn?.let { put("source_turn_id", it) }
                }.toString()).copy(stateChanged = false)
                "list" -> {
                    val files = workspace.list(readable)
                    require(offset <= files.size) { "INVALID_CURSOR" }
                    val selected = files.drop(offset).take(16)
                    buildJsonObject {
                        put("files", Json.encodeToJsonElement(selected)); put("next_offset", offset + selected.size)
                        put("has_more", offset + selected.size < files.size)
                    }
                }
                "read" -> {
                    val path = value("path")
                    TaskWorkspace.validatePath(path)
                    val version = workspace.list(readable).firstOrNull { it.path == path }
                        ?: error("WORKSPACE_FILE_NOT_FOUND")
                    val expected = optionalReference("expected_ref")
                    check(expected == null || expected == version.contentRef) { "WORKSPACE_VERSION_CHANGED" }
                    val text = workspace.readVersion(version)
                    require(offset <= text.length) { "INVALID_CURSOR" }
                    var end = minOf(text.length, offset + policy.pageChars)
                    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                    buildJsonObject {
                        put("path", path); put("content_ref", version.contentRef); put("offset", offset)
                        put("text", text.substring(offset, end)); put("next_offset", end)
                        put("has_more", end < text.length); put("total_chars", text.length)
                    }
                }
                "write", "append", "edit" -> {
                    val path = value("path")
                    val previous = workspace.list(owner).firstOrNull { it.path == path }
                    val previousActive = if (activate) workspace.activeFile(owner) else null
                    val written = workspace.write(
                        owner, path, value("text"), value("op") == "append",
                        if (value("op") == "edit") requiredReference("expected_ref") else optionalReference("expected_ref"),
                        checkNotNull(currentCoroutineContext()[ToolInvocation]).callId, policy, activate = activate,
                        oldText = if (value("op") == "edit") value("old_text") else null
                    )
                    stateChanged = previous?.contentRef != workspace.list(owner).firstOrNull { it.path == path }?.contentRef ||
                        (activate && previousActive?.path != workspace.activeFile(owner)?.path)
                    Json.encodeToJsonElement(written)
                }
                else -> return ToolProtocolResult.invalidArgument("op", "unsupported_operation")
            }
            ToolProtocolResult.success(output.toString()).copy(stateChanged = stateChanged)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: WorkspaceRecordProtocol.RecordValidationException) {
            ToolProtocolResult.notExecuted(error.code, true, details = buildJsonObject {
                put("error_path", error.path)
            })
        }
        catch (error: MissingWorkspaceArgument) {
            if (error.parameter != "expected_ref") ToolProtocolResult.missingArgument(error.parameter)
            else ToolProtocolResult.notExecuted("ARGUMENT_SCHEMA_MISMATCH", retryable = true,
                details = buildJsonObject {
                    put("missing_parameter", "expected_ref")
                    put("recovery_tool_call", buildJsonObject {
                        put("name", name)
                        put("arguments", buildJsonObject {
                            put("op", "read")
                            put("path", Json.parseToJsonElement(arguments).jsonObject.getValue("path"))
                        })
                    })
                })
        }
        catch (error: Exception) { ToolProtocolResult.failed("WORKSPACE_ERROR", false, error) }
    }

    private class MissingWorkspaceArgument(val parameter: String) : IllegalArgumentException()

    private fun validateMutationArguments(args: JsonObject): ToolExecutionResult? {
        val op = (args["op"] as? JsonPrimitive)?.contentOrNull
        if (op !in setOf("write", "append", "edit")) return null
        val required = if (op == "edit") listOf("path", "old_text", "text", "expected_ref")
            else listOf("path", "text")
        val missing = required.filter { args[it] == null || args[it] == JsonNull ||
            (it == "expected_ref" && (args[it] as? JsonPrimitive)?.contentOrNull?.isBlank() == true) }
        val invalid = required.filter { key ->
            val value = args[key]
            value != null && value != JsonNull &&
                (value !is JsonPrimitive || !value.isString || (key == "path" && value.content.isBlank()))
        }
        val unknown = args.keys - definition.parameters.keys
        if (missing.isEmpty() && invalid.isEmpty() && unknown.isEmpty()) return null
        return ToolProtocolResult.notExecuted("ARGUMENT_SCHEMA_MISMATCH", retryable = true,
            details = buildJsonObject {
                missing.firstOrNull()?.let { put("missing_parameter", it) }
                put("missing_parameters", JsonArray(missing.map(::JsonPrimitive)))
                put("invalid_parameters", JsonArray(invalid.map(::JsonPrimitive)))
                put("unexpected_parameters", JsonArray(unknown.sorted().map(::JsonPrimitive)))
                put("required_parameters", JsonArray(required.map(::JsonPrimitive)))
                put("replacement_parameter", "text")
                if ("expected_ref" in missing || "expected_ref" in invalid) {
                    put("recovery_tool_call", buildJsonObject {
                        put("name", name)
                        put("arguments", buildJsonObject {
                            put("op", "read")
                            (args["path"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
                                ?.let { put("path", it) }
                        })
                    })
                }
            })
    }
}
