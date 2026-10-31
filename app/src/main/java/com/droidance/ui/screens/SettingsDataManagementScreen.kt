// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.DroidanceSnackbar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidance.R
import com.droidance.ui.viewmodel.SettingsViewModel
import com.droidance.ui.viewmodel.UsageRange

// ── 数据管理（用量 + 导出 + 清理）───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataManagementScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val total = uiState.totalPromptTokens + uiState.totalCompletionTokens
    var pendingClear by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.dataActionMessage) {
        val message = uiState.dataActionMessage.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeDataActionMessage()
    }

    if (pendingExport) {
        com.droidance.ui.components.GlassAlertDialog(
            onDismissRequest = { pendingExport = false },
            title = stringResource(R.string.settings_export_debug_title),
            text = stringResource(R.string.settings_export_debug_message),
            confirmLabel = stringResource(R.string.settings_export_share),
            onConfirm = {
                pendingExport = false
                viewModel.exportDebugPackage()
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismiss = { pendingExport = false }
        )
    }

    pendingClear?.let { kind ->
        val isConversations = kind == "conversations"
        com.droidance.ui.components.GlassAlertDialog(
            onDismissRequest = { pendingClear = null },
            title = stringResource(if (isConversations) R.string.settings_clear_conversations_title else R.string.settings_clear_logs_title),
            text = stringResource(if (isConversations) R.string.settings_clear_conversations_message else R.string.settings_clear_logs_message),
            confirmLabel = stringResource(R.string.settings_clear_confirm),
            destructive = true,
            onConfirm = {
                if (isConversations) viewModel.clearAllConversations() else viewModel.clearLogData()
                pendingClear = null
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismiss = { pendingClear = null }
        )
    }

    val rangeTitle = stringResource(
        when (uiState.usageRange) {
            UsageRange.SEVEN_DAYS -> R.string.settings_usage_7_days
            UsageRange.THIRTY_DAYS -> R.string.settings_usage_30_days
            UsageRange.ALL -> R.string.settings_usage_all
        }
    )
    val cacheHitRate = if (uiState.cacheReportedCalls == 0L) {
        "—"
    } else if (uiState.cacheReportedInputTokens == 0L) {
        "0%"
    } else {
        String.format(
            java.util.Locale.getDefault(),
            "%.1f%%",
            uiState.cachedInputTokens * 100.0 / uiState.cacheReportedInputTokens
        )
    }

    SubScreenScaffold(
        title = stringResource(R.string.settings_data_controls),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) { DroidanceSnackbar(it) } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item { UsageSectionHeader(stringResource(R.string.settings_usage_range)) }
            item {
                SettingsCardGroup {
                    UsageRangeSelector(
                        selected = uiState.usageRange,
                        onSelect = viewModel::loadTokenStats
                    )
                }
            }
            item { UsageSectionHeader(rangeTitle) }
            item {
                SettingsCardGroup {
                    UsageBarChart(
                        daily = uiState.dailyUsage,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
            item { UsageSectionHeader(stringResource(R.string.settings_period_usage)) }
            item {
                SettingsCardGroup {
                    UsageValueItem(stringResource(R.string.settings_total_calls), stringResource(R.string.settings_calls, uiState.totalCalls))
                    SettingsInsetDivider()
                    UsageValueItem(stringResource(R.string.settings_prompt_tokens), formatTokens(uiState.totalPromptTokens))
                    SettingsInsetDivider()
                    UsageValueItem(stringResource(R.string.settings_completion_tokens), formatTokens(uiState.totalCompletionTokens))
                    SettingsInsetDivider()
                    UsageValueItem(stringResource(R.string.settings_cache_hit_rate), cacheHitRate)
                    SettingsInsetDivider()
                    UsageValueItem(stringResource(R.string.settings_total_tokens), formatTokens(total))
                }
            }
            if (uiState.modelUsage.isNotEmpty()) {
                item { UsageSectionHeader(stringResource(R.string.settings_model_usage)) }
                item {
                    SettingsCardGroup {
                        uiState.modelUsage.forEachIndexed { index, usage ->
                            if (index > 0) SettingsInsetDivider()
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = usage.model.ifBlank { usage.provider },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (usage.provider.isNotBlank() && usage.provider != usage.model && usage.model.isNotBlank()) {
                                    Text(
                                        text = usage.provider,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.settings_total_tokens),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatTokens(usage.total),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.settings_total_calls),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_calls, usage.calls),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { UsageSectionHeader(stringResource(R.string.settings_data_management)) }
            item {
                SettingsCardGroup {
                    SettingsActionItem(
                        title = stringResource(R.string.settings_export_debug_package),
                        subtitle = stringResource(R.string.settings_export_debug_detail),
                        actionText = stringResource(R.string.settings_export_action),
                        enabled = !uiState.debugExporting && uiState.dataClearing == null,
                        loading = uiState.debugExporting,
                        onClick = { pendingExport = true }
                    )
                    SettingsInsetDivider()
                    SettingsActionItem(
                        title = stringResource(R.string.settings_clear_logs),
                        subtitle = stringResource(R.string.settings_clear_logs_detail),
                        actionText = stringResource(R.string.settings_clear_confirm),
                        enabled = !uiState.debugExporting && uiState.dataClearing == null,
                        loading = uiState.dataClearing == com.droidance.ui.viewmodel.DataClearTarget.LOGS,
                        onClick = { pendingClear = "logs" }
                    )
                    SettingsInsetDivider()
                    SettingsActionItem(
                        title = stringResource(R.string.settings_clear_conversations),
                        subtitle = stringResource(R.string.settings_clear_conversations_detail),
                        actionText = stringResource(R.string.settings_clear_confirm),
                        enabled = !uiState.debugExporting && uiState.dataClearing == null,
                        loading = uiState.dataClearing == com.droidance.ui.viewmodel.DataClearTarget.CONVERSATIONS,
                        onClick = { pendingClear = "conversations" }
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(horizontal = 32.dp)
            .padding(top = 28.dp, bottom = 8.dp)
    )
}

@Composable
private fun UsageValueItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}
