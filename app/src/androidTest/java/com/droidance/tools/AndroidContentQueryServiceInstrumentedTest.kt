// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidance.core.device.permission.PermissionBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real app-UID ContentProvider read; no backend, screenshot, or permission approval is involved. */
@RunWith(AndroidJUnit4::class)
class AndroidContentQueryServiceInstrumentedTest {
    @Test
    fun readsSettingsProviderAndMatchesThePlatformSettingsApi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissionEvents = mutableListOf<String>()
        val broker = PermissionBroker(context) { requestId ->
            error("Settings provider should not request a runtime permission: $requestId")
        }
        val service = AndroidContentQueryService(
            context = context,
            permissionBroker = broker,
            permissionOwner = { "settings-turn" to "settings-call" },
            permissionReason = { "test:$it" },
            permissionTimeoutMillis = { 0L },
            onPermissionWaiting = { permissionEvents += "waiting:${it.requestId}" },
            onPermissionResolved = { _, outcome ->
                permissionEvents += "resolved:$outcome"
            }
        )
        val settingName = Settings.System.SCREEN_BRIGHTNESS
        val expected = Settings.System.getString(context.contentResolver, settingName)

        val result = service.query(
            """{"uri":"content://settings/system","projection":["name","value"],"selection":"name = ?","selection_args":["$settingName"],"limit":1}"""
        )

        assertEquals(com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertTrue(permissionEvents.isEmpty())
        val payload = Json.parseToJsonElement(result.outputText.substringAfter("\n")).jsonObject
        val row = payload.getValue("rows").jsonArray.single().jsonObject
        assertEquals(settingName, row.getValue("name").jsonPrimitive.content)
        assertEquals(expected, row["value"]?.jsonPrimitive?.contentOrNull)
    }
}
