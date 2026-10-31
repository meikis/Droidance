// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.tools.DeviceToolNames
import com.droidance.core.agent.tools.UnifiedDeviceToolProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Serializable
data class ObservationRecoveryPolicy(
    val attempts: Int,
    val maxFingerprintDistance: Int,
    val historyLimit: Int,
    val scopeHistoryLimit: Int,
    val coordinateTolerance: Int,
    val refreshAfterTools: Set<String>,
    val refreshAfterDiagnostics: Set<String>,
    val actions: Map<String, List<String>>,
    val maxCurrentFrameCrops: Int,
    val observationScopedActions: Set<String>,
    val effectChangeFraction: Double,
    val effectChannelDelta: Int,
    val maxOscillationCycles: Int = 2,
    /** At most this fraction of signature samples may differ for a later frame to show the same state again. */
    val revisitSameFraction: Double = 0.0005,
    /** Appearances of one state, counting its first, before its return is reported. */
    val revisitMinAppearances: Int = 3
) {
    fun validate() {
        require(attempts > 0 && historyLimit > 0 && scopeHistoryLimit > 0)
        require(effectChangeFraction > 0.0 && effectChangeFraction < 1.0 && effectChannelDelta in 0..254)
        require(maxCurrentFrameCrops > 0)
        require(maxFingerprintDistance in 0..64 && coordinateTolerance > 0 && maxOscillationCycles > 0)
        require(revisitSameFraction >= 0.0 && revisitSameFraction < effectChangeFraction && revisitMinAppearances >= 2)
        require(actions.isNotEmpty() && refreshAfterDiagnostics.isNotEmpty())
        require(observationScopedActions.all { it in actions })
    }
}

/** Blocks exhausted methods, never terminates the task. Similar pixels are not semantic success. */
class ObservationRecovery(private val policy: ObservationRecoveryPolicy) {
    private data class Attempt(
        val fingerprint: Long,
        val firstObservationId: String,
        val firstArguments: JsonObject,
        var latestObservationId: String,
        var latestArguments: JsonObject,
        var signature: ScreenChangeSignature?,
        var count: Int = 0
    )
    private data class PendingAttempt(
        val key: String,
        val observationId: String,
        val fingerprint: Long,
        val observationScoped: Boolean,
        val signature: ScreenChangeSignature?,
        val executions: Int = 1
    )
    private data class StateTransition(
        val fingerprint: Long,
        val observationId: String,
        val incomingActionKeys: Set<String>
    )
    private class VisualState(
        val signature: ScreenChangeSignature,
        val firstObservationId: String,
        var latestObservationId: String,
        var appearances: Int = 1
    )
    private class ScopeHistory {
        val attempts = linkedMapOf<String, Attempt>()
        var fingerprint: Long? = null
        var frameHash: String? = null
        var signature: ScreenChangeSignature? = null
        var observationId: String? = null
        var pending: List<PendingAttempt> = emptyList()
        val stateTransitions = mutableListOf<StateTransition>()
        var oscillationCycles: Int = 0
        var oscillationPeriod: Int = 0
        var oscillationActionKeys: Set<String> = emptySet()
        /** Distinct post-action states, most recent last; a fine signature tells states apart. */
        val visualStates = mutableListOf<VisualState>()
        var revisited: VisualState? = null
    }
    private data class ActionKey(val key: String, val arguments: JsonObject, val observationScoped: Boolean)
    private data class FailedMethod(val scope: String?, val action: JsonObject, var count: Int, var diagnostic: String)
    private val failures = mutableListOf<FailedMethod>()
    private val scopes = linkedMapOf<String, ScopeHistory>()
    private var sessionIdentity: String? = null
    private var scope: String? = null
    private val current: ScopeHistory? get() = scopes[scope]
    private var coordinateResolver: ((String, Int, Int) -> com.droidance.core.agent.tools.ModelCoordinateMapper.Point?)? = null

    fun bindCoordinateSources(observationId: String, contentRef: String,
        images: List<com.droidance.core.agent.event.DetailAttachmentAddedEventPayload>) {
        coordinateResolver = { ref, x, y ->
            com.droidance.core.agent.tools.RetainedCoordinateResolver.resolve(ref, x, y, observationId, contentRef, images)
        }
    }

    fun reset() {
        coordinateResolver = null
        sessionIdentity = null
        scope = null
        scopes.clear()
        failures.clear()
    }

