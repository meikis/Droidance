// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.automation.AppTakeoverStore
import com.droidance.automation.AppTakeoverWatch
import com.droidance.automation.AppTakeoverTaskPolicy
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.device.config.InstalledAppsManager
import com.droidance.config.RuleConfigManager
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationToolRegistrar(
    private val registerTool: (Tool) -> Unit,
    private val adbShell: AdbShellExecutor,
    private val installedAppsManager: InstalledAppsManager,
    private val appTakeoverStore: AppTakeoverStore,
    private val ruleConfigManager: RuleConfigManager,
    private val executionContextStore: ExecutionContextStore,
    private val requestTime: suspend () -> Long,
    private val requestTurnId: () -> String,
    private val requestModelSelection: () -> com.droidance.automation.AppTakeoverModelSelection
) {
    fun registerAll() {
        registerReadNotifications()
        registerClickNotification()
        registerDismissNotification()
        registerManageAppTakeover()
    }

    private fun registerReadNotifications() {
        registerTool(object : Tool {
            override val name = "read_notifications"
            override val definition = ToolDefinition(
                "read_notifications",
                "Read current notifications with an optional app filter and return their notification keys.",
                mapOf("app" to ToolParameter("string", "Optional exact package or app-label filter", false))
            )

            override suspend fun execute(arguments: String): ToolExecutionResult {
                val filterApp = arguments.jsonString("app")
                val watcher = com.droidance.automation.NotificationWatcher.instance
                if (watcher != null) return ToolProtocolResult.success(formatWatcherNotifications(watcher, filterApp))
                if (!adbShell.isAvailable()) {
                    return ToolProtocolResult.notExecuted(
                        code = "NOTIFICATION_ACCESS_UNAVAILABLE",
                        retryable = false
                    )
                }
                val result = adbShell.exec("dumpsys notification --noredact 2>/dev/null | grep -A 3 'android.title\\|android.text' | head -60", 10_000)
                return if (result.exitCode == 0) ToolProtocolResult.success(result.stdout.ifBlank { "No notifications" })
                else ToolProtocolResult.failed("NOTIFICATION_READ_FAILED", true)
            }
        })
    }

    private fun formatWatcherNotifications(
        watcher: com.droidance.automation.NotificationWatcher,
        filterApp: String?
    ): String {
        val all = watcher.getAllNotifications()
        val filtered = if (filterApp.isNullOrBlank()) all else all.filter {
            it.packageName.contains(filterApp, ignoreCase = true) ||
                it.appName.contains(filterApp, ignoreCase = true)
        }
        if (filtered.isEmpty()) return if (filterApp != null) "No notifications from \"$filterApp\"" else "No notifications"
        return filtered.joinToString("\n\n") { notification ->
            "📬 [${notification.appName}] ${notification.title}\n" +
                "   Content: ${notification.content}\n" +
                "   Time: ${CLOCK.format(Date(notification.timestamp))}\n" +
                "   key: ${notification.key}"
        }
    }

    private fun registerClickNotification() {
        registerTool(object : Tool {
            override val name = "click_notification"
            override val definition = ToolDefinition(
                "click_notification",
                "Click a notification by its notification key.",
                mapOf("key" to ToolParameter("string", "Notification key returned by read_notifications", true))
            )

            override suspend fun execute(arguments: String): ToolExecutionResult {
                val key = arguments.jsonString("key") ?: return ToolProtocolResult.missingArgument("key")
                val watcher = com.droidance.automation.NotificationWatcher.instance
                    ?: return ToolProtocolResult.notExecuted(
                        code = "NOTIFICATION_LISTENER_UNAVAILABLE",
                        retryable = false
                    )
                return if (watcher.clickNotification(key, executionContextStore.current()?.displayId)) {
                    ToolProtocolResult.accepted("Notification clicked; opening the target page")
                } else {
                    ToolProtocolResult.failed(
                        code = "NOTIFICATION_NOT_FOUND",
                        retryable = true,
                        details = buildJsonObject { put("key", JsonPrimitive(key.take(300))) }
                    )
                }
            }
        })
    }

    private fun registerDismissNotification() {
        registerTool(object : Tool {
            override val name = "dismiss_notification"
            override val definition = ToolDefinition(
                "dismiss_notification",
                "Dismiss a notification by key.",
                mapOf("key" to ToolParameter("string", "Notification key", true))
            )

            override suspend fun execute(arguments: String): ToolExecutionResult {
                val key = arguments.jsonString("key") ?: return ToolProtocolResult.missingArgument("key")
                val watcher = com.droidance.automation.NotificationWatcher.instance
                    ?: return ToolProtocolResult.notExecuted(
                        code = "NOTIFICATION_LISTENER_UNAVAILABLE",
                        retryable = false
                    )
                watcher.dismissNotification(key)
                return ToolProtocolResult.accepted("Notification dismissed")
            }
        })
    }

    private fun registerManageAppTakeover() {
        registerTool(object : Tool {
            override val name = "manage_app_takeover"
            override val definition = ToolDefinition(
                name,
                ruleConfigManager.readMarkdownSection("automation_tools.md", "app_takeover_description"),
                mapOf(
                    "action" to ToolParameter(
                        "string",
                        "Action to perform.",
                        true,
                        enumValues = listOf("start", "update", "pause", "resume", "stop", "list")
                    ),
                    "apps" to ToolParameter(
                        "array",
                        "App labels or package names. Required except for list; omit on stop to stop all.",
                        false,
                        items = ToolParameter("string", "App label or package name.", false)
                    ),
                    "instruction" to ToolParameter(
                        "string",
                        "Persistent user instruction, stored verbatim. Required for start; optional for update.",
                        false
                    ),
                    "poll_interval_seconds" to ToolParameter(
                        "integer",
                        "Optional per-app polling interval in seconds. The configured default applies to start when omitted.",
                        false
                    ),
                    "duration_seconds" to ToolParameter("integer", "Entire goal duration after the user's message, including waits and all runs; positive. Mutually exclusive with deadline_at.", false),
                    "deadline_at" to ToolParameter("string", "Entire goal deadline, ISO-8601 with explicit UTC offset. Preserved across runs, pauses and restarts.", false),
                    "checkpoint" to ToolParameter("string", "Update only: save useful multi-object task state during a run; up to 16000 characters. Not proof of completion.", false),
                    "success_summary" to ToolParameter(
                        "string",
                        "Truthful user-facing summary emitted after a state-changing takeover action succeeds; ignored for list.",
                        false, requiredWhen = mapOf("action" to listOf("start", "update", "pause", "resume", "stop"))
                    )
                )
            )

            override suspend fun execute(arguments: String): ToolExecutionResult {
                val document = runCatching { Json.parseToJsonElement(arguments).jsonObject }
                    .getOrElse { return ToolProtocolResult.invalidArguments() }
                val action = document["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                    ?: return ToolProtocolResult.missingArgument("action")
                val appInputs = runCatching {
                    document["apps"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                        .orEmpty()
                }.getOrElse { return ToolProtocolResult.invalidArgument("apps", "expected_string_array") }
                val instruction = document["instruction"]?.jsonPrimitive?.contentOrNull?.trim()
                val successSummary = document["success_summary"]?.jsonPrimitive?.contentOrNull?.trim()
                val intervalResult = parsePollInterval(
                    document["poll_interval_seconds"]?.jsonPrimitive?.contentOrNull
                )
                if (intervalResult.isFailure) {
                    return ToolProtocolResult.invalidArgument(
                        "poll_interval_seconds",
                        "invalid_or_out_of_configured_range"
                    )
                }
                val pollIntervalMs = intervalResult.getOrNull()
                val durationSeconds = (document["duration_seconds"] as? JsonPrimitive)
                    ?.takeUnless { it.isString }?.longOrNull
                if ("duration_seconds" in document && durationSeconds == null) {
                    return ToolProtocolResult.invalidArgument("duration_seconds", "expected_positive_integer")
                }
                val deadlineText = (document["deadline_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if ("deadline_at" in document && deadlineText == null) {
                    return ToolProtocolResult.invalidArgument("deadline_at", "expected_offset_datetime")
                }
                val checkpoint = (document["checkpoint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if ("checkpoint" in document && (checkpoint == null || checkpoint.length > AppTakeoverTaskPolicy.MAX_CHECKPOINT_CHARS || action != "update")) {
                    return ToolProtocolResult.invalidArgument("checkpoint", "invalid_task_state_update")
                }
                if ((durationSeconds != null || deadlineText != null) && action !in setOf("start", "update")) {
                    return ToolProtocolResult.invalidArgument("deadline_at", "deadline_only_for_start_or_update")
                }
                val requestAtMs = if (action == "start" || durationSeconds != null) requestTime() else System.currentTimeMillis()
                val deadlineAtMs = runCatching { AppTakeoverTaskPolicy.deadline(durationSeconds, deadlineText, requestAtMs) }
                    .getOrElse { return ToolProtocolResult.invalidArgument("deadline_at", it.message.orEmpty()) }
                if (deadlineAtMs != null && deadlineAtMs <= System.currentTimeMillis()) {
                    return ToolProtocolResult.invalidArgument("deadline_at", "deadline_already_elapsed")
                }

                if (action == "list") return ToolProtocolResult.success("[APP_TAKEOVER_OK] ${formatWatches(appTakeoverStore.list())}")
                if (action !in MANAGE_ACTIONS) {
                    return ToolProtocolResult.invalidArgument("action", "unsupported_value", action)
                }
                if (successSummary.isNullOrBlank()) {
                    return ToolProtocolResult.missingArgument("success_summary")
                }
                if (action == "start" && instruction.isNullOrBlank()) {
                    return ToolProtocolResult.missingArgument("instruction")
                }
                if (appInputs.isEmpty() && action != "stop") {
                    return ToolProtocolResult.missingArgument("apps")
                }
                val packages = resolvePackagesAtomically(appInputs)
                    ?: return ToolProtocolResult.notExecuted(
                        code = "APP_TAKEOVER_TARGET_NOT_FOUND",
                        retryable = true,
                        details = buildJsonObject {
                            put("requested_apps", JsonArray(appInputs.map(::JsonPrimitive)))
                        }
                    )
                if (action != "start" && packages.isNotEmpty()) {
                    val configured = appTakeoverStore.list().map { it.packageName.lowercase() }.toSet()
                    if (packages.any { it.lowercase() !in configured }) {
                        return ToolProtocolResult.notExecuted(
                            code = "APP_TAKEOVER_NOT_CONFIGURED",
                            retryable = true,
                            details = buildJsonObject {
                                put("packages", JsonArray(packages.map(::JsonPrimitive)))
                            }
                        )
                    }
                }
                val thresholds = ruleConfigManager.harnessPolicy().runtimeThresholds
                val watches = when (action) {
                    "start" -> appTakeoverStore.start(
                        packageNames = packages,
                        instruction = checkNotNull(instruction),
                        pollIntervalMs = pollIntervalMs ?: thresholds.appTakeoverDefaultPollMs,
                        taskStartedAtMs = requestAtMs,
                        deadlineAtMs = deadlineAtMs,
                        originTurnId = requestTurnId(),
                        modelSelection = requestModelSelection()
                    )
                    "update" -> {
                        if (instruction.isNullOrBlank() && pollIntervalMs == null && deadlineAtMs == null && checkpoint == null) {
                            return ToolProtocolResult.notExecuted(
                                code = "ARGUMENT_SCHEMA_MISMATCH",
                                retryable = true,
                                details = buildJsonObject {
                                put("required_any", JsonArray(listOf("instruction", "poll_interval_seconds", "deadline_at", "duration_seconds", "checkpoint").map(::JsonPrimitive)))
                                }
                            )
                        }
                        appTakeoverStore.update(packages, instruction, pollIntervalMs,
                            deadlineAtMs, checkpoint, checkpointSummary = successSummary)
                    }
                    "pause" -> appTakeoverStore.setEnabled(packages, false)
                    "resume" -> {
                        if (packages.mapNotNull(appTakeoverStore::find).any {
                            it.terminalReason != null || AppTakeoverTaskPolicy.expired(it, System.currentTimeMillis())
                        }) return ToolProtocolResult.notExecuted("APP_TAKEOVER_FINISHED", false)
                        appTakeoverStore.setEnabled(packages, true)
                    }
                    "stop" -> if (appInputs.isEmpty()) appTakeoverStore.clear() else {
                        appTakeoverStore.remove(packages)
                    }
                    else -> return ToolProtocolResult.invalidArgument("action", "unsupported_value", action)
                }
                // Configuration is not execution. The model must decide whether the current
                // task is complete and call `done` itself; recording a terminal result here
                // made a one-time device task look successful after only changing a watch.
                return ToolProtocolResult.success("[APP_TAKEOVER_OK] $action completed. ${formatWatches(watches)}")
            }
        })
    }

    private suspend fun resolvePackage(app: String): String? =
        if (app.contains('.')) {
            app.takeIf { installedAppsManager.isSystemPackage(it) != null }
        } else {
            installedAppsManager.findPackageAwait(app)
        }

    private suspend fun resolvePackagesAtomically(apps: List<String>): List<String>? {
        val resolved = apps.map { resolvePackage(it) ?: return null }
        return resolved.distinct()
    }

    private fun parsePollInterval(raw: String?): Result<Long?> {
        if (raw == null) return Result.success(null)
        val seconds = raw.toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("'poll_interval_seconds' must be an integer"))
        val millis = runCatching { Math.multiplyExact(seconds, 1_000L) }
            .getOrElse { return Result.failure(IllegalArgumentException("Polling interval is too large")) }
        val thresholds = ruleConfigManager.harnessPolicy().runtimeThresholds
        if (millis !in thresholds.appTakeoverMinPollMs..thresholds.appTakeoverMaxPollMs) {
            return Result.failure(
                IllegalArgumentException(
                    "Polling interval must be between ${thresholds.appTakeoverMinPollMs / 1_000} and " +
                        "${thresholds.appTakeoverMaxPollMs / 1_000} seconds"
                )
            )
        }
        return Result.success(millis)
    }

    private fun formatWatches(watches: List<AppTakeoverWatch>): String =
        if (watches.isEmpty()) {
            "No app takeover is configured."
        } else {
            "Configured: " + watches.joinToString("; ") {
                "${it.packageName} (${if (it.enabled) "enabled" else "paused"}, " +
                    "${it.pollIntervalMs / 1_000}s, deadline_at=${it.deadlineAtMs?.let(java.time.Instant::ofEpochMilli)}, " +
                    "next_check_at_ms=${it.nextCheckAtMs}, terminal=${it.terminalReason}) -> \"${it.instruction}\""
            }
        }

    private fun String.jsonString(key: String): String? {
        return runCatching { Json.parseToJsonElement(this).jsonObject[key]?.jsonPrimitive?.content }.getOrNull()
    }

    private companion object {
        val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val MANAGE_ACTIONS = setOf("start", "update", "pause", "resume", "stop")
    }
}
