// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.device.permission.AndroidContentPermissionResolver
import com.droidance.core.device.permission.PendingPermissionRequest
import com.droidance.core.device.permission.PermissionBroker
import com.droidance.core.device.permission.PermissionRequestOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Universal access to Android ContentProviders (query, insert, update, delete, batch). */
class AndroidContentQueryService(
    private val context: Context,
    private val permissionBroker: PermissionBroker,
    private val permissionOwner: suspend () -> Pair<String, String>,
    private val permissionReason: (String) -> String,
    private val permissionTimeoutMillis: () -> Long,
    private val onPermissionWaiting: suspend (PendingPermissionRequest) -> Unit,
    private val onPermissionResolved: suspend (PendingPermissionRequest, PermissionRequestOutcome) -> Unit,
    private val platformDispatch: (suspend (String, String) -> ToolExecutionResult)?
) {
    suspend fun query(arguments: String): ToolExecutionResult {
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val rawUri = input["uri"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (rawUri.isBlank() || uri?.scheme != "content") {
            return ToolProtocolResult.notExecuted(
                code = "CONTENT_URI_REQUIRED",
                retryable = true,
                details = buildJsonObject { put("required_scheme", JsonPrimitive("content")) }
            )
        }

        val requiredPermissions = AndroidContentPermissionResolver.requiredPermissions(uri, write = false)
        val missingPermissions = permissionBroker.missingPermissions(requiredPermissions)
        if (missingPermissions.isNotEmpty()) {
            val outcome = permissionBroker.request(
                permissions = missingPermissions,
                turnId = permissionOwner().first,
                callId = permissionOwner().second,
                reason = permissionReason(rawUri.take(160)),
                timeoutMillis = permissionTimeoutMillis(),
                onWaiting = onPermissionWaiting,
                onResolved = onPermissionResolved
            )
            if (outcome != PermissionRequestOutcome.GRANTED) {
                return ToolProtocolResult.notExecuted(
                    code = "PERMISSION_${outcome.name}",
                    retryable = outcome == PermissionRequestOutcome.TIMED_OUT,
                    details = buildJsonObject {
                        put("uri", JsonPrimitive(rawUri.take(300)))
                        put("permissions", JsonArray(missingPermissions.map(::JsonPrimitive)))
                        put("platform_status", JsonObject(missingPermissions.associateWith { JsonPrimitive(permissionBroker.platformStatus(it)) }))
                    }
                )
            }
        }

        val projection = input["projection"]?.let { element ->
            stringArray(element, "projection") ?: return invalidArray("projection")
        }
        val selectionArgs = input["selection_args"]?.let { element ->
            stringArray(element, "selection_args") ?: return invalidArray("selection_args")
        }
        val selection = input["selection"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val sortOrder = input["sort_order"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val limit = (input["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = (input["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)

        return try {
            platformDispatch?.let { return it("query", arguments) }
            com.droidance.core.device.agent.device.RuntimeContentProviders.acquire(requireNotNull(uri.authority))
            val cursor = context.contentResolver.query(
                uri,
                projection?.toTypedArray(),
                selection,
                selectionArgs?.toTypedArray(),
                sortOrder
            ) ?: return ToolProtocolResult.failed(
                code = "CONTENT_QUERY_FAILED",
                retryable = true,
                details = buildJsonObject {
                    put("uri", JsonPrimitive(rawUri.take(300)))
                    put("reason", JsonPrimitive("provider_returned_no_cursor"))
                }
            )
            cursor.use { formatResult(rawUri, it, limit, offset) }
        } catch (e: SecurityException) {
            ToolProtocolResult.failed("PERMISSION_MISSING", retryable = true, error = e)
        } catch (e: IllegalArgumentException) {
            ToolProtocolResult.failed("CONTENT_QUERY_INVALID", retryable = true, error = e)
        } catch (e: Exception) {
            ToolProtocolResult.failed("CONTENT_QUERY_FAILED", retryable = true, error = e)
        }
    }

    suspend fun insert(arguments: String): ToolExecutionResult {
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val rawUri = input["uri"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (rawUri.isBlank() || uri?.scheme != "content") {
            return ToolProtocolResult.notExecuted(
                code = "CONTENT_URI_REQUIRED",
                retryable = true,
                details = buildJsonObject { put("required_scheme", JsonPrimitive("content")) }
            )
        }
        val valuesElement = input["values"] as? JsonObject
            ?: return ToolProtocolResult.missingArgument("values")

        val requiredPermissions = AndroidContentPermissionResolver.requiredPermissions(uri, write = true)
        val missingPermissions = permissionBroker.missingPermissions(requiredPermissions)
        if (missingPermissions.isNotEmpty()) {
            val outcome = permissionBroker.request(
                permissions = missingPermissions,
                turnId = permissionOwner().first,
                callId = permissionOwner().second,
                reason = permissionReason(rawUri.take(160)),
                timeoutMillis = permissionTimeoutMillis(),
                onWaiting = onPermissionWaiting,
                onResolved = onPermissionResolved
            )
            if (outcome != PermissionRequestOutcome.GRANTED) {
                return ToolProtocolResult.notExecuted(
                    code = "PERMISSION_${outcome.name}",
                    retryable = outcome == PermissionRequestOutcome.TIMED_OUT,
                    details = buildJsonObject {
                        put("uri", JsonPrimitive(rawUri.take(300)))
                        put("permissions", JsonArray(missingPermissions.map(::JsonPrimitive)))
                        put("platform_status", JsonObject(missingPermissions.associateWith { JsonPrimitive(permissionBroker.platformStatus(it)) }))
                    }
                )
            }
        }

        val contentValues = jsonObjectToContentValues(valuesElement)
        return try {
            platformDispatch?.let { return it("insert", arguments) }
            com.droidance.core.device.agent.device.RuntimeContentProviders.acquire(requireNotNull(uri.authority))
            val insertedUri = context.contentResolver.insert(uri, contentValues)
                ?: return ToolProtocolResult.failed(
                    code = "CONTENT_INSERT_FAILED",
                    retryable = true,
                    details = buildJsonObject {
                        put("uri", JsonPrimitive(rawUri.take(300)))
                        put("reason", JsonPrimitive("provider_returned_null_uri"))
                    }
                )
            val payload = buildJsonObject {
                put("uri", JsonPrimitive(rawUri))
                put("inserted_uri", JsonPrimitive(insertedUri.toString()))
                put("status", JsonPrimitive("inserted"))
            }
            ToolProtocolResult.success("[ANDROID_CONTENT_RESULT]\n$payload").copy(
                data = payload,
                preview = "Inserted: $rawUri"
            )
        } catch (e: SecurityException) {
            ToolProtocolResult.failed("PERMISSION_MISSING", retryable = true, error = e)
        } catch (e: IllegalArgumentException) {
            ToolProtocolResult.failed("CONTENT_INSERT_INVALID", retryable = true, error = e)
        } catch (e: Exception) {
            ToolProtocolResult.failed("CONTENT_INSERT_FAILED", retryable = true, error = e)
        }
    }

    suspend fun update(arguments: String): ToolExecutionResult {
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val rawUri = input["uri"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (rawUri.isBlank() || uri?.scheme != "content") {
            return ToolProtocolResult.notExecuted(
                code = "CONTENT_URI_REQUIRED",
                retryable = true,
                details = buildJsonObject { put("required_scheme", JsonPrimitive("content")) }
            )
        }
        val valuesElement = input["values"] as? JsonObject
            ?: return ToolProtocolResult.missingArgument("values")

        val requiredPermissions = AndroidContentPermissionResolver.requiredPermissions(uri, write = true)
        val missingPermissions = permissionBroker.missingPermissions(requiredPermissions)
        if (missingPermissions.isNotEmpty()) {
            val outcome = permissionBroker.request(
                permissions = missingPermissions,
                turnId = permissionOwner().first,
                callId = permissionOwner().second,
                reason = permissionReason(rawUri.take(160)),
                timeoutMillis = permissionTimeoutMillis(),
                onWaiting = onPermissionWaiting,
                onResolved = onPermissionResolved
            )
            if (outcome != PermissionRequestOutcome.GRANTED) {
                return ToolProtocolResult.notExecuted(
                    code = "PERMISSION_${outcome.name}",
                    retryable = outcome == PermissionRequestOutcome.TIMED_OUT,
                    details = buildJsonObject {
                        put("uri", JsonPrimitive(rawUri.take(300)))
                        put("permissions", JsonArray(missingPermissions.map(::JsonPrimitive)))
                        put("platform_status", JsonObject(missingPermissions.associateWith { JsonPrimitive(permissionBroker.platformStatus(it)) }))
                    }
                )
            }
        }

        val contentValues = jsonObjectToContentValues(valuesElement)
        val selection = input["selection"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val selectionArgs = input["selection_args"]?.let { element ->
            stringArray(element, "selection_args") ?: return invalidArray("selection_args")
        }

        return try {
            platformDispatch?.let { return it("update", arguments) }
            com.droidance.core.device.agent.device.RuntimeContentProviders.acquire(requireNotNull(uri.authority))
            val rows = context.contentResolver.update(
                uri, contentValues, selection, selectionArgs?.toTypedArray()
            )
            val payload = buildJsonObject {
                put("uri", JsonPrimitive(rawUri))
                put("rows_updated", JsonPrimitive(rows))
                put("status", JsonPrimitive("updated"))
            }
            ToolProtocolResult.success("[ANDROID_CONTENT_RESULT]\n$payload").copy(
                data = payload,
                preview = "Updated $rows rows in $rawUri"
            )
        } catch (e: SecurityException) {
            ToolProtocolResult.failed("PERMISSION_MISSING", retryable = true, error = e)
        } catch (e: IllegalArgumentException) {
            ToolProtocolResult.failed("CONTENT_UPDATE_INVALID", retryable = true, error = e)
        } catch (e: Exception) {
            ToolProtocolResult.failed("CONTENT_UPDATE_FAILED", retryable = true, error = e)
        }
    }

    suspend fun delete(arguments: String): ToolExecutionResult {
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val rawUri = input["uri"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (rawUri.isBlank() || uri?.scheme != "content") {
            return ToolProtocolResult.notExecuted(
                code = "CONTENT_URI_REQUIRED",
                retryable = true,
                details = buildJsonObject { put("required_scheme", JsonPrimitive("content")) }
            )
        }

        val requiredPermissions = AndroidContentPermissionResolver.requiredPermissions(uri, write = true)
        val missingPermissions = permissionBroker.missingPermissions(requiredPermissions)
        if (missingPermissions.isNotEmpty()) {
            val outcome = permissionBroker.request(
                permissions = missingPermissions,
                turnId = permissionOwner().first,
                callId = permissionOwner().second,
                reason = permissionReason(rawUri.take(160)),
                timeoutMillis = permissionTimeoutMillis(),
                onWaiting = onPermissionWaiting,
                onResolved = onPermissionResolved
            )
            if (outcome != PermissionRequestOutcome.GRANTED) {
                return ToolProtocolResult.notExecuted(
                    code = "PERMISSION_${outcome.name}",
                    retryable = outcome == PermissionRequestOutcome.TIMED_OUT,
                    details = buildJsonObject {
                        put("uri", JsonPrimitive(rawUri.take(300)))
                        put("permissions", JsonArray(missingPermissions.map(::JsonPrimitive)))
                        put("platform_status", JsonObject(missingPermissions.associateWith { JsonPrimitive(permissionBroker.platformStatus(it)) }))
                    }
                )
            }
        }

        val selection = input["selection"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val selectionArgs = input["selection_args"]?.let { element ->
            stringArray(element, "selection_args") ?: return invalidArray("selection_args")
        }

        return try {
            platformDispatch?.let { return it("delete", arguments) }
            com.droidance.core.device.agent.device.RuntimeContentProviders.acquire(requireNotNull(uri.authority))
            val rows = context.contentResolver.delete(uri, selection, selectionArgs?.toTypedArray())
            val payload = buildJsonObject {
                put("uri", JsonPrimitive(rawUri))
                put("rows_deleted", JsonPrimitive(rows))
                put("status", JsonPrimitive("deleted"))
            }
            ToolProtocolResult.success("[ANDROID_CONTENT_RESULT]\n$payload").copy(
                data = payload,
                preview = "Deleted $rows rows from $rawUri"
            )
        } catch (e: SecurityException) {
            ToolProtocolResult.failed("PERMISSION_MISSING", retryable = true, error = e)
        } catch (e: IllegalArgumentException) {
            ToolProtocolResult.failed("CONTENT_DELETE_INVALID", retryable = true, error = e)
        } catch (e: Exception) {
            ToolProtocolResult.failed("CONTENT_DELETE_FAILED", retryable = true, error = e)
        }
    }

    suspend fun applyBatch(arguments: String): ToolExecutionResult {
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val authority = input["authority"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (authority.isBlank()) {
            return ToolProtocolResult.missingArgument("authority")
        }
        val operationsElement = input["operations"] as? JsonArray
            ?: return ToolProtocolResult.missingArgument("operations")

        val dummyUri = Uri.parse("content://$authority")
        val requiredPermissions = AndroidContentPermissionResolver.requiredPermissions(dummyUri, write = true)
        val missingPermissions = permissionBroker.missingPermissions(requiredPermissions)
        if (missingPermissions.isNotEmpty()) {
            val outcome = permissionBroker.request(
                permissions = missingPermissions,
                turnId = permissionOwner().first,
                callId = permissionOwner().second,
                reason = permissionReason(authority.take(160)),
                timeoutMillis = permissionTimeoutMillis(),
                onWaiting = onPermissionWaiting,
                onResolved = onPermissionResolved
            )
            if (outcome != PermissionRequestOutcome.GRANTED) {
                return ToolProtocolResult.notExecuted(
                    code = "PERMISSION_${outcome.name}",
                    retryable = outcome == PermissionRequestOutcome.TIMED_OUT,
                    details = buildJsonObject {
                        put("authority", JsonPrimitive(authority))
                        put("permissions", JsonArray(missingPermissions.map(::JsonPrimitive)))
                        put("platform_status", JsonObject(missingPermissions.associateWith { JsonPrimitive(permissionBroker.platformStatus(it)) }))
                    }
                )
            }
        }

        val ops = ArrayList<ContentProviderOperation>()
        for (elem in operationsElement) {
            val opObj = (elem as? JsonObject)
                ?: return ToolProtocolResult.invalidArgument("operations", "elements_must_be_objects")
            val opType = opObj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "insert"
            val opUri = opObj["uri"]?.jsonPrimitive?.contentOrNull?.let { Uri.parse(it) }
                ?: return ToolProtocolResult.missingArgument("uri")
            val builder = when (opType) {
                "insert" -> {
                    val b = ContentProviderOperation.newInsert(opUri)
                    (opObj["values"] as? JsonObject)?.let { b.withValues(jsonObjectToContentValues(it)) }
                    b
                }
                "update" -> {
                    val b = ContentProviderOperation.newUpdate(opUri)
                    (opObj["values"] as? JsonObject)?.let { b.withValues(jsonObjectToContentValues(it)) }
                    opObj["selection"]?.jsonPrimitive?.contentOrNull?.let { sel ->
                        val selArgs = stringArray(opObj["selection_args"], "selection_args")
                        b.withSelection(sel, selArgs?.toTypedArray())
                    }
                    b
                }
                "delete" -> {
                    val b = ContentProviderOperation.newDelete(opUri)
                    opObj["selection"]?.jsonPrimitive?.contentOrNull?.let { sel ->
                        val selArgs = stringArray(opObj["selection_args"], "selection_args")
                        b.withSelection(sel, selArgs?.toTypedArray())
                    }
                    b
                }
                else -> return ToolProtocolResult.invalidArgument("type", "unsupported_batch_op_type: $opType")
            }
            opObj["yield_allowed"]?.jsonPrimitive?.booleanOrNull?.let { builder.withYieldAllowed(it) }
            ops.add(builder.build())
        }

        return try {
            platformDispatch?.let { return it("batch", arguments) }
            com.droidance.core.device.agent.device.RuntimeContentProviders.acquire(authority)
            val results = context.contentResolver.applyBatch(authority, ops)
            val payload = buildJsonObject {
                put("authority", JsonPrimitive(authority))
                put("operations_count", JsonPrimitive(ops.size))
                put("results_count", JsonPrimitive(results.size))
                put("status", JsonPrimitive("batch_applied"))
            }
            ToolProtocolResult.success("[ANDROID_CONTENT_RESULT]\n$payload").copy(
                data = payload,
                preview = "Batch applied: ${ops.size} operations"
            )
        } catch (e: SecurityException) {
            ToolProtocolResult.failed("PERMISSION_MISSING", retryable = true, error = e)
        } catch (e: Exception) {
            ToolProtocolResult.failed("CONTENT_BATCH_FAILED", retryable = true, error = e)
        }
    }

    suspend fun mutate(arguments: String): ToolExecutionResult {
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val action = input["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolProtocolResult.missingArgument("action")
        return when (action) {
            "insert" -> insert(arguments)
            "update" -> update(arguments)
            "delete" -> delete(arguments)
            "batch", "apply_batch" -> applyBatch(arguments)
            else -> ToolProtocolResult.invalidArgument(
                "action", "must be 'insert', 'update', 'delete', or 'batch'", action
            )
        }
    }

    private fun formatResult(uri: String, cursor: Cursor, limit: Int, offset: Int): ToolExecutionResult {
        val columns = cursor.columnNames.toList()
        var rowCount = 0
        if (offset > 0 && !cursor.isClosed) {
            cursor.moveToPosition(offset - 1)
        }
        val rows = buildJsonArray {
            while (rowCount < limit && cursor.moveToNext()) {
                add(buildJsonObject {
                    columns.forEachIndexed { index, column ->
                        put(column, cursorValue(cursor, index))
                    }
                })
                rowCount++
            }
        }
        val hasMore = !cursor.isAfterLast
        val truncated = (rows.size == limit && hasMore) || offset > 0
        val payload = buildJsonObject {
            put("uri", JsonPrimitive(uri))
            put("columns", JsonArray(columns.map(::JsonPrimitive)))
            put("rows", rows)
            put("count", JsonPrimitive(rows.size))
            put("offset", JsonPrimitive(offset))
            put("has_more", JsonPrimitive(hasMore))
            put("truncated", JsonPrimitive(truncated))
        }
        return ToolProtocolResult.success("[ANDROID_CONTENT_RESULT]\n$payload").copy(
            data = payload,
            schema = "android_content_query_result_v1",
            coverage = mapOf(
                "count" to rows.size.toString(),
                "offset" to offset.toString(),
                "has_more" to hasMore.toString(),
                "truncated" to truncated.toString()
            ),
            preview = "URI: $uri, count: ${rows.size}, truncated: $truncated, has_more: $hasMore"
        )
    }

    private fun cursorValue(cursor: Cursor, index: Int): JsonElement = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(cursor.getDouble(index))
        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive("[blob ${cursor.getBlob(index)?.size ?: 0} bytes]")
        else -> JsonPrimitive(cursor.getString(index).orEmpty().take(MAX_FIELD_CHARS))
    }

    private fun jsonObjectToContentValues(values: JsonObject): ContentValues {
        val cv = ContentValues()
        for ((k, v) in values) {
            if (v == JsonNull) {
                cv.putNull(k)
                continue
            }
            val prim = v as? JsonPrimitive ?: continue
            if (prim.isString) {
                cv.put(k, prim.content)
            } else {
                val boolVal = prim.booleanOrNull
                val longVal = prim.longOrNull
                val doubleVal = prim.doubleOrNull
                when {
                    boolVal != null -> cv.put(k, boolVal)
                    longVal != null -> cv.put(k, longVal)
                    doubleVal != null -> cv.put(k, doubleVal)
                    else -> cv.put(k, prim.content)
                }
            }
        }
        return cv
    }

    private fun stringArray(element: JsonElement?, name: String): List<String>? {
        if (element == null || element is JsonNull) return emptyList()
        return runCatching {
            element.jsonArray.map { item ->
                item.jsonPrimitive.contentOrNull
                    ?: throw IllegalArgumentException("$name must contain strings")
            }
        }.getOrNull()
    }

    private fun invalidArray(name: String): ToolExecutionResult =
        ToolProtocolResult.notExecuted(
            code = "ARGUMENT_SCHEMA_MISMATCH",
            retryable = true,
            details = buildJsonObject {
                put("parameter", JsonPrimitive(name))
                put("expected_type", JsonPrimitive("array<string>"))
            }
        )

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_FIELD_CHARS = 4_000
    }
}