    fun observe(
        sessionIdentity: String,
        scope: String,
        observationId: String,
        fingerprint: Long?,
        frameHash: String?,
        changeSignature: ScreenChangeSignature? = null
    ) {
        coordinateResolver = null
        if (this.sessionIdentity != sessionIdentity) reset()
        this.sessionIdentity = sessionIdentity
        // Different image geometry cannot settle an action on the previous geometry.
        // Preserve settled attempts so inspecting a crop cannot erase them.
        if (this.scope != scope) current?.pending = emptyList()
        this.scope = scope
        val history = scopes.remove(scope) ?: ScopeHistory()
        if (scopes.size >= policy.scopeHistoryLimit) scopes.remove(scopes.keys.first())
        scopes[scope] = history
        // Encoded bytes can change for a caret, animation or clock. They identify evidence,
        // but must not erase repeated attempts on the same approximate viewport.
        history.frameHash = frameHash
        history.signature = changeSignature
        history.fingerprint = fingerprint
        history.observationId = observationId
        recordVisualState(history, observationId, changeSignature)
        // Unknown images cannot establish stagnation.
        if (fingerprint == null || frameHash == null) { history.pending = emptyList(); return }

        val lastTransition = history.stateTransitions.lastOrNull()
        if (lastTransition == null || !isSimilar(lastTransition.fingerprint, fingerprint)) {
            history.stateTransitions.add(StateTransition(
                fingerprint, observationId, history.pending
                    .filter { it.observationId != observationId }
                    .mapTo(mutableSetOf()) { it.key }
            ))
            if (history.stateTransitions.size > policy.historyLimit) {
                history.stateTransitions.removeAt(0)
            }
            val (detectedCycles, detectedPeriod) = detectOscillation(history.stateTransitions)
            history.oscillationCycles = detectedCycles
            history.oscillationPeriod = detectedPeriod
            // Only movements between states inside the detected suffix belong to this
            // cycle. Its first state's incoming action may have arrived from outside it.
            history.oscillationActionKeys = history.stateTransitions
                .takeLast(detectedCycles * detectedPeriod)
                .drop(1)
                .flatMapTo(mutableSetOf()) { it.incomingActionKeys }
        }

        val settledKeys = mutableSetOf<String>()
        history.pending.forEach { pending ->
            if (pending.observationId != observationId) {
                history.attempts[pending.key]?.let {
                    // Count similar-view attempts, not failed effects or semantic stagnation. A
                    // material change, such as a digit appearing in a display, is a visible
                    // effect even when the coarse viewport fingerprint stays the same.
                    val similarViewport = java.lang.Long.bitCount(pending.fingerprint xor fingerprint) <= policy.maxFingerprintDistance
                    val visibleEffect = materiallyChanged(pending.signature, changeSignature)
                    it.count = if (similarViewport && !visibleEffect) it.count + pending.executions else 0
                    it.latestObservationId = observationId
                    it.signature = changeSignature
                    settledKeys += pending.key
                }
            }
        }
        history.pending = emptyList()
        // An exhausted method applies to the view it was exhausted on. Once that view changed
        // materially, through another action or by itself, the method is available again.
        history.attempts.forEach { (key, attempt) ->
            if (key !in settledKeys && attempt.count > 0 && materiallyChanged(attempt.signature, changeSignature)) {
                attempt.count = 0
                attempt.signature = changeSignature
            }
        }
    }

    /**
     * A frame that shows a state already left once is a return, even when the coarse viewport
     * fingerprint never changed, such as a picker whose values move back and forth. Staying on
     * the current state is not a return; stationary attempts are counted separately.
     */
    private fun recordVisualState(history: ScopeHistory, observationId: String, signature: ScreenChangeSignature?) {
        history.revisited = null
        if (signature == null) return
        fun same(state: VisualState) = state.signature.changedFraction(signature, policy.effectChannelDelta)
            ?.let { it <= policy.revisitSameFraction } == true
        val latest = history.visualStates.lastOrNull()
        if (latest != null && same(latest)) return
        val earlier = history.visualStates.lastOrNull(::same)
        if (earlier == null) {
            history.visualStates.add(VisualState(signature, observationId, observationId))
            if (history.visualStates.size > policy.historyLimit) history.visualStates.removeAt(0)
            return
        }
        earlier.appearances++
        earlier.latestObservationId = observationId
        history.visualStates.remove(earlier)
        history.visualStates.add(earlier)
        if (earlier.appearances >= policy.revisitMinAppearances) history.revisited = earlier
    }

    private fun materiallyChanged(before: ScreenChangeSignature?, after: ScreenChangeSignature?): Boolean {
        if (before == null || after == null) return false
        val fraction = before.changedFraction(after, policy.effectChannelDelta) ?: return false
        return fraction >= policy.effectChangeFraction
    }

