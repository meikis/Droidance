// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.debug

/** Stack locations and cause types are sufficient to locate failures without copying secrets from messages. */
fun DebugLogAppender.recordRuntimeFailure(reference: String, context: String, error: Throwable) {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val trace = buildString {
        appendLine("reference=$reference $context")
        var cause: Throwable? = error
        while (cause != null && seen.size < 8 && seen.add(cause)) {
            appendLine(cause.javaClass.name)
            cause.stackTrace.take(32).forEach { appendLine("  at $it") }
            cause = cause.cause
        }
    }
    appendPersistentEvent("runtime-failures", trace)
}
