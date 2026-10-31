// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import com.droidance.config.MarkdownTemplateRenderer
import com.droidance.config.RuleConfigManager
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal fun takeoverProgressFacts(watch: AppTakeoverWatch): JsonObject = buildJsonObject {
    put("package_name", watch.packageName)
    put("enabled", watch.enabled)
    put("checkpoint_saved", watch.checkpoint.isNotBlank())
    put("checkpoint_chars", watch.checkpoint.length)
    put("last_saved_summary", watch.lastSummary)
    put("next_check_at_ms", watch.nextCheckAtMs?.let(::JsonPrimitive) ?: JsonNull)
    put("terminal_reason", watch.terminalReason?.name?.let(::JsonPrimitive) ?: JsonNull)
}

internal fun savedTakeoverProgress(watch: AppTakeoverWatch): String =
    watch.checkpoint.takeIf(String::isNotBlank) ?: watch.lastSummary

@Singleton
class AppTakeoverPromptBuilder @Inject constructor(
    private val ruleConfigManager: RuleConfigManager
) {
    fun run(claim: ClaimedAppTakeoverRun, targetAlreadyVisible: Boolean): String = MarkdownTemplateRenderer.render(
        ruleConfigManager.readMarkdownSection(RULE_FILE, "app_takeover_run_prompt"),
        mapOf(
            "package_name" to claim.watch.packageName,
            "instruction" to claim.watch.instruction,
            "signals" to formatSignals(claim.signals),
            "display_state" to if (targetAlreadyVisible) {
                "target_already_visible"
            } else {
                "target_not_confirmed"
            },
            "checkpoint" to claim.watch.checkpoint.ifBlank { "none" },
            "previous_summary" to claim.watch.lastSummary.ifBlank { "none" },
            "started_at" to Instant.ofEpochMilli(claim.watch.taskStartedAtMs).toString(),
            "deadline_at" to (claim.watch.deadlineAtMs?.let { Instant.ofEpochMilli(it).toString() } ?: "none"),
            "current_time" to Instant.now().toString()
        )
    )

    fun failureNotice(): String = ruleConfigManager.publicFallback("app_takeover_start_failed")

    fun roundContext(watch: AppTakeoverWatch): String = buildJsonObject {
        val nowMs = System.currentTimeMillis()
        put("persistent_task_clock", buildJsonObject {
            put("current_time", Instant.ofEpochMilli(nowMs).toString())
            put("started_at", Instant.ofEpochMilli(watch.taskStartedAtMs).toString())
            watch.deadlineAtMs?.let {
                put("deadline_at", Instant.ofEpochMilli(it).toString())
                put("remaining_ms", (it - nowMs).coerceAtLeast(0L))
            }
        })
        put("untrusted_wake_signals", Json.encodeToJsonElement(watch.pendingSignals.takeLast(8).map {
            it.copy(title = it.title.take(160), content = it.content.take(240))
        }))
        put("persistent_task_state", takeoverProgressFacts(watch))
        put("continuation_contract", ruleConfigManager.readMarkdownSection(RULE_FILE, "app_takeover_continuation_contract"))
    }.toString()

    fun expired(watch: AppTakeoverWatch): String = buildString {
        append(ruleConfigManager.publicFallback("app_takeover_deadline_reached"))
        val progress = savedTakeoverProgress(watch)
        if (progress.isNotBlank()) {
            append("\n\n")
            append(ruleConfigManager.publicFallback("app_takeover_last_saved_progress"))
            append("\n")
            append(progress)
        }
    }

    fun failure(packageName: String, error: String): String = MarkdownTemplateRenderer.render(
        ruleConfigManager.readMarkdownSection(RULE_FILE, "app_takeover_failure_message"),
        mapOf(
            "package_name" to packageName,
            "error" to error
        )
    )

    fun unchanged(packageName: String): String = MarkdownTemplateRenderer.render(
        ruleConfigManager.readMarkdownSection(RULE_FILE, "app_takeover_unchanged_message"),
        mapOf("package_name" to packageName)
    )

    private fun formatSignals(signals: List<AppTakeoverSignal>): String =
        signals.joinToString("\n") { signal ->
            buildString {
                append("- ")
                append(signal.type.name.lowercase())
                signal.notificationKey?.let { append(" key=").append(it) }
                if (signal.title.isNotBlank()) append(" title=").append(signal.title.take(160))
                if (signal.content.isNotBlank()) append(" preview=").append(signal.content.take(240))
            }
        }.ifBlank { "- scheduled check" }

    private companion object {
        const val RULE_FILE = "automation_prompts.md"
    }
}
