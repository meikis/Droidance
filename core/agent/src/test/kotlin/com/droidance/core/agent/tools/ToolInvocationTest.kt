// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.junit.Assert.*
import org.junit.Test

class ToolInvocationTest {
    @Test
    fun interleavedCallbacksKeepTheirOwnCallIdentity() = runBlocking {
        val firstReady = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        suspend fun receipt() = withContext(Dispatchers.Default) {
            coroutineContext[ToolInvocation]?.callId
        }
        val first = async(ToolInvocation("first")) {
            firstReady.complete(Unit)
            secondReady.await()
            receipt()
        }
        val second = async(ToolInvocation("second")) {
            firstReady.await()
            secondReady.complete(Unit)
            receipt()
        }
        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertNull(coroutineContext[ToolInvocation])
    }

    @Test
    fun cancelledNestedInvocationRestoresCallerIdentity() = runBlocking {
        withContext(ToolInvocation("outer")) {
            try {
                withContext(ToolInvocation("inner")) {
                    yield()
                    assertEquals("inner", coroutineContext[ToolInvocation]?.callId)
                    throw CancellationException("cancel inner")
                }
            } catch (_: CancellationException) {
                assertEquals("outer", coroutineContext[ToolInvocation]?.callId)
            }
        }
        assertNull(coroutineContext[ToolInvocation])
    }
}
