// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.program

import com.droidance.core.device.program.*

import com.droidance.program.*

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProgramWorkerInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun isolatedWorkerComputesAndRequestsAndroidOnlyThroughHost() = runBlocking {
        val connection = ProgramConnection(context)
        try {
            connection.start("var r=android.call('android_read',{op:'device'});print(r.value*2);", 10000)
            val identity = Json.parseToJsonElement(connection.identity).jsonObject
            assertTrue(identity.getValue("isolated").jsonPrimitive.boolean)
            assertNotEquals(Process.myUid(), identity.getValue("uid").jsonPrimitive.int)
            val call = withContext(Dispatchers.IO) { connection.requests.poll(5, TimeUnit.SECONDS) }!!
            assertEquals("android", call.operation)
            // Pending calls cannot perform device effects without the host's active pump.
            delay(150)
            assertFalse(call.answer.isDone)
            call.answer.complete("{\"value\":{\"value\":21}}")
            val output = withContext(Dispatchers.IO) { connection.requests.poll(5, TimeUnit.SECONDS) }!!
            assertEquals("stdout", output.operation)
            assertTrue(output.arguments.contains("42"))
            output.answer.complete("{\"value\":null}")
            withTimeout(5000) { while (connection.status == "running") delay(20) }
            assertEquals("exited", connection.status)
        } finally { connection.close() }
    }
    @Test fun cancellationKillsInfiniteWorkerAndNextRunHasFreshGlobals() = runBlocking {
        val old = ProgramConnection(context)
        try {
            old.start("var secret=123; while(true){}", 10000)
            old.close()
            assertEquals("canceled", old.status)
            val fresh = ProgramConnection(context)
            try {
                fresh.start("if(typeof secret!=='undefined'||typeof Packages!=='undefined')throw Error('leak');print('fresh');", 10000)
                val output = withContext(Dispatchers.IO) { fresh.requests.poll(5, TimeUnit.SECONDS) }!!
                assertTrue(output.arguments.contains("fresh"))
                output.answer.complete("{\"value\":null}")
                withTimeout(5000) { while (fresh.status == "running") delay(20) }
                assertEquals("exited", fresh.status)
            } finally { fresh.close() }
        } finally { old.close() }
    }
    @Test fun timeoutStopsComputationWithoutHostCalls() = runBlocking {
        val connection = ProgramConnection(context)
        try {
            connection.start("while(true){}", 200)
            withTimeout(5000) { while (connection.status == "running") delay(20) }
            assertEquals("timed_out", connection.status)
            assertTrue(connection.requests.isEmpty())
        } finally { connection.close() }
    }
}
