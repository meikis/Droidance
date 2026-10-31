// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An activated tool loop must select an action or its existing completion tool. */
class ToolResponseRequirement : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolResponseRequirement> {
        fun apply(request: JsonObject, required: Boolean): JsonObject =
            if (required && (request["tools"] as? JsonArray)?.isNotEmpty() == true)
                JsonObject(request + ("tool_choice" to JsonPrimitive("required")))
            else request
    }
}
