// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.droidance.config.TokenUsageTotals
import com.droidance.ui.theme.LocalAppMotionEnabled

internal fun tokenCountLabel(tokens: Long, reported: Boolean): String = when {
    !reported -> "—"
    tokens >= 1_000_000 -> "%.2fM".format(tokens / 1_000_000.0)
    else -> "%.1fk".format(tokens.coerceAtLeast(0) / 1_000.0)
}

@Composable
internal fun TokenUsageLabel(usage: TokenUsageTotals, modifier: Modifier = Modifier) {
    val target = usage.totalTokens.toFloat()
    // Restored history starts at its recorded value; subsequent updates continue from the visible count.
    val count = remember { Animatable(target) }
    val motionEnabled = LocalAppMotionEnabled.current
    LaunchedEffect(target, motionEnabled) {
        if (motionEnabled) count.animateTo(target, tween(900, easing = LinearOutSlowInEasing))
        else count.snapTo(target)
    }
    Text(
        tokenCountLabel(count.value.toLong(), usage.hasReportedTokens),
        modifier = modifier.padding(start = 10.dp).testTag("chat-token-usage"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
