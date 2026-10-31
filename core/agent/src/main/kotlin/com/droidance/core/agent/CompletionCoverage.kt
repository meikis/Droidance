// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.*

/** Coverage references the single public body. Process checks need only their original evidence. */
object CompletionCoverage {
    const val ARGUMENT = "requirement_checks"
    data class Check(val requirement: String, val status: String, val evidenceRefs: List<String>,
        val unresolvedIndex: Int, val resultIndices: List<Int>)

    fun schema(description: String, evidenceRefsDescription: String) = ToolParameter("array", description, required = false,
        items = ToolParameter("object", "", properties = linkedMapOf(
            "requirement_id" to ToolParameter("string", ""),
            "result_indices" to ToolParameter("array", "", items = ToolParameter("integer", "")),
            "status" to ToolParameter("string", "", enumValues = listOf("supported", "incomplete", "unverified")),
            "evidence_refs" to ToolParameter("array",
                "Sources are derived from selected result_indices; omit this field there. Redundant references may only repeat those selected sources. For an empty result_indices process check, supply original sources here. $evidenceRefsDescription",
                required = false, items = ToolParameter("string", "Exact original source reference.", minLength = 1)),
            "unresolved_index" to ToolParameter("integer", "")
        ), additionalProperties = false))

    fun parse(value: JsonElement?, remainingCount: Int, claims: List<ActionControlProtocol.Claim>): List<Check>? {
        val array = value as? JsonArray ?: return null
        // Coverage is optional bookkeeping; public gaps need no second indexed representation.
        if (array.isEmpty()) return emptyList()
        val checks = array.map { element ->
            val row = element as? JsonObject ?: return null
            val requiredKeys = setOf("requirement_id", "status", "unresolved_index", "result_indices")
            if (!row.keys.containsAll(requiredKeys) || (row.keys - requiredKeys - "evidence_refs").isNotEmpty()) return null
            fun string(name: String) = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun index(name: String) = (row[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val requirement = string("requirement_id")?.takeIf(String::isNotBlank) ?: return null
            val status = string("status") ?: return null
            val unresolvedIndex = index("unresolved_index") ?: return null
            val resultIndices = (row["result_indices"] as? JsonArray)?.map { entry ->
                (entry as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    ?.takeIf { it >= 0 } ?: return null
            } ?: return null
            if (resultIndices.distinct().size != resultIndices.size) return null
            val refs = if (resultIndices.isNotEmpty()) {
                val selectedRefs = resultIndices.flatMap { index ->
                    (claims.getOrNull(index) ?: return null).evidenceRefs
                }.map(String::trim).distinct()
                if ("evidence_refs" in row) {
                    val supplied = runCatching { com.droidance.core.agent.harness.PublicResultEntry.references(row.getValue("evidence_refs")) }.getOrNull() ?: return null
                    if (supplied.any { it !in selectedRefs }) return null
                }
                // Derive coverage from the public entries; duplicate citations add no authority.
                selectedRefs
            } else {
                if ("evidence_refs" in row) runCatching { com.droidance.core.agent.harness.PublicResultEntry.references(row.getValue("evidence_refs")) }.getOrNull() ?: return null else emptyList()
            }
            when (status) {
                "supported" -> if (unresolvedIndex != -1) return null
                "incomplete", "unverified" -> if (unresolvedIndex !in 0 until remainingCount) return null
                else -> return null
            }
            Check(requirement, status, refs, unresolvedIndex, resultIndices)
        }
        if (checks.map { it.requirement }.distinct().size != checks.size) return null
        if (checks.filter { it.status != "supported" }.map { it.unresolvedIndex }.toSet() !=
            (0 until remainingCount).toSet()) return null
        return checks
    }

    fun suppliedReferencesValid(checks: List<Check>, isGrounded: (String) -> Boolean): Boolean =
        checks.filter { it.status == "supported" }.all {
            it.evidenceRefs.isEmpty() || (it.evidenceRefs.all(String::isNotBlank) && isGrounded(it.evidenceRefs.joinToString(",")))
        }

    /** Never scores wording similarity. Each result-bound check must retain its actual sources. */
    fun unboundResults(checks: List<Check>, claims: List<ActionControlProtocol.Claim>): List<Int> =
        checks.mapIndexedNotNull { index, check ->
            val selected = check.resultIndices.mapNotNull(claims::getOrNull)
            val references = selected.flatMap { it.evidenceRefs }
            index.takeIf { selected.size != check.resultIndices.size ||
                (selected.isNotEmpty() && check.status == "supported" && check.evidenceRefs.isNotEmpty() && check.evidenceRefs.any { ref ->
                    !ActionControlProtocol.referencesContain(references, ref)
                }) }
        }
}
