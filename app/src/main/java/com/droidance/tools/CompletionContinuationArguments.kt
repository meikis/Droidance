// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.automation.AppTakeoverTaskPolicy
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.*

/** Identical persistent state semantics for every public completion format. */
internal object CompletionContinuationArguments {
    val parameters = linkedMapOf(
        "checkpoint" to ToolParameter("string", "Persistent takeover state: goal, object identities, findings, pending dependencies, completed effects and next actions. Up to 16000 characters; retained whole.", false),
        "next_check_seconds" to ToolParameter("integer", "Seconds until the next serial Agent decision even on an unchanged screen; 0 continues immediately. Omit only when waiting for external changes.", false),
        "goal_complete" to ToolParameter("boolean", "True ends the entire persistent goal. Requires a successful completion and no next_check_seconds. Omission ends only this run.", false),
        "notify_user" to ToolParameter("boolean", "Notify for meaningful progress, human need or failure; keep empty checks silent.", false)
    )

    fun parse(json: JsonObject, status: String, summary: String): CompletionResult {
        val checkpoint = (json["checkpoint"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?: if ("checkpoint" in json) throw Invalid("checkpoint", "expected_string") else ""
        if (checkpoint.length > AppTakeoverTaskPolicy.MAX_CHECKPOINT_CHARS) throw Invalid("checkpoint", "state_too_large")
        val nextCheckSeconds = (json["next_check_seconds"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if ("next_check_seconds" in json && (nextCheckSeconds == null ||
                runCatching { AppTakeoverTaskPolicy.checkAt(System.currentTimeMillis(), nextCheckSeconds) }.isFailure)) {
            throw Invalid("next_check_seconds", "expected_nonnegative_seconds")
        }
        val goalComplete = json.strictBooleanOrNull("goal_complete")
            ?: if ("goal_complete" in json) throw Invalid("goal_complete", "expected_boolean") else false
        if (goalComplete && (status != "done" || nextCheckSeconds != null)) throw Invalid("goal_complete", "conflicting_continuation")
        val notifyUser = json.strictBooleanOrNull("notify_user")
            ?: if ("notify_user" in json) throw Invalid("notify_user", "expected_boolean") else null
        return CompletionResult(status, summary, checkpoint, nextCheckSeconds, goalComplete, notifyUser)
    }

    class Invalid(val field: String, val reason: String) : IllegalArgumentException("$field:$reason")
}
