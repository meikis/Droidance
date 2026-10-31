// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import com.droidance.ui.theme.AppIconSize
import com.droidance.ui.components.droidanceFloatingSurfaceColor
import com.droidance.ui.theme.AppShapes
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.droidance.R
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppMotion
import com.droidance.ui.screens.settingsScrollbar
import com.droidance.ui.viewmodel.ComposerModelOption
import com.droidance.ui.viewmodel.ComposerPreferences

@Composable
internal fun ComposerSelectionControls(
    modelOptions: List<ComposerModelOption>,
    preferences: ComposerPreferences,
    enabled: Boolean,
    onOpenPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onModelSelected: (ComposerModelOption?) -> Unit,
    modifier: Modifier = Modifier,
    onPickerClosed: () -> Unit = {}
) {
    var showModels by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) { showModels = false }
    }
    LaunchedEffect(showModels) {
        if (!showModels) onPickerClosed()
    }
    val autoLabel = stringResource(R.string.composer_model_auto)
    val activeModel = preferences.model ?: modelOptions.firstOrNull()
    val modelLabel = activeModel?.displayName ?: autoLabel
    val modelDescription = stringResource(
        R.string.composer_model_description,
        activeModel?.modelId ?: autoLabel
    )
    val popupWidth = (LocalConfiguration.current.screenWidthDp - 32).coerceIn(180, 288).dp

    Row(
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f, fill = false)) {
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .clip(AppShapes.Control)
                    .clickable(enabled = enabled, role = Role.Button) {
                        if (!showModels) {
                            onOpenPicker()
                            onOpenModelPicker()
                        }
                        showModels = !showModels
                    }
                    .semantics { contentDescription = modelDescription }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    modelLabel,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val arrowRotation by animateFloatAsState(if (showModels) 180f else 0f,
                    tween(AppMotion.ContentMillis, easing = AppMotion.Settle), label = "modelMenuArrow")
                Icon(
                    DroidanceIcons.ChevronDown,
                    null,
                    Modifier.size(12.dp).rotate(arrowRotation),
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ComposerDropdown(showModels, { showModels = false }, popupWidth) {
                ComposerMenuHeader(stringResource(R.string.composer_model_title)) { showModels = false }
                val modelScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .settingsScrollbar(modelScrollState, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                        .verticalScroll(modelScrollState)
                ) {
                    modelOptions.forEach { option ->
                        ComposerChoiceRow(
                            title = option.modelId,
                            subtitle = option.providerName,
                            selected = (option == activeModel),
                            enabled = enabled && showModels,
                            onClick = { onModelSelected(option); showModels = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    width: Dp,
    content: @Composable () -> Unit
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState && visibility.isIdle) return
    var opensAbove by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val marginPx = with(density) { 16.dp.roundToPx() }
    val gapPx = with(density) { 6.dp.roundToPx() }
    val position = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val left = if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.right - popupContentSize.width
                } else anchorBounds.left
                val yAbove = anchorBounds.top - gapPx - popupContentSize.height
                opensAbove = yAbove >= marginPx
                val y = if (yAbove >= marginPx) {
                    yAbove
                } else {
                    (anchorBounds.bottom + gapPx).coerceAtMost(windowSize.height - popupContentSize.height - marginPx)
                }
                return IntOffset(
                    left.coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)),
                    y.coerceIn(marginPx, (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
                )
            }
        }
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(120).dp
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = position,
        properties = PopupProperties(focusable = expanded)
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(AppMotion.FeedbackMillis)) + scaleIn(
                tween(AppMotion.ContentMillis, easing = AppMotion.Settle), initialScale = 0.98f,
                transformOrigin = TransformOrigin(1f, if (opensAbove) 1f else 0f)),
            exit = fadeOut(tween(AppMotion.PressMillis)) + scaleOut(
                tween(AppMotion.FeedbackMillis), targetScale = 0.98f,
                transformOrigin = TransformOrigin(1f, if (opensAbove) 1f else 0f))
        ) {
            Surface(
                modifier = Modifier.width(width),
                shape = AppShapes.Large,
                color = droidanceFloatingSurfaceColor(),
                tonalElevation = 0.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .padding(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ComposerMenuHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(DroidanceIcons.Close, stringResource(R.string.composer_picker_close), Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ComposerChoiceRow(
    title: String,
    subtitle: String = "",
    selected: Boolean,
    enabled: Boolean,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.Control)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) Icon(DroidanceIcons.Check, null, Modifier.size(AppIconSize.Inline)) else Spacer(Modifier.size(AppIconSize.Inline))
    }
}
