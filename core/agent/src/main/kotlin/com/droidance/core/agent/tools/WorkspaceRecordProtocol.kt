// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.*

/** Model-authored object records. Validates shape and provenance, never field meaning. */
object WorkspaceRecordProtocol {
    val parameter = ToolParameter("array", "For records: objects with stable record_id and source-bound fields. Exact-ID updates merge supplied fields and preserve omitted fields; no semantic deduplication.", false,
        items = ToolParameter("object", "", properties = linkedMapOf(
            "record_id" to ToolParameter("string", "Stable object identity chosen by the Agent.", minLength = 1),
            "fields" to ToolParameter("object", "Object keyed by field name; each value is {value, source_ref, region?}.", additionalPropertySchema = ToolParameter("object", "", properties = linkedMapOf(
                "value" to ToolParameter("string", "Exact observed value; preserve units and qualifications."),
                "source_ref" to ToolParameter("string", "Original source reference supporting this field.", minLength = 1),
                "region" to ToolParameter("string", "Optional card/row/region locator in that source.", false)
            ), additionalProperties = false)),
            "assessment" to ToolParameter("string", "Optional model selection or interpretation; separate from observed field values and not independently verified.", false),
            "uncertainties" to ToolParameter("array", "Current missing or uncertain fields. Omit to retain prior uncertainties; supply [] to clear them. Never borrow from an adjacent object.", false,
                items = ToolParameter("string", ""))
        ), additionalProperties = false), requiredWhen = mapOf("op" to listOf("records")),
        allowedWhen = mapOf("op" to listOf("records")))

    fun merge(existing: JsonObject?, incoming: JsonArray, sourceExists: (String) -> Boolean): JsonObject {
        require(incoming.isNotEmpty()) { "RECORDS_EMPTY" }
        require(existing == null || (existing.keys == setOf("records") && existing["records"] is JsonArray)) { "RECORD_FILE_SHAPE_INVALID" }
        val prior = existing?.get("records") as? JsonArray ?: JsonArray(emptyList())
        val records = linkedMapOf<String, JsonObject>()
        fun validate(value: JsonElement, path: String): Pair<String, JsonObject> {
            fun fail(code: String, at: String = path): Nothing = throw RecordValidationException(code, at)
            val record = value as? JsonObject ?: fail("RECORD_OBJECT_REQUIRED")
            record.keys.firstOrNull { it !in setOf("record_id", "fields", "assessment", "uncertainties") }
                ?.let { fail("RECORD_UNKNOWN_FIELD", "$path.$it") }
            if (record["record_id"] == null) fail("RECORD_ID_REQUIRED", "$path.record_id")
            val id = string(record["record_id"], "$path.record_id")
            if (id.isBlank()) fail("RECORD_ID_REQUIRED", "$path.record_id")
            val fields = record["fields"] as? JsonObject ?: fail("RECORD_FIELDS_REQUIRED", "$path.fields")
            if (fields.isEmpty() || fields.keys.any(String::isBlank)) fail("RECORD_FIELDS_EMPTY", "$path.fields")
            fields.forEach { (name, value) ->
                val fieldPath = "$path.fields[${JsonPrimitive(name)}]"
                val field = value as? JsonObject ?: fail("RECORD_FIELD_OBJECT_REQUIRED", fieldPath)
                field.keys.firstOrNull { it !in setOf("value", "source_ref", "region") }
                    ?.let { fail("RECORD_FIELD_UNKNOWN_PROPERTY", "$fieldPath.$it") }
                string(field["value"], "$fieldPath.value")
                val source = string(field["source_ref"], "$fieldPath.source_ref")
                if (source.isBlank() || !sourceExists(source)) fail("RECORD_SOURCE_UNAVAILABLE", "$fieldPath.source_ref")
                field["region"]?.let { string(it, "$fieldPath.region") }
            }
            record["assessment"]?.let { string(it, "$path.assessment") }
            record["uncertainties"]?.let { value ->
                val items = value as? JsonArray ?: fail("RECORD_UNCERTAINTIES_ARRAY_REQUIRED", "$path.uncertainties")
                items.forEachIndexed { index, item -> string(item, "$path.uncertainties[$index]") }
            }
            return id to record
        }
        prior.forEachIndexed { index, item ->
            val (id, record) = validate(item, "existing.records[$index]")
            if (records.put(id, record) != null) throw RecordValidationException("RECORD_DUPLICATE_ID", "existing.records[$index].record_id")
        }
        val incomingIds = mutableSetOf<String>()
        incoming.forEachIndexed { index, item ->
            val (id, record) = validate(item, "records[$index]")
            if (!incomingIds.add(id)) throw RecordValidationException("RECORD_DUPLICATE_ID", "records[$index].record_id")
            records[id] = mergeRecord(records[id], record)
        }
        return buildJsonObject { put("records", JsonArray(records.values.toList())) }
    }

    /** Merge already-validated updates by exact identity; each supplied field replaces its whole source/value pair. */
    internal fun mergeRecord(previous: JsonObject?, incoming: JsonObject): JsonObject {
        require(previous == null || previous["record_id"] == incoming["record_id"]) { "RECORD_ID_MISMATCH" }
        return JsonObject(previous.orEmpty() + incoming + mapOf(
            "fields" to JsonObject((previous?.get("fields") as? JsonObject).orEmpty() + incoming.getValue("fields").jsonObject)
        ))
    }

    class RecordValidationException(val code: String, val path: String) : IllegalArgumentException(code)

    private fun string(value: JsonElement?, path: String): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw RecordValidationException("RECORD_STRING_REQUIRED", path)
}
