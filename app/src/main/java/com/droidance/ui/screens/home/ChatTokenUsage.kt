// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droidance.R
import com.droidance.config.TokenUsageTotals
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.screens.formatTokens
import com.droidance.ui.theme.AppShapes

internal fun tokenCountLabel(tokens: Long, reported: Boolean, complete: Boolean): String =
    if (!reported) "—" else formatTokens(tokens) + if (complete) "" else "+"

@Composable
internal fun TokenUsageButton(
    usage: TokenUsageTotals,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    incomplete: Boolean = false,
    showChevron: Boolean = false
) {
    val label = stringResource(R.string.chat_token_count,
        tokenCountLabel(usage.totalTokens, usage.hasReportedTokens, usage.isComplete && !incomplete))
    val description = stringResource(R.string.chat_token_usage_open, label)
    Row(
        modifier = modifier
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showChevron) Icon(DroidanceIcons.ChevronRight, contentDescription = null,
            modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TokenUsageSheet(
    usage: TokenUsageTotals,
    conversation: Boolean,
    onDismiss: () -> Unit,
    incomplete: Boolean = false,
    running: Boolean = false
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AppShapes.Large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .testTag("chat-token-usage-sheet")
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (conversation) R.string.chat_token_usage_conversation else R.string.chat_token_usage_title),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(DroidanceIcons.Close, stringResource(R.string.common_close), Modifier.size(20.dp))
                }
            }
            Text(
                stringResource(R.string.chat_token_count,
                    tokenCountLabel(usage.totalTokens, usage.hasReportedTokens, usage.isComplete && !incomplete && !running)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = MaterialTheme.typography.displayLarge.fontWeight,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp).testTag("chat-token-usage-total")
            )
            UsageRow(stringResource(R.string.chat_token_usage_input),
                tokenCountLabel(usage.inputTokens, usage.inputReportedCalls > 0,
                    usage.inputReportedCalls == usage.calls && !incomplete && !running))
            UsageRow(stringResource(R.string.chat_token_usage_cached),
                tokenCountLabel(usage.cachedInputTokens, usage.cacheReportedCalls > 0,
                    usage.cacheReportedCalls == usage.calls && !incomplete && !running), nested = true)
            UsageRow(stringResource(R.string.settings_completion_tokens),
                tokenCountLabel(usage.outputTokens, usage.outputReportedCalls > 0,
                    usage.outputReportedCalls == usage.calls && !incomplete && !running))
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            UsageRow(stringResource(R.string.settings_total_calls),
                if (usage.calls == 0L) "—" else stringResource(R.string.settings_calls, usage.calls))
            val note = when {
                running -> R.string.chat_token_usage_running
                !usage.hasReportedTokens -> R.string.chat_token_usage_unavailable
                !usage.isComplete || incomplete -> R.string.chat_token_usage_incomplete
                else -> null
            }
            if (note != null) Text(stringResource(note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String, nested: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (nested) 12.dp else 0.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val style = if (nested) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
        val color = if (nested) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        Text(label, style = style, color = color, modifier = Modifier.weight(1f))
        Text(value, style = style, color = color)
    }
}
