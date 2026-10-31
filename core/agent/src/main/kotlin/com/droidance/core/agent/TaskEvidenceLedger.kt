// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

enum class EvidenceSourceKind {
    OBSERVED_STATE,
    GROUNDED_MODEL_OBSERVATION,
    ACTION_RECEIPT,
    MODEL_OUTPUT
}

/**
 * 当前任务的证据账本。
 *
 * 它只服务本次任务：保存观察帧、结构化事实和原文锚点。长期记忆只沉淀路径/经验，
 * 不把“当前屏幕看到的事实”写成跨会话常识，避免历史事实污染当下判断。
 */
@Singleton
class TaskEvidenceLedger @Inject constructor() {
    private data class TaskNotebookEntry(
        val observationId: String,
        val rawText: String,
        val sourceKind: EvidenceSourceKind
    )

    private val lock = Any()
    private var taskGoal: String = ""
    private var observationSeq = 0
    private var factSeq = 0
    private val observations = mutableListOf<ObservationFrame>()
    // Task-lifetime provenance stays separate from the bounded recent-frame payload cache.
    private data class ObservationReference(
        val sourceKind: EvidenceSourceKind,
        val screenshotRef: String?,
        val screenHash: String?,
        val actionCallId: String?,
        val actionReceiptId: String?
    )
    private val observationReferences = linkedMapOf<String, ObservationReference>()
    private data class DecisionImageBinding(val contentHash: String, val currentExecutionScope: String?)
    private val decisionImages = linkedMapOf<String, DecisionImageBinding>()
    private val decisionImageDetails = linkedMapOf<String, String>()
    private val factSources = linkedMapOf<String, String>()
    private val facts = mutableListOf<EvidenceFact>()
    private val taskNotebookEntries = mutableListOf<TaskNotebookEntry>()
    private val groundedObservationIds = mutableSetOf<String>()
    private var latestGroundedObservationId: String? = null
    private var pendingActionReceiptId: String? = null

    data class DerivedComputationSource(
        val sourceRef: String,
        val toolName: String,
        val inputSourceRefs: List<String> = emptyList(),
        val dataRef: String? = null,
        val outputPreview: String? = null
    )
    private val derivedSources = linkedMapOf<String, DerivedComputationSource>()

    fun startTask(goal: String) {
        synchronized(lock) {
            taskGoal = goal.trim()
            observationSeq = 0
            factSeq = 0
            observations.clear()
            observationReferences.clear()
            decisionImages.clear()
            decisionImageDetails.clear()
            factSources.clear()
            facts.clear()
            taskNotebookEntries.clear()
            groundedObservationIds.clear()
            latestGroundedObservationId = null
            pendingActionReceiptId = null
            derivedSources.clear()
        }
    }

    fun clear() {
        synchronized(lock) {
            taskGoal = ""
            observationSeq = 0
            factSeq = 0
            observations.clear()
            observationReferences.clear()
            decisionImages.clear()
            decisionImageDetails.clear()
            factSources.clear()
            facts.clear()
            taskNotebookEntries.clear()
            groundedObservationIds.clear()
            latestGroundedObservationId = null
            pendingActionReceiptId = null
            derivedSources.clear()
        }
    }

