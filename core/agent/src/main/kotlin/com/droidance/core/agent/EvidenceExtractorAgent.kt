// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 任务证据抽取器。
 *
 * 它不是摘要器：输出必须绑定原文片段，且原文片段必须能在工具结果里找到。
 * 这样最终回答可以引用证据，而不是引用模型改写后的“看起来合理”的总结。
 */
@Singleton
class EvidenceFactExtractor @Inject constructor() {
    fun extractInline(_taskGoal: String, observation: ObservationFrame): List<EvidenceFact> {
        val raw = observation.rawText.trim()
        if (!shouldExtract(observation, raw)) return emptyList()
        return finalizeFacts(extractDeterministically(observation), raw)
    }

    suspend fun extract(_taskGoal: String, observation: ObservationFrame): List<EvidenceFact> {
        val raw = observation.rawText.trim()
        if (!shouldExtract(observation, raw)) return emptyList()
        return finalizeFacts(extractDeterministically(observation), raw)
    }

    private fun finalizeFacts(candidates: List<EvidenceFact>, raw: String): List<EvidenceFact> =
        candidates
            .mapNotNull { validateFact(it, raw) }
            .distinctBy { "${it.type}|${it.text}|${it.verbatim}" }
            .take(MAX_FACTS_PER_OBSERVATION)

    private fun shouldExtract(observation: ObservationFrame, raw: String): Boolean {
        if (observation.sourceKind == EvidenceSourceKind.GROUNDED_MODEL_OBSERVATION) {
            return !observation.screenshotRef.isNullOrBlank() &&
                raw.length >= 2 &&
                ObservationTextExtractor.extract(raw).isNotEmpty()
        }
        if (observation.sourceKind != EvidenceSourceKind.OBSERVED_STATE) return false
        return ObservationTextExtractor.extract(raw).isNotEmpty()
    }

    private fun extractDeterministically(observation: ObservationFrame): List<EvidenceFact> {
        val raw = observation.rawText
        return ObservationTextExtractor.extract(raw)
            .asSequence()
            .map { line ->
                EvidenceFact(
                    type = "text",
                    text = line.take(160),
                    verbatim = line,
                    sourceObservationId = observation.id,
                    confidence = 1.0,
                    relevance = 1.0
                )
            }
            .take(24)
            .toList()
    }

    private fun validateFact(fact: EvidenceFact, raw: String): EvidenceFact? {
        val verbatim = fact.verbatim.trim()
        if (verbatim.length < 2) return null
        if (!raw.contains(verbatim)) return null

        val cleanedText = fact.text.trim().ifBlank { verbatim }
        return fact.copy(
            type = fact.type.ifBlank { "text" }.take(40),
            text = cleanedText.take(180),
            verbatim = verbatim.take(240),
            confidence = fact.confidence.coerceIn(0.0, 1.0),
            relevance = fact.relevance.coerceIn(0.0, 1.0)
        )
    }

    companion object {
        private const val MAX_FACTS_PER_OBSERVATION = 24
    }
}
