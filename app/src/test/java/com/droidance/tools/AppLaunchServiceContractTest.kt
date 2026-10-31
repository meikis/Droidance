// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.droidance.core.agent.tools.ToolCatalog

class AppLaunchServiceContractTest {
    @Test fun rejectedRouteAfterAPossibleLaunchPrefixStillRequiresFreshObservation() {
        val rejected = ToolProtocolResult.notExecuted("NATIVE_TARGET_UNAVAILABLE", true)
        assertTrue(preserveLaunchPrefix(rejected, false) === rejected)
        val partial = preserveLaunchPrefix(rejected, true)
        assertTrue(partial.executionStatus == com.droidance.core.agent.event.ToolExecutionStatus.FAILED)
        val gate = com.droidance.core.agent.CompletionGroundingGate()
        gate.recordExecutionStart("open", com.droidance.core.agent.event.ToolBinding.DEVICE_BOUND,
            com.droidance.core.agent.event.ToolEffect.STATE_CHANGING)
        gate.recordExecutedResult("open", com.droidance.core.agent.event.ToolBinding.DEVICE_BOUND,
            com.droidance.core.agent.event.ToolEffect.STATE_CHANGING, partial)
        assertTrue(gate.requiresTerminalObservation(true, false))
        assertTrue(gate.requiresFreshObservationBeforeCompletion())
    }
    @Test
    fun explicitActivityAcceptsStandardAndroidComponentForms() {
        val expected = ExplicitActivityTarget("com.example", "com.example.MainActivity")

        assertTrue(explicitActivityTarget("com.example/.MainActivity", null) == expected)
        assertTrue(explicitActivityTarget("com.example.MainActivity", "com.example") == expected)
        assertTrue(explicitActivityTarget(".MainActivity", "com.example") == expected)
        assertTrue(expected.flattened() == "com.example/com.example.MainActivity")
        assertNull(explicitActivityTarget("com.example.MainActivity", null))
    }

    @Test
    fun openAppOwnsTheGenericDeepLinkAndIntentEntryPoint() {
        val source = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val schema = projectFile("core/device/src/main/java/com/droidance/core/device/tools/ExplicitIntentOptions.kt").readText()

        assertFalse(source.contains("registerTool"))
        assertFalse(source.contains("override val name = \"open_app\""))
        assertTrue(schema.contains("OpenAppToolSchema"))
        assertTrue(schema.contains("\"uri\" to ToolParameter"))
        assertTrue(schema.contains("\"intent_action\" to ToolParameter"))
        assertTrue(schema.contains("\"categories\" to ToolParameter"))
        assertTrue(schema.contains("\"string_extras\" to ToolParameter"))
        assertTrue(schema.contains("\"int_extras\" to ToolParameter"))
        assertTrue(schema.contains("\"long_extras\" to ToolParameter"))
        assertTrue(schema.contains("\"boolean_extras\" to ToolParameter"))
        assertTrue(schema.contains("\"completion_evidence\" to ToolParameter"))
        assertTrue(source.contains("ExplicitIntentOptions.parse(json)"))
        assertTrue(source.contains("ExplicitIntentCommand.build("))
        assertTrue(schema.contains("\"route_action\" to ToolParameter"))
        assertFalse(schema.contains("route_fallback_reason"))
        assertTrue(source.contains("deepLinkKnowledgeStore.resolve("))
        assertTrue(source.contains("[DEEPLINK_ROUTE_MATCH]"))
        assertTrue(source.contains("launchExplicitIntent("))
        assertTrue(source.contains("resolveExplicitIntent("))
        assertTrue(source.contains("context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)"))
        assertTrue(source.contains("\"NATIVE_TARGET_UNAVAILABLE\""))
        assertTrue(source.contains("ToolProtocolResult.notExecuted("))
        assertTrue(source.contains("ToolProtocolResult.failed("))
        assertFalse(source.contains("[DEEPLINK_TOOL_REQUIRED]"))
        assertFalse(source.contains("use list_deeplink_candidates and launch_deeplink"))
    }

