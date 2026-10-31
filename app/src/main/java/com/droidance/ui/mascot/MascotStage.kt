// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.ui.theme.LocalAppIsDark

/** A complete character with breathing room around its dance, never a cropped torso. */
@Composable
fun MascotStage(modifier: Modifier = Modifier, mood: MascotMood = MascotMood.DANCING) {
    val accent = MaterialTheme.colorScheme.primary
    val isDark = LocalAppIsDark.current
    val description = stringResource(R.string.settings_about_app_icon)
    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .5f, size.height * .86f)
            // A quiet pool of light anchors the feet without a hard badge or cut edge.
            withTransform({
                translate(center.x, center.y)
                scale(1f, .20f, pivot = Offset.Zero)
            }) {
                val radius = size.width * .34f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = if (isDark) .16f else .08f), Color.Transparent),
                        center = Offset.Zero, radius = radius
                    ),
                    radius = radius, center = Offset.Zero
                )
            }
        }
        AnimatedMascot(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            framing = MascotFraming.FULL,
            mood = mood
        )
    }
}
