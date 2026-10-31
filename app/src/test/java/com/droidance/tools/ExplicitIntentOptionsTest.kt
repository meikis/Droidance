// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitIntentOptionsTest {
    @Test
    fun parsesAndBuildsTypedAndroidIntentArguments() {
        val json = Json.parseToJsonElement(
            """{
                "categories":["android.intent.category.DEFAULT"],
                "mime_type":"text/plain",
                "string_extras":{"android.intent.extra.TEXT":"hello world"},
                "int_extras":{"android.intent.extra.alarm.HOUR":9},
                "long_extras":{"beginTime":1786467600000},
                "boolean_extras":{"android.intent.extra.alarm.SKIP_UI":true},
                "expect_screen_change":false,
                "completion_evidence":"platform_contract"
            }"""
        ).jsonObject

        val options = ExplicitIntentOptions.parse(json)
        val arguments = options.toShellArguments(::quote)

        assertEquals(
            " -c 'android.intent.category.DEFAULT' -t 'text/plain'" +
                " --es 'android.intent.extra.TEXT' 'hello world'" +
                " --ei 'android.intent.extra.alarm.HOUR' 9" +
                " --el 'beginTime' 1786467600000" +
                " --ez 'android.intent.extra.alarm.SKIP_UI' true",
            arguments
        )
        assertEquals(false, options.expectScreenChange)
        assertEquals(NativeCompletionEvidence.PLATFORM_CONTRACT, options.completionEvidence)
        assertEquals("NATIVE_CONTRACT_ACCEPTED", options.receiptMarker())
        assertEquals(
            "string_extras={android.intent.extra.TEXT=hello world}; " +
                "int_extras={android.intent.extra.alarm.HOUR=9}; " +
                "long_extras={beginTime=1786467600000}; " +
                "boolean_extras={android.intent.extra.alarm.SKIP_UI=true}",
            options.receiptSummary().substringAfter("mime_type=text/plain; ")
        )
    }

    @Test
    fun rejectsAnExtraWithTheWrongScalarType() {
        val json = Json.parseToJsonElement(
            """{"int_extras":{"android.intent.extra.alarm.HOUR":"9"}}"""
        ).jsonObject

        assertThrows(IllegalArgumentException::class.java) {
            ExplicitIntentOptions.parse(json)
        }
    }

    @Test
    fun rejectsUnknownCompletionEvidence() {
        val json = Json.parseToJsonElement(
            """{"completion_evidence":"screen_guess"}"""
        ).jsonObject

        assertThrows(IllegalArgumentException::class.java) {
            ExplicitIntentOptions.parse(json)
        }
    }

    @Test
    fun defaultScreenChangeExpectationDoesNotTurnPackageLaunchIntoExplicitIntent() {
        val json = Json.parseToJsonElement(
            """{"expect_screen_change":true}"""
        ).jsonObject

        assertTrue(ExplicitIntentOptions.parse(json).isEmpty())
    }

    @Test
    fun falseScreenChangeExpectationRemainsAnExplicitNativeOption() {
        val json = Json.parseToJsonElement(
            """{"expect_screen_change":false}"""
        ).jsonObject

        assertEquals(false, ExplicitIntentOptions.parse(json).isEmpty())
    }

    private fun quote(value: String): String = "'$value'"
}
