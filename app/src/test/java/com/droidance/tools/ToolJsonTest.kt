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
import org.junit.Assert.assertNull
import org.junit.Test

class ToolJsonTest {
    @Test
    fun strictBooleanAcceptsOnlyJsonBooleans() {
        val json = Json.parseToJsonElement(
            """{"true_value":true,"false_value":false,"string_value":"true","number_value":1}"""
        ).jsonObject

        assertEquals(true, json.strictBooleanOrNull("true_value"))
        assertEquals(false, json.strictBooleanOrNull("false_value"))
        assertNull(json.strictBooleanOrNull("string_value"))
        assertNull(json.strictBooleanOrNull("number_value"))
        assertNull(json.strictBooleanOrNull("missing"))
    }
}