    fun recordObservation(
        observationId: String? = null,
        toolName: String,
        rawText: String,
        sourceKind: EvidenceSourceKind,
        screenHash: String? = null,
        screenshotRef: String? = null,
        foregroundPackage: String? = null,
        activity: String? = null,
        textFingerprint: LocalActionEvidence.TextFingerprint? = null,
        progressState: String? = null,
        blockerType: String? = null,
        validationReason: String? = null,
        actionCallId: String? = null,
        beforeObservationId: String? = null,
        linksNextObservation: Boolean = false,
        isCurrentState: Boolean = true
    ): ObservationFrame {
        synchronized(lock) {
            observationSeq += 1
            val grounded = sourceKind == EvidenceSourceKind.OBSERVED_STATE ||
                (
                    sourceKind == EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION &&
                        !screenshotRef.isNullOrBlank()
                    )
            val linkedActionReceiptId = pendingActionReceiptId.takeIf { grounded && isCurrentState }
            val resolvedObservationId = observationId
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: "obs_$observationSeq"
            require(resolvedObservationId !in observationReferences) {
                "Observation id already exists in the current task"
            }
            val frame = ObservationFrame(
                id = resolvedObservationId,
                toolName = toolName,
                sourceKind = sourceKind,
                timestamp = System.currentTimeMillis(),
                screenHash = screenHash ?: extractJsonField(rawText, "fingerprint"),
                rawText = rawText.take(MAX_OBSERVATION_CHARS),
                screenshotRef = screenshotRef,
                foregroundPackage = foregroundPackage
                    ?: extractForegroundPackage(rawText)
                    ?: extractJsonField(rawText, "pkg"),
                activity = activity ?: extractJsonField(rawText, "activity"),
                textFingerprint = textFingerprint,
                progressState = progressState,
                blockerType = blockerType,
                validationReason = validationReason,
                actionCallId = actionCallId,
                beforeObservationId = beforeObservationId
                    ?: latestGroundedObservationId.takeIf {
                        sourceKind == EvidenceSourceKind.ACTION_RECEIPT
                    },
                actionReceiptId = linkedActionReceiptId
            )
            observations.add(frame)
            observationReferences[frame.id] = ObservationReference(
                frame.sourceKind, frame.screenshotRef, frame.screenHash,
                frame.actionCallId, frame.actionReceiptId
            )
            if (
                frame.sourceKind in setOf(
                    EvidenceSourceKind.OBSERVED_STATE,
                    EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION
                ) &&
                !frame.screenshotRef.isNullOrBlank() &&
                frame.rawText.isNotBlank()
            ) {
                taskNotebookEntries += TaskNotebookEntry(
                    observationId = frame.id,
                    rawText = frame.rawText,
                    sourceKind = frame.sourceKind
                )
            }
            if (grounded) {
                groundedObservationIds.add(frame.id)
                if (isCurrentState) latestGroundedObservationId = frame.id
                if (linkedActionReceiptId != null) {
                    pendingActionReceiptId = null
                }
            } else if (
                frame.sourceKind == EvidenceSourceKind.ACTION_RECEIPT &&
                linksNextObservation
            ) {
                pendingActionReceiptId = frame.id
            }
            if (observations.size > MAX_OBSERVATIONS) {
                val removed = observations.removeAt(0)
                if (removed.id == pendingActionReceiptId) {
                    pendingActionReceiptId = null
                }
            }
            return frame
        }
    }

    /** Idempotent registration of image provenance; retained pages never replace current state. */
    fun registerImageEvidence(
        observationId: String,
        contentHash: String,
        scope: String,
        rawText: String,
        screenshotRef: String,
        isCurrentState: Boolean
    ): String = synchronized(lock) {
        require(observationId.isNotBlank() && scope.isNotBlank() && screenshotRef.isNotBlank())
        require(contentHash.matches(Regex("[0-9a-f]{64}")))
        val binding = DecisionImageBinding(contentHash, scope.takeIf { isCurrentState })
        decisionImages[observationId]?.let {
            require(it.contentHash == contentHash) { "Original image identity changed content" }
            if (isCurrentState) {
                require(it.currentExecutionScope == null || it.currentExecutionScope == scope) {
                    "Current image identity changed execution scope"
                }
                decisionImages[observationId] = binding
            }
            decisionImageDetails[screenshotRef] = observationId
            if (isCurrentState) latestGroundedObservationId = observationId
            return@synchronized observationId
        }
        val known = observationReferences[observationId]
        if (known != null) {
            require(known.sourceKind == EvidenceSourceKind.OBSERVED_STATE && observationId in groundedObservationIds) {
                "A decision image cannot relabel a model assertion or action receipt"
            }
        } else {
            recordObservation(observationId = observationId, toolName = "model_decision_frame",
                rawText = rawText, sourceKind = EvidenceSourceKind.OBSERVED_STATE,
                screenshotRef = screenshotRef, screenHash = contentHash, isCurrentState = isCurrentState)
        }
        decisionImages[observationId] = binding
        decisionImageDetails[screenshotRef] = observationId
        if (isCurrentState) latestGroundedObservationId = observationId
        observationId
    }

