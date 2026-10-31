// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.view.SurfaceControl
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serializes surface/lifecycle changes, including detach after an in-flight attach. */
internal class VirtualSpaceViewerMirror(
    private val manager: VirtualSpaceManager,
    private val onResult: (SidecarSessionBinding?, Boolean) -> Unit
) {
    data class Target(val binding: SidecarSessionBinding, val parent: SurfaceControl, val width: Int, val height: Int,
        val nativeImeEnabled: Boolean)
    private data class Request(val target: Target?, val revision: Long)
    private val owner = UUID.randomUUID().toString()
    private val lifetime = android.os.Binder()
    private val revision = AtomicLong()
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var closed = false

    init {
        scope.launch {
            var installed: Target? = null
            try {
                for (request in requests) {
                    val target = request.target
                    val previous = installed
                    if (previous != null && (target == null || previous.binding != target.binding)) {
                        manager.detachViewerMirror(previous.binding, owner)
                        installed = null
                    }
                    val ok = if (target == null) false else {
                        val attached = target == installed || manager.attachViewerMirror(target.binding, target.parent, target.width, target.height, owner, lifetime)
                        installed = target.takeIf { attached }
                        val ready = attached && manager.setViewerImeEnabled(target.binding, target.nativeImeEnabled, owner, lifetime)
                        if (!ready && attached) {
                            manager.detachViewerMirror(target.binding, owner)
                            installed = null
                        }
                        ready
                    }
                    withContext(Dispatchers.Main) {
                        if (!closed && request.revision == revision.get()) onResult(target?.binding, ok)
                    }
                }
            } finally {
                installed?.let { manager.detachViewerMirror(it.binding, owner) }
                scope.cancel()
            }
        }
    }

    fun update(target: Target?) {
        if (!closed) requests.trySend(Request(target, revision.incrementAndGet()))
    }

    fun close() {
        closed = true
        revision.incrementAndGet()
        // Closing a conflated channel drains its final request before the worker exits.
        requests.trySend(Request(null, revision.get()))
        requests.close()
    }
}
