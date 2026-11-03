// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.droidance.core.knowledge.skill.SkillInfo
import com.droidance.ui.components.droidanceFieldColors
import com.droidance.ui.components.droidanceFloatingSurfaceColor
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppShapes

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComposerSkillReferences(
    skills: List<ComposerSkillReference>,
    enabled: Boolean,
    onOpen: (ComposerSkillReference) -> Unit,
    onRemove: (ComposerSkillReference) -> Unit
) {
    if (skills.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        skills.forEach { skill ->
            Surface(shape = AppShapes.Control, color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("@${skill.title}", style = MaterialTheme.typography.labelLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable(enabled = enabled) { onOpen(skill) }
                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
                    if (skill.draft) Text(stringResource(R.string.composer_skill_draft),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
                    IconButton(onClick = { onRemove(skill) }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                        Icon(DroidanceIcons.Close, stringResource(R.string.skill_remove_selection), Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/** A non-focusable mention popup leaves the editor and its IME session in control. */
@Composable
internal fun ComposerSkillPicker(
    skills: List<SkillInfo>,
    selectedKeys: Set<String>,
    language: String,
    width: Dp,
    maxHeight: Dp,
    query: String,
    manual: Boolean,
    highlightedIndex: Int,
    onQueryChanged: (String) -> Unit,
    onSelect: (SkillInfo) -> Unit,
    onManage: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        val margin = with(density) { 12.dp.roundToPx() }
        val gap = with(density) { 8.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val left = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
                    else anchorBounds.right - popupContentSize.width
                return IntOffset(
                    left.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(margin)
                )
            }
        }
    }
    Popup(popupPositionProvider = positionProvider, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = manual)) {
        Surface(Modifier.width(width).testTag("composer-skill-picker"), shape = AppShapes.Large,
            color = droidanceFloatingSurfaceColor(), tonalElevation = 0.dp, shadowElevation = 4.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (manual) IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(DroidanceIcons.ArrowBack, stringResource(R.string.common_back), Modifier.size(16.dp))
                    }
                    Text(stringResource(R.string.composer_choose_skill), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = if (manual) 0.dp else 4.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(DroidanceIcons.Close, stringResource(R.string.composer_picker_close), Modifier.size(16.dp))
                    }
                }
                if (manual) {
                    val searchFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    OutlinedTextField(query, onValueChange = onQueryChanged, singleLine = true,
                        placeholder = { Text(stringResource(R.string.composer_skill_search_hint)) },
                        leadingIcon = { Icon(DroidanceIcons.Search, null, Modifier.size(18.dp)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                            .testTag("composer-skill-search"),
                        shape = AppShapes.Control, colors = droidanceFieldColors())
                }
                if (skills.isEmpty()) {
                    Text(stringResource(if (query.isBlank()) R.string.composer_no_skills else R.string.composer_no_skill_results),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 20.dp))
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(highlightedIndex, manual) {
                        if (!manual) listState.animateScrollToItem(highlightedIndex)
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = (maxHeight - if (manual) 144.dp else 84.dp).coerceAtLeast(64.dp)),
                        state = listState) {
                        itemsIndexed(skills, key = { _, skill -> skill.selectionKey }) { index, skill ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(Modifier.fillMaxWidth()
                                .background(if (!manual && highlightedIndex == index) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { onSelect(skill) }.padding(horizontal = 4.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(skill.displayName(language), style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        if (skill.isDraft) Text(stringResource(R.string.composer_skill_draft),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 6.dp))
                                    }
                                    Text(skill.displayDescription(language), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(8.dp))
                                Icon(if (skill.selectionKey in selectedKeys) DroidanceIcons.Check else DroidanceIcons.Add,
                                    null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                TextButton(onClick = onManage) {
                    Text(stringResource(R.string.skill_manage), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerSkillDetails(skill: ComposerSkillReference, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = droidanceFloatingSurfaceColor()) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(skill.title, style = MaterialTheme.typography.titleLarge)
            if (skill.draft) Text(stringResource(R.string.composer_skill_draft), style = MaterialTheme.typography.labelMedium)
            Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (skill.parameters.isNotEmpty()) {
                Text(stringResource(R.string.skill_parameters), style = MaterialTheme.typography.labelLarge)
                Text(skill.parameters.keys.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
