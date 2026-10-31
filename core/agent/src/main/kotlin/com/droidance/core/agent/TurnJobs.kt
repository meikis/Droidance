// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.coroutines.Job

/** Cancellation is owned by jobs, never a sticky runtime-wide boolean. */
class TurnJobs {
    private val lock = Any()
    private val registered = linkedMapOf<String, Job>()
    private var running: Pair<String, Job>? = null

    val runningJob: Job? get() = synchronized(lock) { running?.second }

    fun register(id: String, job: Job) = synchronized(lock) { registered[id] = job }

    fun begin(id: String, job: Job) = synchronized(lock) {
        check(running == null) { "A Turn is already running" }
        running = id to job
        registered[id] = job
    }

    fun release(id: String, expectedJob: Job) = synchronized(lock) {
        if (registered[id] === expectedJob) registered.remove(id)
        if (running?.first == id && running?.second === expectedJob) running = null
    }

    fun cancellationTargets(id: String?): List<Job> = synchronized(lock) {
        val targets = if (id == null) registered.values.toList() else listOfNotNull(registered[id])
        val active = running?.takeIf { id == null || it.first == id }?.second
        if (id == null) registered.clear() else registered.remove(id)
        (targets + listOfNotNull(active)).distinct()
    }
}