    /** Resolves only established provenance; text-only receipts have no image-review requirement. */
    fun completionImageSources(rawReferences: String): Set<String> = synchronized(lock) {
        val requested = parseEvidenceReferences(rawReferences).toMutableSet()
        requested.addAll(requested.mapNotNull { factSources[it] })
        requested.addAll(requested.mapNotNull { ref ->
            observationReferences[ref]?.takeIf { it.sourceKind == EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION }
                ?.screenshotRef?.let(decisionImageDetails::get)
        })
        decisionImages.keys.filterTo(linkedSetOf()) { id ->
            val source = observationReferences.getValue(id)
            id in requested || factSources.any { (fact, origin) -> fact in requested && origin == id } ||
                source.actionCallId in requested ||
                source.actionReceiptId?.let { observationReferences[it]?.actionCallId } in requested
        }
    }

    fun addFacts(candidates: List<EvidenceFact>): List<EvidenceFact> {
        if (candidates.isEmpty()) return emptyList()
        synchronized(lock) {
            val knownFacts = facts.mapTo(mutableSetOf(), ::factIdentity)
            val accepted = candidates
                .filter { it.text.isNotBlank() && it.verbatim.isNotBlank() && it.sourceObservationId.isNotBlank() }
                .distinctBy(::factIdentity)
                .filter { knownFacts.add(factIdentity(it)) }
                .map { candidate ->
                    factSeq += 1
                    candidate.copy(id = candidate.id.ifBlank { "fact_$factSeq" })
                }
            facts.addAll(accepted)
            accepted.forEach { factSources[it.id] = it.sourceObservationId }
            if (facts.size > MAX_FACTS) {
                val overflow = facts.size - MAX_FACTS
                repeat(overflow) { facts.removeAt(0) }
            }
            return accepted
        }
    }

    private fun factIdentity(fact: EvidenceFact): String {
        val normalizedText = fact.text.trim().lowercase().replace(WHITESPACE_REGEX, " ")
        return "${fact.type.trim().lowercase()}|$normalizedText"
    }

    fun hasEvidence(): Boolean = synchronized(lock) {
        facts.isNotEmpty() || observations.any {
            it.rawText.isNotBlank() && when (it.sourceKind) {
                EvidenceSourceKind.OBSERVED_STATE -> true
                EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION -> !it.screenshotRef.isNullOrBlank()
                else -> false
            }
        }
    }

    fun hasSourceBoundFacts(): Boolean = synchronized(lock) { facts.isNotEmpty() }

    fun latestGroundedObservation(): ObservationFrame? = synchronized(lock) {
        latestGroundedObservationId?.let { id ->
            observations.lastOrNull { it.id == id }
        }
    }

    fun observationById(observationId: String): ObservationFrame? = synchronized(lock) {
        observations.lastOrNull { it.id == observationId }
    }

    fun recordDerivedSource(
        sourceRef: String,
        toolName: String,
        inputSourceRefs: List<String> = emptyList(),
        dataRef: String? = null,
        outputPreview: String? = null
    ): String = synchronized(lock) {
        val trimmed = sourceRef.trim()
        require(trimmed.isNotBlank()) { "Derived source reference cannot be blank" }
        derivedSources[trimmed] = DerivedComputationSource(
            sourceRef = trimmed,
            toolName = toolName,
            inputSourceRefs = inputSourceRefs.map(String::trim).filter(String::isNotBlank),
            dataRef = dataRef,
            outputPreview = outputPreview
        )
        trimmed
    }

