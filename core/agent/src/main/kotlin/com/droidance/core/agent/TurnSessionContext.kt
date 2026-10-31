// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import java.util.concurrent.ConcurrentHashMap

/**
 * Task-scoped execution context associated with a single TurnSession.
 * Encapsulates turn-level state (observed target caches, idempotency guards, completion tracking)
 * so that state lifecycle belongs to the TurnSession rather than global singletons.
 */
class TurnSessionContext(
    val turnId: String,
    val threadId: String
) {
    private var released = false
    @PublishedApi
    internal val typedComponents = ConcurrentHashMap<kotlin.reflect.KClass<*>, Any>()

    @Synchronized
    fun <T : Any> getOrPutTyped(kClass: kotlin.reflect.KClass<T>, creator: () -> T): T {
        check(!released) { "Turn session context is released" }
        @Suppress("UNCHECKED_CAST")
        return typedComponents.getOrPut(kClass, creator) as T
    }

    inline fun <reified T : Any> getOrPutTyped(noinline creator: () -> T): T =
        getOrPutTyped(T::class, creator)

    inline fun <reified T : Any> getTyped(): T? {
        @Suppress("UNCHECKED_CAST")
        return typedComponents[T::class] as? T
    }

    @Synchronized
    fun clear() {
        released = true
        // Revoke owned workers before releasing task state. Workers expose no background device channel.
        var failure: Throwable? = null
        typedComponents.values.filterIsInstance<AutoCloseable>().forEach { resource ->
            try { resource.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        typedComponents.clear()
        failure?.let { throw it }
    }
}
