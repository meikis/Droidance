// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Per-invocation diagnostics. Nested stage totals overlap; they are not additive wall time. */
class ObservationTiming : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ObservationTiming> {
        suspend fun current(): ObservationTiming? = coroutineContext[Key]
        suspend fun <T> measure(stage: String, block: suspend () -> T): T {
            val collector = current() ?: return block()
            val started = System.nanoTime()
            return try { block() } finally { collector.add(stage, (System.nanoTime() - started) / 1_000_000) }
        }
    }
    private val values = mutableMapOf<String, Long>()
    @Synchronized fun add(stage: String, value: Long) { values[stage] = (values[stage] ?: 0) + value }
    @Synchronized fun snapshot(): Map<String, Long> = values.toMap()
    @Synchronized fun recordRendering(policy: String, result: VisualFrameSampler.Result) {
        val sequence = (values["render_capture_count"] ?: 0L) + 1
        values["render_capture_count"] = sequence
        val prefix = "render_${sequence}_${policy.removeSuffix("_capture_policy")}_"
        values[prefix + "stable"] = if (result.stable) 1 else 0
        values[prefix + "localized_motion"] = if (result.localizedMotion) 1 else 0
        values[prefix + "wait_ms"] = result.elapsedMs
        result.diagnostics.forEach { (key, value) -> values[prefix + key] = value }
    }
}