    private fun isGroundedDerivedSource(reference: String): Boolean {
        val derived = derivedSources[reference] ?: return false
        if (derived.inputSourceRefs.isEmpty()) return true
        val groundedActionAliases = groundedObservationIds.flatMap { id ->
            val observation = observationReferences[id]
            listOfNotNull(
                observation?.actionCallId,
                observation?.actionReceiptId?.let { observationReferences[it]?.actionCallId }
            )
        }.toSet()
        return derived.inputSourceRefs.all { input ->
            input in groundedObservationIds ||
                factSources[input] in groundedObservationIds ||
                input in groundedActionAliases ||
                isGroundedDerivedSource(input)
        }
    }

    /**
     * Available grounded references for model context and local projections. This inventory
     * is not a substitute for the evidence explicitly submitted by a model completion.
     */
    fun completionEvidenceReferences(): String = synchronized(lock) {
        buildList {
            factSources.filterValues { it in groundedObservationIds }.keys.forEach(::add)
            groundedObservationIds.forEach(::add)
            derivedSources.keys.filter(::isGroundedDerivedSource).forEach(::add)
        }.distinct().joinToString(",")
    }

    fun validateEvidenceReferences(rawReferences: String?): Boolean = synchronized(lock) {
        val requested = parseEvidenceReferences(rawReferences)
        if (requested.isEmpty()) return@synchronized false
        val available = observationReferences.keys + factSources.keys + derivedSources.keys
        requested.all(available::contains)
    }

    /** Completion accepts only references backed by grounded current-task provenance. */
    fun hasCompletionEvidence(rawReferences: String?): Boolean = synchronized(lock) {
        val requested = parseEvidenceReferences(rawReferences)
        requested.isNotEmpty() && unresolvedCompletionReferences(rawReferences).isEmpty()
    }

    fun unresolvedCompletionReferences(rawReferences: String?): List<String> = synchronized(lock) {
        val groundedActionAliases = groundedObservationIds.flatMap { id ->
            val observation = observationReferences.getValue(id)
            listOfNotNull(
                observation.actionCallId,
                observation.actionReceiptId?.let { observationReferences[it]?.actionCallId }
            )
        }.toSet()
        parseEvidenceReferences(rawReferences).filter { reference ->
            reference !in groundedObservationIds &&
                factSources[reference] !in groundedObservationIds &&
                reference !in groundedActionAliases &&
                !isGroundedDerivedSource(reference)
        }
    }

    /**
     * A route launch is only a transport receipt. When the route asset declares a generic
     * landing contract, require the latest grounded landing evidence in that route attempt to
     * satisfy that contract before allowing completion. Text comes from the latest grounded
     * frame; explicit Activity metadata comes from the latest grounded frame that actually carries
     * such metadata, because a later model description may omit stable runtime metadata. The
     * contract itself stays in the route catalog; this ledger only evaluates the serialized marker.
     */
    fun routeVerificationIssues(): List<String> = synchronized(lock) {
        observations.mapIndexedNotNull { receiptIndex, receipt ->
            if (receipt.progressState == "FAILED" ||
                receipt.blockerType != null ||
                receipt.rawText.contains("DEEPLINK_LAUNCH_FAILED") ||
                receipt.rawText.contains("DEEPLINK_ROUTE_FAILED")
            ) {
                return@mapIndexedNotNull null
            }
            val expectation = parseRouteExpectation(receipt.rawText) ?: return@mapIndexedNotNull null
            // A route can land on a loading skeleton before its destination content is
            // rendered. Evaluate the latest grounded frame in this route attempt, while
            // stopping at the next action receipt or route receipt so subsequent user navigations
            // do not overwrite the route's landing verification.
            val nextActionReceiptIndex = observations
                .mapIndexedNotNull { index, observation ->
                    index.takeIf {
                        index > receiptIndex &&
                            (observation.sourceKind == EvidenceSourceKind.ACTION_RECEIPT ||
                                parseRouteExpectation(observation.rawText) != null)
                    }
                }
                .firstOrNull()
                ?: observations.size
            val groundedAfter = observations
                .subList(receiptIndex + 1, nextActionReceiptIndex)
                .filter(::isGrounded)
            val latestGrounded = groundedAfter.lastOrNull()
            val latestActivityGrounded = groundedAfter
                .filter(::containsActivityMetadata)
                .lastOrNull()
            val activityMatches = expectation.activityContains.isBlank() ||
                latestActivityGrounded?.activity?.contains(expectation.activityContains, ignoreCase = true) == true ||
                latestActivityGrounded?.rawText.orEmpty().contains(expectation.activityContains, ignoreCase = true)
            when {
                latestGrounded == null ->
                    "route_id=${expectation.routeId} action=${expectation.action}: no grounded landing observation"
                !activityMatches ->
                    "route_id=${expectation.routeId} action=${expectation.action}: landing Activity did not match " +
                        expectation.activityContains
                expectation.textAny.isNotEmpty() &&
                    expectation.textAny.none { marker ->
                        latestGrounded.rawText.contains(marker, ignoreCase = true)
                    } ->
                    "route_id=${expectation.routeId} action=${expectation.action}: landing text did not contain any " +
                        expectation.textAny.joinToString(" | ")
                else -> null
            }
        }
    }

