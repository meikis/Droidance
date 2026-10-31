// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import android.animation.ValueAnimator
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun AnimatedMascot(
    modifier: Modifier = Modifier,
    framing: MascotFraming = MascotFraming.PORTRAIT,
    mood: MascotMood = MascotMood.WELCOME
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val painter = remember(context) { MascotPainter(context) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var transition by remember { mutableStateOf(MascotTransition(mood)) }
    var reducedMotion by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    LaunchedEffect(owner, mood) {
        transition = transition.retarget(elapsed, mood)
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            if (!reducedMotion) {
                var previous = 0L
                while (true) {
                    withFrameNanos { now ->
                        if (previous != 0L) elapsed += (now - previous) / 1_000_000L
                        previous = now
                    }
                }
            }
        }
    }
    Canvas(modifier) {
        val pose = transition.pose(elapsed, reducedMotion)
        painter.draw(drawContext.canvas.nativeCanvas, RectF(0f, 0f, size.width, size.height),
            framing, pose)
    }
}
