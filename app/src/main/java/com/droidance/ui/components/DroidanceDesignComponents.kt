// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.compose.foundation.layout.Arrangement
import com.droidance.ui.theme.LightFloating
import com.droidance.ui.theme.LocalAppIsDark
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun DroidancePanel(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.Control,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Large floating layers (drawer, sheets, dialogs, popovers) are the top step. Light mode keeps them one
 * small step above the cards and short of pure white, so no large area glares.
 */
@Composable
fun droidanceFloatingSurfaceColor(darkColor: Color = MaterialTheme.colorScheme.surface): Color =
    if (LocalAppIsDark.current) darkColor else LightFloating

/**
 * Text field colors. Light mode fills the field with a faint ink wash over whatever it sits on and shows
 * an outline only on focus; dark mode keeps the surface fill with a hairline.
 */
@Composable
fun droidanceFieldColors(): TextFieldColors {
    val ink = MaterialTheme.colorScheme.onSurface
    return if (LocalAppIsDark.current) OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ink.copy(alpha = 0.4f),
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        cursorColor = ink
    ) else OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ink.copy(alpha = 0.3f),
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedContainerColor = ink.copy(alpha = 0.045f),
        unfocusedContainerColor = ink.copy(alpha = 0.045f),
        disabledContainerColor = ink.copy(alpha = 0.03f),
        errorContainerColor = ink.copy(alpha = 0.045f),
        cursorColor = ink
    )
}

/** 全局开关配色：开启 = 当前主题主色，未开启 = 可见的中性灰轨道。 */
@Composable
fun droidanceSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
    uncheckedBorderColor = Color.Transparent
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DroidanceDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    maxWidth: Dp = 360.dp,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DroidancePanel(
            modifier = modifier
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .heightIn(max = maxHeight)
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
            shape = AppShapes.Card,
            containerColor = droidanceFloatingSurfaceColor(),
            contentPadding = PaddingValues(vertical = 18.dp)
        ) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!text.isNullOrBlank()) Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
                content?.invoke(this)
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (dismissLabel != null) {
                    TextButton(enabled = dismissEnabled, onClick = { (onDismiss ?: onDismissRequest)() }) {
                        Text(dismissLabel, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (dismissEnabled) 1f else 0.38f))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(enabled = confirmEnabled, onClick = onConfirm) {
                    Text(
                        confirmLabel,
                        color = if (!confirmEnabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun DroidanceDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier,
        scrollState = scrollState, shape = AppShapes.Large,
        containerColor = droidanceFloatingSurfaceColor(), tonalElevation = 0.dp,
        shadowElevation = 4.dp, border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content)
}

@Composable
fun DroidanceSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    Snackbar(snackbarData = data, modifier = modifier, shape = AppShapes.Card,
        containerColor = droidanceFloatingSurfaceColor(), contentColor = MaterialTheme.colorScheme.onSurface,
        actionColor = MaterialTheme.colorScheme.primary, dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
}