    fun observationsSnapshot(): List<ObservationFrame> = synchronized(lock) {
        observations.toList()
    }

    fun verifiedFactTexts(
        maxFacts: Int = 6,
        minimumRelevance: Double = 0.0
    ): List<String> = synchronized(lock) {
        facts.asSequence()
            .filter { it.relevance >= minimumRelevance }
            .sortedWith(
                compareByDescending<EvidenceFact> { it.relevance }.thenByDescending { it.confidence }
            )
            .take(maxFacts.coerceAtLeast(0))
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    /** Retained model-selected observations, never action receipts or a completion verdict. */
    fun partialObservationTexts(maxItems: Int = 8): List<String> = synchronized(lock) {
        taskNotebookEntries.asReversed().asSequence()
            .filter { it.sourceKind == EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION }
            .filter { !observationReferences[it.observationId]?.screenshotRef.isNullOrBlank() }
            .flatMap { it.rawText.lineSequence().map(String::trim).filter(String::isNotBlank) }
            .distinct()
            .take(maxItems.coerceAtLeast(0))
            .toList()
    }

    fun buildCoordinatorContext(maxFacts: Int = 40, maxChars: Int = 7000): String {
        val snapshot = synchronized(lock) {
            EvidenceSnapshot(
                taskGoal = taskGoal,
                observations = observations.toList(),
                facts = facts.toList()
            )
        }
        return snapshot.toPrompt(maxFacts = maxFacts, maxChars = maxChars)
    }

    fun buildOperatorEvidenceText(
        maxFacts: Int = 30,
        maxChars: Int = 5000,
        minimumRelevance: Double = 0.0
    ): String {
        val snapshot = synchronized(lock) {
            EvidenceSnapshot(
                taskGoal = taskGoal,
                observations = observations.toList(),
                facts = facts.filter { it.relevance >= minimumRelevance }
            )
        }
        return snapshot.toPrompt(maxFacts = maxFacts, maxChars = maxChars, includeGoal = false)
    }

    /**
     * Bounded active-loop view of the task note. The newest grounded stage is emitted first so a
     * long task cannot keep feeding the model only its earliest pages after the character budget
     * fills. Retained EventStore observations remain available for full final/audit synthesis.
     */
    fun buildRecentTaskResearchNote(maxChars: Int = 12_000): String {
        if (maxChars <= 0) return ""
        val selectedEntries = synchronized(lock) {
            taskNotebookEntries.asReversed().filter {
                it.sourceKind == EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION
            }
        }
        val seen = linkedSetOf<String>()
        val entries = selectedEntries.flatMap { entry ->
            ObservationTextExtractor.extract(entry.rawText)
                .map(String::trim)
                .filter(String::isNotBlank)
                .filter { seen.add(it.lowercase().replace(WHITESPACE_REGEX, " ")) }
                .map { line ->
                    val sources = completionImageSources(entry.observationId).joinToString(",")
                    "- [${entry.observationId}] original_sources=[$sources] $line"
                }
        }
        if (entries.isEmpty()) return ""

        return buildString {
            appendLine("model_authored_task_research_note_latest_first:")
            for (entry in entries) {
                if (length + entry.length + 100 > maxChars) {
                    appendLine("[TRUNCATED: older findings remain in task history; retrieve originals before completion.]")
                    break
                }
                appendLine(entry)
            }
        }.trimEnd().take(maxChars)
    }

    fun wrapInternalEvidence(result: String, maxChars: Int = 5000): String {
        val evidence = buildCoordinatorContext(maxChars = maxChars)
        if (evidence.isBlank()) return result
        return buildString {
            append(result.trimEnd())
            appendLine()
            appendLine()
            appendLine(INTERNAL_EVIDENCE_START)
            appendLine(evidence)
            appendLine(INTERNAL_EVIDENCE_END)
        }
    }

    fun stripInternalEvidence(text: String): String {
        val start = text.indexOf(INTERNAL_EVIDENCE_START)
        if (start < 0) return text.trim()
        val end = text.indexOf(INTERNAL_EVIDENCE_END, start)
        val before = text.substring(0, start)
        val after = if (end >= 0) text.substring(end + INTERNAL_EVIDENCE_END.length) else ""
        return (before + after).trim()
    }

    /** Treat a malformed reference-like token as invalid instead of silently dropping it. */
    private fun parseEvidenceReferences(rawReferences: String?): Set<String> =
        if (rawReferences.isNullOrBlank()) emptySet()
        else rawReferences.split(',').map(String::trim).toSet()

    companion object {
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private const val ROUTE_EXPECTATION_PREFIX = "[DEEPLINK_ROUTE_EXPECTATION]"
        const val INTERNAL_EVIDENCE_START = "[[DROIDANCE_TASK_EVIDENCE]]"
        const val INTERNAL_EVIDENCE_END = "[[/DROIDANCE_TASK_EVIDENCE]]"

        private const val MAX_OBSERVATION_CHARS = 50_000
        private const val MAX_OBSERVATIONS = 30
        private const val MAX_FACTS = 480
    }

    private fun extractJsonField(rawText: String, field: String): String? {
        return Regex(""""$field"\s*:\s*"([^"]+)"""")
            .find(rawText)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractForegroundPackage(rawText: String): String? {
        val foreground = Regex(
            """(?im)\bForeground:\s*([A-Za-z0-9._-]+)(?:/|\s|$)"""
        ).find(rawText)?.groupValues?.getOrNull(1)
        if (!foreground.isNullOrBlank()) return foreground
        return Regex("""(?im)\bforeground=([A-Za-z0-9._-]+)""")
            .find(rawText)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(String::isNotBlank)
    }

    private fun isGrounded(observation: ObservationFrame): Boolean =
        observation.sourceKind == EvidenceSourceKind.OBSERVED_STATE ||
            (observation.sourceKind == EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION &&
                !observation.screenshotRef.isNullOrBlank())

    private fun containsActivityMetadata(observation: ObservationFrame): Boolean =
        !observation.activity.isNullOrBlank() || ACTIVITY_METADATA_REGEX.containsMatchIn(observation.rawText)

    private fun parseRouteExpectation(rawText: String): RouteExpectation? {
        val line = rawText.lineSequence()
            .firstOrNull { it.trimStart().startsWith(ROUTE_EXPECTATION_PREFIX) }
            ?: return null
        val fields = line.substringAfter(ROUTE_EXPECTATION_PREFIX)
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
            }
            .toMap()
        val routeId = decode(fields["route_id"].orEmpty()).takeIf(String::isNotBlank) ?: return null
        val action = decode(fields["action"].orEmpty())
        val activity = decode(fields["activity_contains"].orEmpty())
        val textAny = fields["text_any"].orEmpty()
            .split(',')
            .map { decode(it) }
            .filter(String::isNotBlank)
        return RouteExpectation(routeId, action, activity, textAny)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    private val ACTIVITY_METADATA_REGEX = Regex(
        "(?i)(?:foreground|activity|component)\\s*[:=]"
    )
}

private data class RouteExpectation(
    val routeId: String,
    val action: String,
    val activityContains: String,
    val textAny: List<String>
)

data class ObservationFrame(
    val id: String,
    val toolName: String,
    val timestamp: Long,
    val screenHash: String?,
    val rawText: String,
    val sourceKind: EvidenceSourceKind = EvidenceSourceKind.OBSERVED_STATE,
    val screenshotRef: String? = null,
    val foregroundPackage: String? = null,
    val activity: String? = null,
    val textFingerprint: LocalActionEvidence.TextFingerprint? = null,
    val progressState: String? = null,
    val blockerType: String? = null,
    val validationReason: String? = null,
    val actionCallId: String? = null,
    val beforeObservationId: String? = null,
    val actionReceiptId: String? = null
)

data class EvidenceFact(
    val id: String = "",
    val type: String,
    val text: String,
    val verbatim: String,
    val sourceObservationId: String,
    /** Exact-text extraction score, not confidence that the source or interpretation is true. */
    val confidence: Double,
    val relevance: Double
)

data class EvidenceSnapshot(
    val taskGoal: String,
    val observations: List<ObservationFrame>,
    val facts: List<EvidenceFact>
) {
    fun toPrompt(maxFacts: Int, maxChars: Int, includeGoal: Boolean = true): String {
        val groundingObservations = observations.filter {
            it.sourceKind == EvidenceSourceKind.OBSERVED_STATE ||
                (it.sourceKind == EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION && !it.screenshotRef.isNullOrBlank())
        }
        if (groundingObservations.isEmpty() && facts.isEmpty()) return ""
        val text = buildString {
            // Structural labels only. The rule about what may be cited from this ledger lives in
            // the evidence contract in rules/agent_core.md; restating it here would put a
            // behavioural rule back into Kotlin.
            if (includeGoal) {
                appendLine("task_goal:")
                appendLine(taskGoal.ifBlank { "unrecorded" })
                appendLine()
            }

            appendLine("source_bound_excerpts:")
            val selectedFacts = facts.sortedWith(
                compareByDescending<EvidenceFact> { it.relevance }.thenByDescending { it.confidence }
            ).take(maxFacts)
            if (selectedFacts.isEmpty()) {
                appendLine("none")
            } else {
                selectedFacts.forEach { fact ->
                    appendLine("- [${fact.id}] ${fact.type}: ${fact.text}")
                    appendLine("  verbatim=${fact.verbatim}")
                    appendLine(
                        "  source=${fact.sourceObservationId} " +
                            "source_kind=${observations.firstOrNull { it.id == fact.sourceObservationId }?.sourceKind?.name ?: "UNAVAILABLE"}"
                    )
                }
            }

            appendLine()
            appendLine("recent_observations:")
            groundingObservations.takeLast(6).forEach { obs ->
                appendLine("- [${obs.id}] tool=${obs.toolName} screenHash=${obs.screenHash ?: "unknown"} screenshotRef=${obs.screenshotRef ?: "none"} progress=${obs.progressState ?: "unknown"} foreground=${obs.foregroundPackage ?: "unknown"}")
                if (!obs.validationReason.isNullOrBlank()) {
                    appendLine("  local_validation=${obs.validationReason}")
                }
                val preview = ObservationTextExtractor.extract(obs.rawText)
                    .takeIf { it.isNotEmpty() }
                    ?.take(10)
                    ?.joinToString(" / ")
                    ?.take(700)
                    ?: obs.rawText
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .take(10)
                        .joinToString(" / ")
                        .take(700)
                appendLine("  preview=$preview")
            }
        }.trim()
        return text.take(maxChars)
    }
}
