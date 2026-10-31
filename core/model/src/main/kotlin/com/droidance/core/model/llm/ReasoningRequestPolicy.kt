// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ReasoningParameter
import kotlinx.serialization.json.*

object ReasoningRequestPolicy {
    /** Normalize the reasoning dialect and omit effort when using model defaults. */
    fun apply(
        request: JsonObject,
        effort: ReasoningEffort,
        parameter: ReasoningParameter,
        forceDefault: Boolean = false
    ): JsonObject = buildJsonObject {
        request.forEach { (key, value) ->
            if (key != "reasoning_effort" && key != "reasoning") put(key, value)
        }
        if (forceDefault) return@buildJsonObject
        val nested = request["reasoning"] as? JsonObject
        val nestedWithoutEffort = nested?.filterKeys { it != "effort" }.orEmpty()
        if (parameter == ReasoningParameter.REASONING_OBJECT) {
            if (nestedWithoutEffort.isNotEmpty() || effort.wireValue != null) putJsonObject("reasoning") {
                nestedWithoutEffort.forEach { (key, value) -> put(key, value) }
                effort.wireValue?.let { put("effort", it) }
            }
        } else {
            // Keep other explicitly configured reasoning fields, but never send two effort dialects.
            if (nestedWithoutEffort.isNotEmpty()) put("reasoning", JsonObject(nestedWithoutEffort))
            effort.wireValue?.let { put("reasoning_effort", it) }
        }
    }
}