    @Test
    fun virtual_route_waits_for_launcher_handoff_before_sending_the_deeplink() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val verifier = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()
        val config = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()

        assertTrue(registrar.contains("awaitForegroundComponentExit(component)"))
        assertTrue(verifier.contains("routeEntrySettleTimeoutMs"))
        assertTrue(verifier.contains("currentForegroundComponentForContext()"))
        assertTrue(config.contains("route_entry_settle_timeout_ms:"))
    }

    @Test
    fun openAppDoesNotAutoIterateDeepLinkTopFive() {
        val source = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()

        assertFalse(source.contains("launchBestDeepLink("))
        assertFalse(source.contains("candidates.take(5)"))
    }

    @Test
    fun modelChoosesBetweenPackageIntentRouteAndGuiWithoutAHarnessRouteGate() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val operator = com.droidance.agent.coordinationSources()
        val prompts = projectFile("app/src/main/assets/rules/harness_prompts.yaml").readText()
        val argumentProtocol = projectFile("core/device/src/main/java/com/droidance/core/device/tools/ExplicitIntentOptions.kt").readText()

        assertTrue(registrar.contains("deepLinkKnowledgeStore.resolve("))
        assertTrue(registrar.contains("launchExplicitIntent("))
        assertTrue(registrar.contains("launchPackage(packageName, packageLaunchMode(packageName))"))
        assertTrue(argumentProtocol.contains("route_action_requires_app_target"))
        assertFalse(executor.contains("preflightToolCall"))
        assertFalse(operator.contains("DirectRouteRepeatGuard"))
        assertFalse(projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/RuntimePolicies.kt").readText().contains("DeepLinkPreopenPolicy"))
        assertFalse(operator.contains("DEEPLINK_CANDIDATE_ROUTE_REQUIRED"))
        assertFalse(prompts.contains("deeplink_route_required"))
        assertFalse(prompts.contains("Screen actions are withheld"))
        assertTrue(executor.contains("recordExecutionFallback(executionPath, toolCall.name"))
        assertFalse(executor.contains("diagnostics.recordExecutionPath(path, toolCall.name)"))
        assertTrue(executor.contains("if (ToolResultStatus.isNotExecuted(result)) return"))
    }

    @Test
    fun deepLinkLaunchHasNoParallelRegistrarOrTaskLocalVerificationState() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val executor = com.droidance.agent.executorSources()

        assertFalse(toolsDir.resolve("DeepLinkToolRegistrar.kt").exists())
        assertFalse(executor.contains("deepLinkTaskStateResetter"))
        assertFalse(executor.contains("lastLaunchedDeepLink"))
        assertFalse(executor.contains("clearPendingDeepLinkVerification"))
    }

    @Test
    fun routeDiscoveryIsInternalToOpenApp() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val prompt = projectFile("app/src/main/assets/rules/agent_core.md").readText()

        assertFalse(ToolCatalog.operatorToolNames.contains("query_deep_links"))
        assertFalse(ToolCatalog.operatorToolNames.contains("verify_deep_link"))
        assertFalse(prompt.contains("query_deep_links"))
        assertFalse(prompt.contains("launch_deeplink"))
        assertTrue(registrar.contains("deepLinkKnowledgeStore.resolve("))
    }

    @Test
    fun uriRoutesUseSharedEncoderWithoutForcingTheResolvedActivity() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()

        assertTrue(registrar.contains("ExplicitIntentCommand.build("))
        assertTrue(registrar.contains("component = explicitActivityTarget(activity, packageName)?.flattened()"))
        assertTrue(registrar.contains("packageName = packageName, options = options"))
    }

    @Test
    fun deepLinkWithoutExpectedPackageIsNotForegroundVerified() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()

        assertTrue(registrar.contains("localActionVerifier.capture(forceFresh = true)"))
        assertTrue(registrar.contains("localActionVerifier.awaitRouteTransition("))
        assertTrue(registrar.contains("requireFreshFrame = true"))
        assertTrue(registrar.contains("localActionVerifier.validate("))
        assertTrue(registrar.contains("launchedUnverified = true"))
    }

    @Test
    fun openAppUsesFrozenDeviceBackendWhenVirtualSpaceIsSelected() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val backend = projectFile("app/src/main/java/com/droidance/agent/device/AndroidDeviceBackend.kt").readText()
        val executor = com.droidance.agent.executorSources()

        assertTrue(registrar.contains("private val deviceBackend: DeviceBackend"))
        assertFalse(registrar.contains("private val virtualSpaceManager: VirtualSpaceManager"))
        assertFalse(registrar.contains("private val virtualSpaceSidecarBridge: VirtualSpaceSidecarBridge"))
        assertFalse(registrar.contains("private val deviceShellCommandChannel: DeviceShellCommandChannel"))
        assertTrue(registrar.contains("private val executionContextStore: ExecutionContextStore"))
        assertTrue(registrar.contains("ExecutionSpace.VIRTUAL_SPACE"))
        assertTrue(registrar.contains("executionContextStore.requireCurrent()"))
        assertTrue(registrar.contains("context.readinessMessage"))
        assertTrue(registrar.contains("deviceBackend.openPackage(packageName, launchMode, intentFlags)"))
        assertTrue(backend.contains("virtualSpaceManager.launchApp(packageName, launchMode, intentFlags)"))
        assertTrue(registrar.contains("virtualLaunchValidation(packageName)"))
        assertTrue(executor.contains("executionContextStore = executionContextStore"))
    }

    @Test
    fun openAppExposesGenericTaskStackModesWithoutAppRules() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val schema = projectFile("core/device/src/main/java/com/droidance/core/device/tools/ExplicitIntentOptions.kt").readText()
        val modes = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/device/AppLaunchMode.kt").readText()
        val rules = projectFile("app/src/main/assets/rules/agent_core.md").readText()

        assertTrue(schema.contains("\"launch_mode\" to ToolParameter"))
        assertTrue(modes.contains("RESUME(\"resume\")"))
        assertTrue(modes.contains("RESET_TASK(\"reset_task\")"))
        assertTrue(modes.contains("FRESH(\"fresh\")"))
        assertTrue(schema.contains("resume keeps the current page"))
        assertTrue(schema.contains("reset_task returns through the launcher entry"))
        assertFalse(rules.contains("launch_mode=reset_task"))
        assertFalse(modes.contains("com.jingdong"))
    }

    @Test
    fun unresolvedAppNameUsesTheSharedStructuredFailureProtocol() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()

        assertTrue(registrar.contains("\"APP_NAME_UNRESOLVED\""))
        assertFalse(registrar.contains("No app named"))
        assertFalse(registrar.contains("Use android_act"))
        assertFalse(registrar.contains("verify the destination with android_read"))
        assertFalse(registrar.contains("Next step must"))
    }

    @Test
    fun routeAliasCanResolveTheTargetPackageBeforeTheDirectRouteLaunch() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
            .substringAfter("private suspend fun launchApp(arguments: String)")
            .substringBefore("private suspend fun reliableInstalledPackages()")

        assertTrue(registrar.contains("val candidates = installedAppsManager.findCandidates(appName)"))
        assertTrue(registrar.contains("APP_NAME_AMBIGUOUS"))
        assertTrue(registrar.contains("packageName = deepLinkKnowledgeStore.resolve("))
        assertTrue(registrar.contains("action = routeAction"))
        assertTrue(registrar.contains("keyword = routeKeyword"))
        assertTrue(registrar.contains("routeParams.values.singleOrNull()"))
    }

    @Test
    fun routeLaunchRecordsUnverifiedEvidenceWithoutPromotingSemanticSuccess() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val appLaunch = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val routeBody = appLaunch.substringAfter("private suspend fun launchKnowledgeRoute(")
            .substringBefore("private suspend fun landAppEntry(")
        val executor = toolsDir.resolve("ToolExecutor.kt").readText()

        assertFalse(toolsDir.resolve("DeepLinkToolRegistrar.kt").exists())
        assertFalse(
            "Foreground launch is not semantic success and must not create learned app knowledge",
            routeBody.contains("appendDeepLinkRecord(")
        )
        assertTrue(routeBody.contains("recordLaunchResult("))
        assertTrue(routeBody.contains("success = false"))
        assertTrue(routeBody.contains("else -> \"launched_unverified\""))
        assertFalse(executor.contains("lastLaunchedDeepLink"))
        assertFalse(executor.contains("clearPendingDeepLinkVerification"))
    }

    @Test
    fun openAppIntentTargetsTheFrozenDisplay() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val executor = com.droidance.agent.executorSources()

        assertTrue(registrar.contains("private val executionContextStore: ExecutionContextStore"))
        assertTrue(registrar.contains("ExecutionSpace.VIRTUAL_SPACE"))
        assertTrue(registrar.contains("displayId = execution.displayId"))
        assertTrue(registrar.contains("deviceBackend.openIntentCommand(command)"))
        assertTrue(executor.contains("executionContextStore = executionContextStore"))
    }

    @Test
    fun failedVirtualAppEntryStillContinuesIntoTheResolvedDeepLink() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val routeLaunch = registrar.substringAfter("private suspend fun launchKnowledgeRoute(")
            .substringBefore("private suspend fun landAppEntry(")
        val entryLaunch = registrar.substringAfter("private suspend fun landAppEntry(")
            .substringBefore("private suspend fun launchPackage(")

        assertFalse(routeLaunch.contains("if (entryLanding != null && !entryLanding.success) return entryLanding.receipt"))
        assertTrue(routeLaunch.contains("val launchResult = preflight ?: launchExplicitIntent("))
        assertTrue(entryLaunch.contains("ToolResultStatus.isLaunchAccepted(result)"))
        assertTrue(entryLaunch.contains("[APP_ENTRY_REQUEST_ACCEPTED_UNVERIFIED]"))
        assertTrue(entryLaunch.contains("launcher_exit_observed="))
        assertFalse(entryLaunch.contains("[APP_ENTRY_LANDED]"))
        assertTrue(entryLaunch.contains("[APP_ENTRY_FAILED]"))
    }

    @Test
    fun failedCatalogRouteReturnsItsEvidenceWithoutHiddenRetryOrGuiFallback() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val routeLaunch = registrar.substringAfter("private suspend fun launchKnowledgeRoute(")
            .substringBefore("private suspend fun landAppEntry(")

        assertTrue(routeLaunch.contains("routeFailed || launchFailed"))
        assertTrue(routeLaunch.contains("appendLine(launchResult.outputText)"))
        assertFalse(routeLaunch.contains("recoverDeepLinkWithPackage("))
        assertFalse(routeLaunch.contains("AppLaunchMode.RESET_TASK"))
        assertFalse(routeLaunch.contains("launchPackage("))
    }

    @Test
    fun unavailableCommittedSidecarStopsLaunchRetriesWithAChannelMarker() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val backend = projectFile("app/src/main/java/com/droidance/agent/device/AndroidDeviceBackend.kt").readText()

        assertTrue(registrar.contains("result.diagnosticCode != null"))
        assertTrue(backend.contains("BACKEND_SESSION_MISMATCH"))
        assertTrue(backend.contains("BACKEND_CHANNEL_UNAVAILABLE"))
    }

    @Test
    fun deepLinkDisabledGateRejectsRouteActionsAndCustomSchemes() {
        val source = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()

        assertTrue(source.contains("DEEPLINK_DISABLED"))
        assertTrue(source.contains("isDeepLinkEnabled()"))
        assertTrue(source.contains("STANDARD_INTENT_SCHEMES"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
