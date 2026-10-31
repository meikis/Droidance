// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.settings.AppThemeMode
import com.droidance.ui.theme.DroidanceTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises real Compose windows and callbacks without changing connectivity or invoking a model. */
class DeviceConnectionPromptTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun label(id: Int) = instrumentation.targetContext.getString(id)

    @Test fun laterAndReopeningPreserveTheDraftAndTheRecoveryAction() {
        val state = mutableStateOf(DeviceConnectionUiState()
            .observe(DeviceConnectionStatus.WIRELESS_DISABLED, 0L)
            .observe(DeviceConnectionStatus.WIRELESS_DISABLED, 5_000L))
        val draft = mutableStateOf("Keep my draft")
        var confirmed = 0
        compose.setContent {
            DroidanceTheme(AppThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        BasicTextField(draft.value, { draft.value = it },
                            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("draft"),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
                        Spacer(Modifier.weight(1f))
                        DeviceConnectionNotice(state.value) { state.value = state.value.showDetails() }
                    }
                    if (state.value.dialogVisible) DeviceConnectionDialog(state.value.status,
                        onDismiss = { state.value = state.value.dismiss() },
                        onConfirm = { confirmed++; state.value = state.value.dismiss() })
                }
            }
        }
        compose.onNodeWithText(label(R.string.connection_open_settings)).assertIsDisplayed()
        screenshot("dialog-dark")
        compose.onNodeWithText(label(R.string.connection_later)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("draft").assertTextContains("Keep my draft").performTextInput(" more")
        compose.runOnIdle { state.value = state.value.observe(DeviceConnectionStatus.WIRELESS_DISABLED, 6_000L) }
        compose.onNode(isDialog()).assertDoesNotExist()
        screenshot("notice-dark")
        compose.onNodeWithText(label(R.string.connection_resolve)).performClick()
        compose.onNodeWithText(label(R.string.connection_open_settings)).performClick()
        compose.runOnIdle {
            assertEquals(1, confirmed)
            assertTrue(draft.value.contains("Keep my draft"))
            assertTrue(draft.value.contains(" more"))
        }
    }

    @Test fun repairingActionsStayVisibleWithLargeTextInLightTheme() {
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.8f)) {
                    Surface {
                        DeviceConnectionDialog(DeviceConnectionStatus.PAIRING_REQUIRED, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText(label(R.string.settings_air_repair)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.connection_later)).assertIsDisplayed()
        screenshot("dialog-large-text-light")
    }

    @Test fun reconnectingStaysInlineAndDisappearsOnRecovery() {
        val state = mutableStateOf(DeviceConnectionUiState().observe(DeviceConnectionStatus.RECONNECTING, 0L))
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                Surface { DeviceConnectionNotice(state.value) { state.value = state.value.showDetails() } }
            }
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.connection_reconnecting_title)).assertIsDisplayed()
        screenshot("reconnecting-light")
        compose.runOnIdle { state.value = state.value.observe(DeviceConnectionStatus.READY, 1_000L) }
        compose.onNodeWithText(label(R.string.connection_reconnecting_title)).assertDoesNotExist()
    }

    @Test fun wifiWarningsRequireConfirmedLossAndShortLossesStayInvisible() {
        val state = mutableStateOf(DeviceConnectionUiState())
        compose.setContent {
            DroidanceTheme(AppThemeMode.LIGHT) {
                Surface {
                    DeviceConnectionNotice(state.value) { state.value = state.value.showDetails() }
                    if (state.value.dialogVisible) DeviceConnectionDialog(state.value.status,
                        onDismiss = { state.value = state.value.dismiss() }, onConfirm = {})
                }
            }
        }
        for ((time, status) in listOf(
            0L to DeviceConnectionStatus.CHECKING,
            1_000L to DeviceConnectionStatus.WIFI_UNAVAILABLE,
            5_999L to DeviceConnectionStatus.WIFI_UNAVAILABLE,
            6_000L to DeviceConnectionStatus.READY,
            10_000L to DeviceConnectionStatus.WIFI_UNAVAILABLE,
            14_999L to DeviceConnectionStatus.WIFI_UNAVAILABLE
        )) {
            compose.runOnIdle { state.value = state.value.observe(status, time) }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText(label(R.string.connection_wifi_title)).assertDoesNotExist()
        }
        compose.runOnIdle {
            state.value = state.value.observe(DeviceConnectionStatus.WIFI_UNAVAILABLE, 15_000L)
        }
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.connection_open_wifi)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.observe(DeviceConnectionStatus.READY, 15_001L) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.connection_wifi_title)).assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Capture the actual composition rather than an OEM window's in-flight dim animation.
        val node = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(isDialog())
        } else compose.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "connection-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}
