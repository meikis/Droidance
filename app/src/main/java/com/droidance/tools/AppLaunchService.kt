// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.LocalActionEvidence

import com.droidance.core.agent.event.ToolExecutionResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.droidance.agent.LocalActionVerifier
import com.droidance.core.agent.ToolResultStatus
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.agent.device.AppLaunchMode
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.config.DeepLinkKnowledgeStore
import com.droidance.core.device.config.InstalledAppsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppLaunchService(
    private val context: Context,
    private val adbShell: AdbShellExecutor,
    private val installedAppsManager: InstalledAppsManager,
    private val localActionVerifier: LocalActionVerifier,
    private val executionContextStore: ExecutionContextStore,
    private val deviceBackend: DeviceBackend,
    private val deepLinkKnowledgeStore: DeepLinkKnowledgeStore,
    private val appendLocalValidation: (ToolExecutionResult, LocalActionEvidence.ValidationResult?) -> ToolExecutionResult,
    private val isDeepLinkEnabled: () -> Boolean = { false }
) {
    private var openedExecutionId: String? = null
    private val openedPackages = mutableSetOf<String>()

    companion object {
        private const val LAUNCH_SAMPLE_INTERVAL_MS = 150L
        private const val LAUNCH_STABLE_SAMPLES = 3
        private const val LAUNCH_PIXEL_DELTA = 8
        private const val LAUNCH_CHANGED_FRACTION = 0.005
        private const val LAUNCH_HANDOFF_RENDER_MS = 1500L
        private const val LAUNCH_HANDOFF_LOCALIZED_MOTION = 0.15
        private val STANDARD_INTENT_SCHEMES = setOf(
            "http", "https", "content", "file", "tel", "sms", "smsto", "mailto", "geo", "package"
        )
    }
    suspend fun open(arguments: String): ToolExecutionResult = try {
        launchApp(arguments)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        toolFailure("OPEN_APP_FAILED", false, "message" to e.message.orEmpty().take(400))
    }

    private suspend fun launchApp(arguments: String): ToolExecutionResult {
        val json = Json.parseToJsonElement(arguments).jsonObject
        var packageName = json["package_name"]?.jsonPrimitive?.content
        val uri = json["uri"]?.jsonPrimitive?.content
        val activity = json["activity"]?.jsonPrimitive?.content
        val appName = json["app_name"]?.jsonPrimitive?.content
        val action = json["intent_action"]?.jsonPrimitive?.content
        val routeAction = json["route_action"]?.jsonPrimitive?.content
        val routeCategory = json["route_category"]?.jsonPrimitive?.content
        val routeParams = DeepLinkParams.parse(json["route_params"])
        val intentOptions = ExplicitIntentOptions.parse(json)
        val routeKeyword = json["route_keyword"]?.jsonPrimitive?.content ?: routeParams["keyword"] ?: routeParams["query"] ?: routeParams.values.singleOrNull()
        val requestedLaunchMode = json["launch_mode"]?.jsonPrimitive?.content?.let { raw ->
            AppLaunchMode.parse(raw) ?: return toolRejection(
                "ARGUMENT_SCHEMA_MISMATCH",
                true,
                "field" to "launch_mode",
                "expected" to "resume|reset_task|fresh",
                "received" to raw
            )
        }
        val launchMode = requestedLaunchMode ?: AppLaunchMode.RESUME
        val packageLaunchMode = { target: String? -> requestedLaunchMode ?: defaultPackageLaunchMode(target) }
        if (packageName.isNullOrBlank() && !appName.isNullOrBlank()) {
            val candidates = installedAppsManager.findCandidates(appName)
            if (candidates.size > 1) return ToolProtocolResult.notExecuted("APP_NAME_AMBIGUOUS", true,
                details = buildJsonObject {
                    put("app_name", JsonPrimitive(appName))
                    put("candidates", kotlinx.serialization.json.JsonArray(candidates.map {
                        buildJsonObject { put("name", JsonPrimitive(it.label)); put("package_name", JsonPrimitive(it.packageName)) }
                    }))
                })
            packageName = candidates.singleOrNull()?.packageName
        }
        if (packageName.isNullOrBlank() && !appName.isNullOrBlank()) {
            // Catalog names are hints, not installation proof. Only a unique exact alias
            // may supply a package; the platform still resolves and executes the launch.
            packageName = deepLinkKnowledgeStore.allRoutes().filter { route ->
                (listOf(route.appName) + route.appAliases).any { it.equals(appName.trim(), ignoreCase = true) }
            }.map { it.packageName }.distinct().singleOrNull()
        }
        if (packageName.isNullOrBlank() && !appName.isNullOrBlank() && !routeAction.isNullOrBlank()) {
            packageName = deepLinkKnowledgeStore.resolve(
                installedPackages = reliableInstalledPackages(packageName),
                appName = appName,
                category = routeCategory,
                action = routeAction,
                keyword = routeKeyword,
                params = routeParams,
                excludedRouteIds = deepLinkKnowledgeStore.unavailableRouteIds(executionContextStore.requireCurrent().backendSessionId)
            )?.route?.packageName
        }
        if (!routeAction.isNullOrBlank()) {
            if (!isDeepLinkEnabled()) {
                return toolRejection(
                    "DEEPLINK_DISABLED",
                    true,
                    "reason" to "Deep Link navigation is disabled in Advanced Settings. Open the app by name or package and use GUI navigation.",
                    "app" to (appName ?: packageName).orEmpty(),
                    "action" to routeAction
                )
            }
            val resolution = deepLinkKnowledgeStore.resolve(
                installedPackages = reliableInstalledPackages(packageName),
                appName = appName,
                packageName = packageName,
                category = routeCategory,
                action = routeAction,
                keyword = routeKeyword,
                params = routeParams,
                excludedRouteIds = deepLinkKnowledgeStore.unavailableRouteIds(executionContextStore.requireCurrent().backendSessionId)
            ) ?: return toolRejection(
                "DEEPLINK_ROUTE_NOT_FOUND",
                true,
                "app" to (appName ?: packageName).orEmpty(),
                "action" to routeAction
            )
            return launchKnowledgeRoute(resolution, launchMode)
        }
        if (packageName.isNullOrBlank() && !appName.isNullOrBlank()) {
            return toolRejection(
                "APP_NAME_UNRESOLVED", true,
                "app_name" to appName,
                "package_inventory_verified" to installedAppsManager.hasVerifiedPackageInventory().toString(),
                "reason" to "No package matched this name; installation absence is not established."
            )
        }
        if (uri?.startsWith("android.settings.") == true) {
            return toolRejection(
                "ARGUMENT_SCHEMA_MISMATCH",
                true,
                "issue" to "android_settings_value_requires_intent_action_field"
            )
        }
        if (action == Intent.ACTION_MAIN && uri.isNullOrBlank() && activity.isNullOrBlank() && intentOptions.isEmpty() && !packageName.isNullOrBlank()) {
            return launchPackage(packageName, packageLaunchMode(packageName))
        }
        if (!uri.isNullOrBlank()) {
            val scheme = runCatching { android.net.Uri.parse(uri).scheme?.lowercase() }.getOrNull()
            if (scheme != null && scheme !in STANDARD_INTENT_SCHEMES && !isDeepLinkEnabled()) {
                return toolRejection(
                    "DEEPLINK_DISABLED",
                    true,
                    "reason" to "Third-party Deep Link is disabled in Advanced Settings. Open the app by name or package and use GUI navigation.",
                    "uri" to uri
                )
            }
        }
        if (!uri.isNullOrBlank() || !action.isNullOrBlank() || !activity.isNullOrBlank()) {
            val effectiveActivity = if (action == Intent.ACTION_MAIN && activity.isNullOrBlank() && uri.isNullOrBlank() && !packageName.isNullOrBlank()) {
                AppEntryLanding.launcherComponent(context, packageName)
            } else {
                activity
            }
            return launchExplicitIntent(
                uri = uri,
                action = action,
                activity = effectiveActivity,
                packageName = packageName,
                launchMode = launchMode,
                options = intentOptions
            )
        }
        if (!intentOptions.isEmpty()) {
            return toolRejection(
                "ARGUMENT_SCHEMA_MISMATCH",
                true,
                "issue" to "intent_options_require_target"
            )
        }
        return launchPackage(packageName, packageLaunchMode(packageName))
    }

    /**
     * An omitted mode starts the first open of a package in an execution from the configured
     * policy, so pages left by earlier tasks are not the starting state; later opens resume the
     * pages this execution reached. Explicit modes are always honored and still count as opens.
     */
    @Synchronized
    private fun defaultPackageLaunchMode(packageName: String?): AppLaunchMode {
        val executionId = executionContextStore.current()?.executionId
        if (executionId != openedExecutionId) {
            openedExecutionId = executionId
            openedPackages.clear()
        }
        if (packageName.isNullOrBlank() || !openedPackages.add(packageName)) return AppLaunchMode.RESUME
        return localActionVerifier.appFirstOpenLaunchMode()
    }

    private suspend fun reliableInstalledPackages(packageName: String?): Set<String> {
        val installed = installedAppsManager.packagesAwait(packageName)
        return if (installed.size <= 1 && context.packageName in installed) emptySet() else installed
    }

    private suspend fun launchKnowledgeRoute(
        resolution: DeepLinkKnowledgeStore.DeepLinkResolution,
        launchMode: AppLaunchMode
    ): ToolExecutionResult {
        val resolved = resolution.resolvedUri
        val hasUriScheme = android.net.Uri.parse(resolved).scheme != null
        val target = resolveExplicitIntent(resolved.takeIf { hasUriScheme },
            resolved.takeUnless { hasUriScheme }, null, resolution.route.packageName, ExplicitIntentOptions.EMPTY)
        val preflight = if (target == null) toolRejection(
            "NATIVE_TARGET_UNAVAILABLE", true,
            "failure_kind" to "TARGET_UNAVAILABLE", "issue" to "no_activity_resolved",
            "package" to resolution.route.packageName, "uri" to resolved
        ) else inaccessibleTarget(target)
        // A retained foreground target can receive the route directly. Cold virtual entries
        // still need the launcher handoff; acceptance never verifies the rendered page.
        val entryLanding = if (preflight == null && AppEntryLanding.required(
                isVirtualSpaceSelected(), launchMode, resolution.route.packageName,
                localActionVerifier.currentForegroundPackageForContext())) {
            landAppEntry(resolution.route.packageName, launchMode)
        } else null
        // 入口帧只提供诊断；即使虚拟屏 launcher 首帧晚到也继续发送路由。
        // 入口已按 launchMode 重置过，后续路由不能再 FRESH 清掉刚落地的页面。
        val routeLaunchMode = if (entryLanding != null) AppLaunchMode.RESUME else launchMode
        val launchResult = preflight ?: launchExplicitIntent(
            uri = resolved.takeIf { hasUriScheme },
            action = resolved.takeUnless { hasUriScheme },
            activity = null,
            packageName = resolution.route.packageName,
            launchMode = routeLaunchMode
        )
        val routeFailed = launchResult.diagnosticCode == "DEEPLINK_ROUTE_FAILED"
        val launchFailed = !routeFailed && !ToolResultStatus.isLaunchAccepted(launchResult)
        val directRouteUnavailable = routeFailed || launchFailed
        deepLinkKnowledgeStore.recordLaunchResult(
            route = resolution.route,
            resolvedUri = resolved,
            success = false,
            detail = buildString {
                appendLine("app_version=" + runCatching { context.packageManager.getPackageInfo(resolution.route.packageName, 0).versionName }.getOrNull())
                appendLine("execution_space=" + executionContextStore.requireCurrent().space.name)
                append(launchResult.outputText)
            },
            resultLabel = when {
                routeFailed -> "route_failed"
                launchFailed -> "launch_failed"
                else -> "launched_unverified"
            },
            appLastUpdateTime = runCatching { context.packageManager.getPackageInfo(resolution.route.packageName, 0).lastUpdateTime }.getOrNull(),
            backendSessionId = executionContextStore.requireCurrent().backendSessionId,
            failureKind = runCatching { Json.parseToJsonElement(launchResult.outputText).jsonObject["details"]
                ?.jsonObject?.get("failure_kind")?.jsonPrimitive?.content }.getOrNull()
        )
        return preserveLaunchPrefix(launchResult, entryLanding != null).copy(outputText = buildString {
            entryLanding?.receipt?.let(::appendLine)
            appendLine(launchResult.outputText)
            appendLine("[DEEPLINK_ROUTE_MATCH] route_id=${resolution.route.id}")
            appendLine("app=${resolution.route.appName} package=${resolution.route.packageName} action=${resolution.route.action}")
            if (!directRouteUnavailable && ToolResultStatus.isLaunchAccepted(launchResult)) {
                DeepLinkRouteExpectation.marker(resolution.route)?.let { appendLine(it) }
            }
            append("source_type=${resolution.route.sourceType} score=${resolution.score}")
        })
    }
    /** 把 App 的 launcher activity 显式启动到当前执行显示上，返回一行回执。见 [AppEntryLanding]。 */
    private suspend fun landAppEntry(packageName: String, launchMode: AppLaunchMode): AppEntryLandingResult? {
        val component = AppEntryLanding.launcherComponent(context, packageName) ?: return null
        val result = launchExplicitIntent(
            uri = null,
            action = Intent.ACTION_MAIN,
            activity = component,
            packageName = packageName,
            launchMode = launchMode,
            options = ExplicitIntentOptions(categories = listOf(Intent.CATEGORY_LAUNCHER), expectScreenChange = false)
        )
        val firstLine = result.outputText.lineSequence().firstOrNull().orEmpty().take(200)
        val accepted = ToolResultStatus.isLaunchAccepted(result)
        val settled = if (accepted) localActionVerifier.awaitForegroundComponentExit(component) else false
        val marker = if (accepted) "[APP_ENTRY_REQUEST_ACCEPTED_UNVERIFIED]" else "[APP_ENTRY_FAILED]"
        return AppEntryLandingResult(accepted, "$marker $firstLine; launcher_exit_observed=$settled")
    }

    private suspend fun launchPackage(
        packageName: String?,
        launchMode: AppLaunchMode
    ): ToolExecutionResult {
        if (packageName.isNullOrBlank()) {
            return toolRejection(
                "ARGUMENT_SCHEMA_MISMATCH",
                true,
                "issue" to "app_or_intent_target_required"
            )
        }
        installedAppsManager.packagesAwait(packageName)
        val cachedTarget = installedAppsManager.currentSnapshot().apps.firstOrNull { it.packageName == packageName }?.launchTarget
        if (cachedTarget != null && runCatching {
            val component = ComponentName.unflattenFromString(cachedTarget)
            val info = context.packageManager.getActivityInfo(requireNotNull(component), 0)
            info.enabled && info.applicationInfo.enabled && info.exported
        }.getOrDefault(false).not()) installedAppsManager.refreshPackage(packageName)
        if (isVirtualSpaceSelected()) {
            val intentFlags = launchMode.intentFlags
            return launchPackageInVirtualSpace(packageName, launchMode, intentFlags)
        }
        if (launchMode == AppLaunchMode.RESUME &&
            localActionVerifier.currentForegroundPackageForContext() == packageName
        ) {
            return appendLocalValidation(
                ToolProtocolResult.success("[ALREADY_FOREGROUND] package=$packageName relaunch_skipped=true"),
                launchValidation(packageName, launchedUnverified = false)
            )
        }
        if (launchMode == AppLaunchMode.FRESH) {
            val stop = deviceBackend.forceStopApp(packageName)
            if (!stop.success) {
                return toolFailure(
                    "APP_RESET_FAILED",
                    true,
                    "package" to packageName,
                    "detail" to stop.stdout.ifBlank { stop.stderr }.take(240)
                )
            }
        }
        val launch = deviceBackend.openPackage(
            packageName = packageName,
            launchMode = launchMode,
            intentFlags = launchMode.intentFlags
        )
        if (!launch.accepted) {
            return preserveLaunchPrefix(toolRejection(
                launch.diagnosticCode ?: "APP_NOT_FOUND",
                launch.diagnosticCode == "BACKEND_SESSION_MISMATCH",
                "package" to packageName,
                "detail" to launch.detail.take(240)
            ), launchMode == AppLaunchMode.FRESH)
        }
        val settle = awaitLaunchSettled(packageName)
        return validatePackageLaunch(
            packageName,
            "Started app: $packageName launchMode=${launchMode.wireName} backend=${launch.backend}; launch_settle=${settle.outcome}"
        )
    }

    data class LaunchSettle(val outcome: String, val launcherExit: Boolean?, val elapsedMs: Long)

    /**
     * Apps often open on a splash, countdown or ad screen that resolves by itself within seconds,
     * which is shorter than one Agent decision. Hand the Agent a usable page instead: wait,
     * within app_launch_settle_timeout_ms, until the entry activity hands off or the frame is
     * fully still. A countdown keeps changing, so it is never mistaken for a settled page; after
     * a hand-off the new page gets a short bounded render settle.
     */
    private suspend fun awaitLaunchSettled(packageName: String): LaunchSettle = coroutineScope {
        val started = android.os.SystemClock.elapsedRealtime()
        val timeoutMs = localActionVerifier.appLaunchSettleTimeoutMs()
        if (timeoutMs <= 0) return@coroutineScope LaunchSettle("disabled", null, 0)
        val launcher = AppEntryLanding.launcherComponent(context, packageName)
        fun stillness(budgetMs: Long, localizedMotion: Double) = async {
            com.droidance.core.device.tools.VisualFrameSampler(
                capture = { deviceBackend.sampleScreen() },
                elapsedMillis = { android.os.SystemClock.elapsedRealtime() }
            ).sample(budgetMs.coerceAtLeast(1), LAUNCH_SAMPLE_INTERVAL_MS, LAUNCH_STABLE_SAMPLES, LAUNCH_PIXEL_DELTA,
                LAUNCH_CHANGED_FRACTION, localizedMotion).let { it.stable || it.localizedMotion }
        }
        val still = stillness(timeoutMs, 0.0)
        val handOff = launcher?.let { component -> async { localActionVerifier.awaitForegroundComponentExit(component, timeoutMs) } }
        val outcome = if (handOff == null) {
            if (still.await()) "still" else "timeout"
        } else {
            val winner = select<Pair<String, Boolean>> {
                still.onAwait { "still" to it }
                handOff.onAwait { "handoff" to it }
            }
            if (winner.second) winner.first
            else if (winner.first == "still") { if (handOff.await()) "handoff" else "timeout" }
            else if (still.await()) "still" else "timeout"
        }
        still.cancel(); handOff?.cancel()
        if (outcome == "handoff") {
            val remaining = timeoutMs - (android.os.SystemClock.elapsedRealtime() - started)
            stillness(minOf(remaining, LAUNCH_HANDOFF_RENDER_MS), LAUNCH_HANDOFF_LOCALIZED_MOTION).await()
        }
        LaunchSettle(outcome, if (handOff == null) null else outcome == "handoff",
            android.os.SystemClock.elapsedRealtime() - started)
    }

    private suspend fun launchPackageInVirtualSpace(
        packageName: String,
        launchMode: AppLaunchMode,
        intentFlags: Int
    ): ToolExecutionResult {
        val display = virtualDisplayIdOrMissing()
        val displayId = display.first
            ?: return toolRejection(
                "VIRTUAL_SPACE_DISPLAY_MISSING",
                true,
                "detail" to display.second
            )
        val launch = deviceBackend.openPackage(packageName, launchMode, intentFlags)
        if (!launch.accepted) {
            return toolFailure(
                "VIRTUAL_SPACE_LAUNCH_FAILED",
                true,
                "package" to packageName,
                "display_id" to displayId.toString(),
                "detail" to launch.detail.take(240)
            )
        }
        if (launch.frameReady != true) {
            return appendLocalValidation(
                ToolProtocolResult.accepted("[VIRTUAL_SPACE_LAUNCHED_UNVERIFIED] package=$packageName display_id=$displayId frame_ready=false detail=${launch.detail.take(240)}"),
                virtualLaunchValidation(packageName)
            )
        }
        val settle = awaitLaunchSettled(packageName)
        return appendLocalValidation(
            ToolProtocolResult.accepted("[VIRTUAL_SPACE_LAUNCHED] Started app in virtual space: $packageName displayId=$displayId" +
                (settle.launcherExit?.let { "; launcher_exit_observed=$it" } ?: "") + "; launch_settle=${settle.outcome}")
                .copy(metrics = mapOf("app_launch_settle_ms" to settle.elapsedMs)),
            virtualLaunchValidation(packageName)
        )
    }

    private suspend fun launchExplicitIntent(
        uri: String?,
        action: String?,
        activity: String?,
        packageName: String?,
        launchMode: AppLaunchMode,
        options: ExplicitIntentOptions = ExplicitIntentOptions.EMPTY
    ): ToolExecutionResult {
        val resolvedTarget = resolveExplicitIntent(uri, action, activity, packageName, options)
            ?: return toolRejection(
                "NATIVE_TARGET_UNAVAILABLE",
                true,
                "issue" to "no_activity_resolved",
                "failure_kind" to "TARGET_UNAVAILABLE",
                "intent_action" to action.orEmpty(),
                "package" to packageName.orEmpty(),
                "activity" to activity.orEmpty(),
                "action_parameter_type" to "android_intent_action",
                "public_title_field" to "action_title"
            )
        val execution = executionContextStore.requireCurrent()
        inaccessibleTarget(resolvedTarget)?.let { return it }
        val expectedPackage = packageName
            ?: explicitActivityTarget(activity, packageName)?.packageName
        val deepLink = !uri.isNullOrBlank()
        val before = if (options.expectScreenChange == false) null else runCatching {
            localActionVerifier.capture(forceFresh = true)
        }.getOrNull()
        if (launchMode == AppLaunchMode.FRESH && !expectedPackage.isNullOrBlank()) {
            val reset = deviceBackend.forceStopApp(expectedPackage)
            if (!reset.success) {
                return toolFailure(
                    "APP_RESET_FAILED",
                    true,
                    "package" to expectedPackage,
                    "detail" to reset.stdout.ifBlank { reset.stderr }.take(240)
                )
            }
        }

        val command = buildExplicitIntentCommand(
            displayId = execution.displayId,
            uri = uri,
            action = action,
            activity = activity,
            packageName = packageName,
            launchMode = launchMode,
            options = options
        )
        val result = deviceBackend.openIntentCommand(command)
        val output = listOf(result.stdout, result.stderr)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .trim()
        val rejected = listOf("error:", "exception", "permission deni", "unable to resolve", "does not exist")
            .any { marker -> output.contains(marker, ignoreCase = true) }
        if (!result.success || rejected) {
            val label = when {
                deepLink -> "DEEPLINK_LAUNCH_FAILED"
                !action.isNullOrBlank() -> "INTENT_LAUNCH_FAILED"
                else -> "ACTIVITY_LAUNCH_FAILED"
            }
            val permissionDenied = output.contains("SecurityException", true) || output.contains("Permission Denial", true)
            val targetUnavailable = output.contains("unable to resolve", true) || output.contains("does not exist", true)
            return toolFailure(
                label,
                !permissionDenied && !targetUnavailable,
                "failure_kind" to when {
                    permissionDenied -> "PERMISSION_DENIED"
                    targetUnavailable -> "TARGET_UNAVAILABLE"
                    result.diagnosticCode != null -> "CHANNEL_UNAVAILABLE"
                    else -> "LAUNCH_REJECTED"
                },
                "channel_unavailable" to (result.diagnosticCode != null).toString(),
                "detail" to output.ifBlank { "Android rejected the launch command" }
            )
        }

        val label = when {
            deepLink -> "DEEPLINK_LAUNCHED"
            !action.isNullOrBlank() -> "INTENT_LAUNCHED"
            else -> "ACTIVITY_LAUNCHED"
        }
        val target = uri?.takeIf(String::isNotBlank)
            ?: action?.takeIf(String::isNotBlank)
            ?: activity.orEmpty()
        if (options.expectScreenChange == false) {
            val platformContract = options.completionEvidence == NativeCompletionEvidence.PLATFORM_CONTRACT
            return ToolProtocolResult.accepted("[${options.receiptMarker()}] display_id=${execution.displayId} target=${target.take(240)} ${options.receiptSummary()}").copy(
                platformContractAccepted = platformContract,
                evidenceStatus = if (platformContract) com.droidance.core.agent.event.ToolEvidenceStatus.NOT_REQUIRED
                    else com.droidance.core.agent.event.ToolEvidenceStatus.UNKNOWN
            )
        }
        val after = localActionVerifier.awaitRouteTransition(before, expectedPackage, requireFreshFrame = true)
        val validation = localActionVerifier.validate(
            toolName = "android_act.open",
            arguments = target,
            before = before,
            after = after,
            expectedPackage = expectedPackage,
            launchedUnverified = true
        )
        if (validation.progressState == LocalActionEvidence.ProgressState.ROUTE_FAILED) {
            val failedLabel = when {
                deepLink -> "DEEPLINK_ROUTE_FAILED"
                !action.isNullOrBlank() -> "INTENT_ROUTE_FAILED"
                else -> "ACTIVITY_ROUTE_FAILED"
            }
            return appendLocalValidation(
                ToolProtocolResult.accepted("[$failedLabel] Android accepted the explicit launch on display ${execution.displayId}, but the expected route did not render: ${target.take(240)}").copy(diagnosticCode = failedLabel),
                validation
            )
        }
        return appendLocalValidation(
            ToolProtocolResult.accepted("[$label] Android accepted the explicit launch on display ${execution.displayId}: " +
                "${target.take(240)} resolved=${resolvedTarget.packageName}/${resolvedTarget.name}"),
            validation
        )
    }

    private fun resolveExplicitIntent(
        uri: String?,
        action: String?,
        activity: String?,
        packageName: String?,
        options: ExplicitIntentOptions
    ): android.content.pm.ActivityInfo? {
        val effectiveAction = action?.takeIf(String::isNotBlank)
            ?: uri?.takeIf(String::isNotBlank)?.let { Intent.ACTION_VIEW }
        val intent = Intent(effectiveAction).apply {
            val data = uri?.takeIf(String::isNotBlank)?.let(android.net.Uri::parse)
            val mimeType = options.mimeType?.takeIf(String::isNotBlank)
            when {
                data != null && mimeType != null -> setDataAndType(data, mimeType)
                data != null -> setData(data)
                mimeType != null -> type = mimeType
            }
            options.categories.forEach(::addCategory)
            packageName?.takeIf(String::isNotBlank)?.let(::setPackage)
            activity?.takeIf(String::isNotBlank)?.let {
                component = explicitActivityTarget(it, packageName)?.toComponentName() ?: return null
            }
        }
        return context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
    }

    private fun inaccessibleTarget(target: android.content.pm.ActivityInfo): ToolExecutionResult? {
        if (!target.enabled || !target.applicationInfo.enabled) return toolRejection("NATIVE_TARGET_DISABLED", false,
            "failure_kind" to "TARGET_DISABLED",
            "package" to target.packageName, "activity" to target.name)
        // Standard launches use the paired shell UID rather than the application UID.
        if (executionContextStore.requireCurrent().mode != com.droidance.core.agent.device.RuntimeMode.STANDARD ||
            target.exported || target.applicationInfo.uid == 2000) return null
        return toolRejection("NATIVE_TARGET_NOT_EXPORTED", false,
            "failure_kind" to "TARGET_NOT_EXPORTED",
            "package" to target.packageName, "activity" to target.name,
            "detail" to "The resolved activity is not exported to the frozen shell backend; no launch was sent.")
    }

    private fun buildExplicitIntentCommand(
        displayId: Int,
        uri: String?,
        action: String?,
        activity: String?,
        packageName: String?,
        launchMode: AppLaunchMode,
        options: ExplicitIntentOptions
    ): String = ExplicitIntentCommand.build(
        displayId = displayId, flags = launchMode.intentFlags, uri = uri, action = action,
        component = explicitActivityTarget(activity, packageName)?.flattened(),
        packageName = packageName, options = options, escape = adbShell::escapeForShell
    )

    private fun isVirtualSpaceSelected(): Boolean =
        executionContextStore.requireCurrent().space == ExecutionSpace.VIRTUAL_SPACE

    private fun virtualDisplayIdOrMissing(): Pair<Int?, String> {
        val context = executionContextStore.requireCurrent()
        if (context.space == ExecutionSpace.VIRTUAL_SPACE) {
            return context.displayId to (
                context.readinessMessage ?: "Virtual space is selected, but no virtual displayId is available."
            )
        }
        return null to "The frozen execution context is not using virtual space."
    }

    private suspend fun validatePackageLaunch(
        packageName: String,
        successText: String
    ): ToolExecutionResult {
        if (!waitForForegroundPackage(packageName, attempts = 4, delayMs = 300)) {
            return toolFailure(
                "OPEN_APP_VERIFICATION_FAILED",
                true,
                "package" to packageName,
                "verification" to foregroundVerificationStatus(packageName)
            )
        }
        return appendLocalValidation(
            ToolProtocolResult.accepted(successText),
            launchValidation(packageName, launchedUnverified = false)
        )
    }

    private suspend fun waitForForegroundPackage(packageName: String?, attempts: Int, delayMs: Long): Boolean {
        if (packageName.isNullOrBlank()) return true
        repeat(attempts.coerceAtLeast(1)) {
            if (localActionVerifier.currentForegroundPackageForContext() == packageName) return true
            delay(delayMs)
        }
        return localActionVerifier.currentForegroundPackageForContext() == packageName
    }

    private fun foregroundVerificationStatus(packageName: String?): String {
        val foreground = localActionVerifier.currentForegroundPackageForContext()
        val current = foreground ?: "unknown"
        val channelMarker = if (foreground == null) " ${SidecarInputController.CHANNEL_UNAVAILABLE_MARKER}" else ""
        return "target=${packageName ?: "unknown"}, current=$current$channelMarker"
    }

    private fun toolRejection(
        code: String,
        retryable: Boolean,
        vararg details: Pair<String, String>
    ): ToolExecutionResult = ToolProtocolResult.notExecuted(
        code = code,
        retryable = retryable,
        details = buildJsonObject {
            details.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("launch_delivered", JsonPrimitive(false))
            put("foreground_observed_at_ms", JsonPrimitive(System.currentTimeMillis()))
            put("observed_foreground_package", runCatching { localActionVerifier.currentForegroundPackageForContext() }
                .getOrNull()?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            put("backend_session_id", executionContextStore.current()?.backendSessionId?.let(::JsonPrimitive)
                ?: kotlinx.serialization.json.JsonNull)
        }
    )

    private fun toolFailure(
        code: String,
        retryable: Boolean,
        vararg details: Pair<String, String>
    ): ToolExecutionResult = ToolProtocolResult.failed(
        code = code,
        retryable = retryable,
        details = buildJsonObject {
            details.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("launch_delivery", JsonPrimitive("unknown"))
            put("foreground_observed_at_ms", JsonPrimitive(System.currentTimeMillis()))
            put("observed_foreground_package", runCatching { localActionVerifier.currentForegroundPackageForContext() }
                .getOrNull()?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            put("backend_session_id", executionContextStore.current()?.backendSessionId?.let(::JsonPrimitive)
                ?: kotlinx.serialization.json.JsonNull)
        }
    )

    private fun virtualLaunchValidation(
        packageName: String?
    ): LocalActionEvidence.ValidationResult {
        return LocalActionEvidence.ValidationResult(
            toolName = "android_act.open",
            progressState = LocalActionEvidence.ProgressState.ROUTE_LAUNCHED_UNVERIFIED,
            reason = "Target app launch was sent to the virtual display; page semantics are not verified yet",
            beforeHash = null,
            afterHash = null,
            foregroundPackage = localActionVerifier.currentForegroundPackageForContext(),
            expectedPackage = packageName
        )
    }

    private fun launchValidation(
        packageName: String?,
        launchedUnverified: Boolean
    ): LocalActionEvidence.ValidationResult {
        val progress = if (launchedUnverified) {
            LocalActionEvidence.ProgressState.ROUTE_LAUNCHED_UNVERIFIED
        } else {
            LocalActionEvidence.ProgressState.ROUTE_VERIFIED
        }
        return LocalActionEvidence.ValidationResult(
            toolName = "android_act.open",
            progressState = progress,
            reason = if (launchedUnverified) {
                if (packageName == null) {
                    "Android accepted the launch, but no expected package was available for semantic verification"
                } else {
                    "Target app is foreground, but page semantics are not verified yet"
                }
            } else {
                "Foreground package matches the target app"
            },
            beforeHash = null,
            afterHash = null,
            foregroundPackage = packageName,
            expectedPackage = packageName
        )
    }
}

internal data class ExplicitActivityTarget(val packageName: String, val className: String) {
    fun toComponentName(): ComponentName = ComponentName(packageName, className)
    fun flattened(): String = "$packageName/$className"
}

internal fun explicitActivityTarget(activity: String?, packageName: String?): ExplicitActivityTarget? {
    val rawActivity = activity?.trim()?.takeIf(String::isNotBlank) ?: return null
    val flattenedParts = rawActivity.split('/', limit = 2)
    val targetPackage = if (flattenedParts.size == 2) {
        flattenedParts[0].takeIf(String::isNotBlank)
    } else {
        packageName?.trim()?.takeIf(String::isNotBlank)
    } ?: return null
    val rawClassName = if (flattenedParts.size == 2) flattenedParts[1] else rawActivity
    val className = when {
        rawClassName.startsWith('.') -> targetPackage + rawClassName
        '.' in rawClassName -> rawClassName
        else -> "$targetPackage.$rawClassName"
    }
    return ExplicitActivityTarget(targetPackage, className)
}

internal data class AppEntryLandingResult(val success: Boolean, val receipt: String)

/** A rejected suffix cannot turn an attempted launch prefix into an unexecuted whole call. */
internal fun preserveLaunchPrefix(result: ToolExecutionResult, priorMayHaveExecuted: Boolean): ToolExecutionResult =
    if (priorMayHaveExecuted && result.executionStatus == com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED)
        result.copy(executionStatus = com.droidance.core.agent.event.ToolExecutionStatus.FAILED,
            metrics = result.metrics + ("launch_prefix_may_have_executed" to 1L))
    else result
