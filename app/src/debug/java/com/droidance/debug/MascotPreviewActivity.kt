// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.droidance.core.device.agent.device.CursorMotion
import com.droidance.core.device.tools.InputAction
import com.droidance.ui.mascot.*
import com.droidance.ui.theme.LocalAppIsDark
import com.droidance.ui.overlay.TouchIndicatorView
import com.droidance.ui.overlay.VirtualSpaceHandCue
import kotlinx.coroutines.delay

/** Debug-only visual rehearsal. It never dispatches input or calls a model. */
class MascotPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = !intent.getBooleanExtra("light", false)
            CompositionLocalProvider(LocalAppIsDark provides dark) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    var mood by remember { mutableStateOf(MascotMood.WELCOME) }
                    var hand by remember { mutableStateOf<TouchIndicatorView?>(null) }
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            Text("Droidance", style = MaterialTheme.typography.headlineMedium)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                AnimatedMascot(Modifier.size(152.dp), MascotFraming.FULL, mood)
                                AnimatedMascot(Modifier.size(88.dp), MascotFraming.PORTRAIT, mood)
                                AnimatedMascot(Modifier.size(36.dp), MascotFraming.HEAD, mood)
                            }
                            Text("同一个机器人，自然回应", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { mood = MascotMood.WELCOME }) { Text("欢迎") }
                                TextButton(onClick = { mood = MascotMood.THINKING }) { Text("思考") }
                                TextButton(onClick = { mood = MascotMood.OPERATING }) { Text("操作") }
                                TextButton(onClick = { mood = MascotMood.TAKEOVER }) { Text("接管") }
                            }
                            AndroidView(factory = { TouchIndicatorView(it).also { view -> hand = view } },
                                modifier = Modifier.fillMaxWidth().height(240.dp).background(
                                    if (dark) Color(0xff171720) else Color(0xffeeeeF8)))
                        }
                    }
                    LaunchedEffect(hand) {
                        val view = hand ?: return@LaunchedEffect
                        delay(200)
                        while (true) {
                            val now = CursorMotion.nowMs()
                            view.showCue(VirtualSpaceHandCue("tap-$now", 0,
                                InputAction.Tap(view.width / 3, view.height / 3), now))
                            delay(70)
                            view.showCue(VirtualSpaceHandCue("tap-$now", 0,
                                InputAction.Tap(view.width / 3, view.height / 3), now, CursorMotion.nowMs()))
                            delay(1700)
                            val start = CursorMotion.nowMs()
                            view.showCue(VirtualSpaceHandCue("swipe-$start", 0,
                                InputAction.Swipe(view.width / 3, view.height * 2 / 3,
                                    view.width * 2 / 3, view.height / 3, 600), start))
                            delay(600)
                            view.showCue(VirtualSpaceHandCue("swipe-$start", 0,
                                InputAction.Swipe(view.width / 3, view.height * 2 / 3,
                                    view.width * 2 / 3, view.height / 3, 600), start, CursorMotion.nowMs()))
                            delay(1700)
                        }
                    }
                }
            }
        }
    }
}
