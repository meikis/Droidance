// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.droidance.ui.theme.DroidanceBackground
import com.droidance.ui.theme.DroidanceLightBackground

fun Modifier.appGradientBackground(isDark: Boolean): Modifier = background(
    color = if (isDark) DroidanceBackground else DroidanceLightBackground
)

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false
) {
    DroidanceDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        dismissLabel = dismissLabel,
        onDismiss = onDismiss,
        destructive = destructive,
        modifier = modifier
    )
}