    private fun isSimilar(f1: Long, f2: Long): Boolean =
        java.lang.Long.bitCount(f1 xor f2) <= policy.maxFingerprintDistance

    private fun detectOscillation(transitions: List<StateTransition>): Pair<Int, Int> {
        val n = transitions.size
        if (n < 4) return 0 to 0
        for (p in listOf(2, 3)) {
            if (n < p * 2) continue
            val periodElementsDistinct = (0 until p - 1).all { offset ->
                !isSimilar(transitions[n - 1 - offset].fingerprint, transitions[n - 1 - offset - 1].fingerprint)
            }
            if (!periodElementsDistinct) continue

            var cycles = 1
            while (n >= (cycles + 1) * p) {
                val matches = (0 until p).all { offset ->
                    isSimilar(transitions[n - 1 - offset].fingerprint, transitions[n - 1 - offset - cycles * p].fingerprint)
                }
                if (matches) {
                    cycles++
                } else {
                    break
                }
            }
            if (cycles >= 2) {
                return cycles to p
            }
        }
        return 0 to 0
    }

    private fun failureActions(tool: String, arguments: String): List<JsonObject> {
        if (tool != DeviceToolNames.SCREEN_ACT) return emptyList()
        val root = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return emptyList()
        if (UnifiedDeviceToolProtocol.operation(tool, arguments) != "batch") return emptyList()
        return (root["actions"] as? JsonArray).orEmpty().mapNotNull { element ->
            val action = element as? JsonObject ?: return@mapNotNull null
            val primitive = com.droidance.core.agent.tools.ScreenActionBatchProtocol.primitive(action) ?: return@mapNotNull null
            val ref = (primitive["coordinate_ref"] as? JsonPrimitive)?.content ?: return@mapNotNull primitive
            val x = (primitive["x"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val y = (primitive["y"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val point = coordinateResolver?.invoke(ref, x, y) ?: return@mapNotNull null
            JsonObject(primitive.filterKeys { it != "coordinate_ref" } + mapOf(
                "x" to JsonPrimitive(point.x), "y" to JsonPrimitive(point.y)))
        }
    }

    private fun sameFailedMethod(left: JsonObject, right: JsonObject): Boolean {
        val operation = (left["op"] as? JsonPrimitive)?.content ?: return false
        if (left["op"] != right["op"]) return false
        val fields = policy.actions["screen_act:$operation"] ?: return false
        return fields.all { field ->
            val a = (left[field] as? JsonPrimitive)?.intOrNull
            val b = (right[field] as? JsonPrimitive)?.intOrNull
            if (field in setOf("x", "y", "startX", "startY", "endX", "endY") && a != null && b != null)
                kotlin.math.abs(a.toLong() - b.toLong()) <= policy.coordinateTolerance
            else left[field] == right[field]
        }
    }

    /** Failed delivery does not depend on image similarity: a video must not renew retries. */
    fun settled(tool: String, arguments: String, result: com.droidance.core.agent.event.ToolExecutionResult) {
        val actions = failureActions(tool, arguments)
        if (result.executionStatus == com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED &&
            (UnifiedDeviceToolProtocol.isScreenAction(tool) || tool == DeviceToolNames.ANDROID_ACT) &&
            !UnifiedDeviceToolProtocol.isObservationOnlyAction(tool, arguments)) {
            failures.removeAll { failure -> failure.scope == scope && actions.any { sameFailedMethod(failure.action, it) } }
            return
        }
        if (ToolRecovery.classify(result, policy.refreshAfterDiagnostics) != ToolRecovery.OBSERVE) return
        val attempted = result.metrics["batch_attempted_actions"]?.toInt()
        val batch = UnifiedDeviceToolProtocol.operation(tool, arguments) == "batch"
        // A one-primitive group can be rejected by the invocation's lease check before
        // the inner executor records an attempted index. Its sole method is still known.
        val failed = if (batch) attempted?.let { actions.getOrNull(it - 1) }
            ?: actions.singleOrNull() else actions.firstOrNull()
        failed ?: return
        // A target judged stale while part of the screen kept moving on its own, such as a playing
        // video, was not a failed method: the next fresh frame may ground it. Counting these would
        // exhaust a control on a video page for the whole turn.
        val movingKey = if (batch) "action_${attempted ?: 1}_pre_action_motion_moving" else "pre_action_motion_moving"
        if (result.diagnosticCode == "PRE_ACTION_OBSERVATION_CHANGED" && result.metrics[movingKey] == 1L) return
        val existing = failures.firstOrNull { it.scope == scope && sameFailedMethod(it.action, failed) }
        if (existing != null) {
            existing.count++
            existing.diagnostic = result.diagnosticCode.orEmpty()
        } else {
            if (failures.size >= policy.historyLimit) failures.removeAt(0)
            failures.add(FailedMethod(scope, failed, 1, result.diagnosticCode.orEmpty()))
        }
    }

    private fun blockedFailure(tool: String, arguments: String): FailedMethod? =
        failureActions(tool, arguments).firstNotNullOfOrNull { action ->
            failures.firstOrNull { it.scope == scope && it.count >= policy.attempts && sameFailedMethod(it.action, action) }
        }

    /** Delivery failures survive fresh frames and are facts for the same Agent's review. */
    fun failedMethodHistory(): JsonArray = JsonArray(failures.filter { it.scope == scope }.map { failed ->
        buildJsonObject {
            put("arguments", publicArguments(DeviceToolNames.SCREEN_ACT, failed.action))
            put("failed_attempts", failed.count)
            put("diagnostic_code", failed.diagnostic)
            put("attempt_limit", policy.attempts)
        }
    })

    private fun publicArguments(tool: String, action: JsonObject): JsonObject =
        if (tool == DeviceToolNames.SCREEN_ACT) buildJsonObject {
            put("actions", JsonArray(listOf(action)))
        } else action

    /** Revisited views do not establish semantic stagnation or an exhausted device method. */
    fun navigationFacts(): JsonObject {
        val history = current ?: return JsonObject(emptyMap())
        val oscillating = history.oscillationCycles >= policy.maxOscillationCycles
        val revisited = history.revisited
        if (!oscillating && revisited == null) return JsonObject(emptyMap())
        return buildJsonObject {
            put("restriction_scope", "none")
            if (oscillating) {
                put("state_oscillation_detected", true)
                put("oscillation_cycles", history.oscillationCycles)
                put("oscillation_period", history.oscillationPeriod)
                put("observation_refs", JsonArray(history.stateTransitions
                    .takeLast(history.oscillationCycles * history.oscillationPeriod)
                    .map { JsonPrimitive(it.observationId) }))
            }
            revisited?.let { state ->
                put("current_state_revisited", true)
                put("state_appearances", state.appearances)
                put("first_appearance_ref", state.firstObservationId)
                put("distinct_states_seen", history.visualStates.size)
            }
            put("goal_progress", "UNKNOWN")
        }
    }

    fun blocked(tool: String, arguments: String): Boolean {
        if (blockedFailure(tool, arguments) != null) return true
        val history = current ?: return false
        if (history.fingerprint == null || history.frameHash == null) return false
        val actionKeys = keys(history, tool, arguments)
        if (actionKeys.isEmpty()) return false
        // Navigation cycles are advisory facts for the same Agent. Only stationary
        // repeated attempts and actual failed delivery exhaust a method here.
        return actionKeys.any { (history.attempts[it.key]?.count ?: 0) >= policy.attempts }
    }

    fun recoveryFacts(tool: String, arguments: String): JsonObject {
        blockedFailure(tool, arguments)?.let { failed ->
            return buildJsonObject {
                put("restriction_scope", "method")
                put("repeated_failed_attempts", failed.count)
                put("diagnostic_code", failed.diagnostic)
                put("blocked_arguments", publicArguments(tool, failed.action))
                put("coordinate_tolerance", policy.coordinateTolerance)
                put("recovery_condition", "different_grounded_device_mutation_or_report_blocker")
            }
        }
        val history = current ?: return JsonObject(emptyMap())
        val action = keys(history, tool, arguments).maxByOrNull { history.attempts[it.key]?.count ?: 0 }
        val attempt = action?.let { history.attempts[it.key] }
        val isOscillating = history.oscillationCycles >= policy.maxOscillationCycles
        return buildJsonObject {
            put("restriction_scope", "method")
            put("repeated_visual_attempts", attempt?.count ?: history.oscillationCycles)
            put("first_observation_ref", attempt?.firstObservationId ?: history.stateTransitions.firstOrNull()?.observationId.orEmpty())
            put("latest_observation_ref", attempt?.latestObservationId ?: history.observationId.orEmpty())
            attempt?.let {
                put("first_executed_arguments", publicArguments(tool, it.firstArguments))
                put("latest_executed_arguments", publicArguments(tool, it.latestArguments))
            }
            action?.let {
                put("blocked_arguments", publicArguments(tool, it.arguments))
            }
            put("coordinate_tolerance", policy.coordinateTolerance)
            put("max_fingerprint_distance", policy.maxFingerprintDistance)
            if (isOscillating) {
                put("state_oscillation_detected", true)
                put("oscillation_cycles", history.oscillationCycles)
                put("oscillation_period", history.oscillationPeriod)
                put("action_in_observed_cycle", action?.key in history.oscillationActionKeys)
            }
            history.fingerprint?.let { currentFp ->
                attempt?.fingerprint?.let { attemptFp ->
                    put("fingerprint_distance", java.lang.Long.bitCount(attemptFp xor currentFp))
                }
            }
        }
    }

    fun executed(tool: String, arguments: String, result: com.droidance.core.agent.event.ToolExecutionResult) {
        if (UnifiedDeviceToolProtocol.isObservationOnlyAction(tool, arguments)) return
        val history = current ?: return
        val image = history.fingerprint ?: return
        val observationId = history.observationId ?: return
        val root = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return
        val actions = root["actions"] as? JsonArray
        val executedArguments = if (tool == DeviceToolNames.SCREEN_ACT && UnifiedDeviceToolProtocol.operation(tool, arguments) == "batch" && actions != null) {
            val attempted = result.metrics["batch_attempted_actions"]?.toInt()
                ?: if (actions.size == 1 && result.executionStatus != com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED) 1 else 0
            JsonObject(root + ("actions" to JsonArray(actions.take(attempted.coerceIn(0, actions.size))))).toString()
        } else arguments
        val pending = keys(history, tool, executedArguments).map { action ->
            if (action.key !in history.attempts && history.attempts.size >= policy.historyLimit) {
                history.attempts.remove(history.attempts.keys.first())
            }
            val attempt = history.attempts.getOrPut(action.key) {
                Attempt(image, observationId, action.arguments, observationId, action.arguments, history.signature)
            }
            attempt.latestArguments = action.arguments
            PendingAttempt(action.key, observationId, image, action.observationScoped, history.signature)
        }
        history.pending = (history.pending + pending).groupBy { it.key }.values.map { attempts ->
            // One before/after comparison cannot establish that every intermediate
            // viewport movement was stationary. Actual delivery failures remain separate.
            attempts.first().copy(executions = if (attempts.first().observationScoped) 1
                else attempts.sumOf { it.executions })
        }
    }

    private fun keys(history: ScopeHistory, tool: String, arguments: String): List<ActionKey> {
        val root = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return emptyList()
        if (tool == DeviceToolNames.SCREEN_ACT && UnifiedDeviceToolProtocol.operation(tool, arguments) == "batch") {
            return failureActions(tool, arguments).mapNotNull { key(history, tool, it.toString()) }
        }
        return listOfNotNull(key(history, tool, arguments))
    }

    private fun key(history: ScopeHistory, tool: String, arguments: String): ActionKey? {
        val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return null
        val effectiveTool = tool
        val effectiveArgs = args
        val op = (effectiveArgs["op"] as? JsonPrimitive)?.content
        if (op == "type_text" && effectiveArgs["target_id"] == null && effectiveArgs["x"] == null && effectiveArgs["y"] == null) {
            return null
        }
        val selector = if ("$effectiveTool:$op" in policy.actions) "$effectiveTool:$op" else effectiveTool
        val fields = policy.actions[selector] ?: return null
        val values = JsonObject(fields.associateWith { field ->
            effectiveArgs[field] ?: JsonNull
        })
        val prefix = "$selector\u0000"
        val matchedKey = history.attempts.keys.firstOrNull { stored ->
            val attempt = history.attempts.getValue(stored)
            val image = history.fingerprint
            if (!stored.startsWith(prefix) || image == null ||
                java.lang.Long.bitCount(attempt.fingerprint xor image) > policy.maxFingerprintDistance
            ) false else {
                val previous = attempt.firstArguments
                fields.all { field ->
                    val left = (previous[field] as? JsonPrimitive)?.intOrNull
                    val right = (values[field] as? JsonPrimitive)?.intOrNull
                    if (field in setOf("x", "y", "startX", "startY", "endX", "endY") && left != null && right != null) {
                        kotlin.math.abs(left.toLong() - right.toLong()) <= policy.coordinateTolerance
                    } else previous[field] == values[field]
                }
            }
        } ?: prefix + values.toString() + "\u0000" + history.fingerprint
        return ActionKey(matchedKey, values, selector in policy.observationScopedActions)
    }
}
