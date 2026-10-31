// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppIconSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import com.droidance.R
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.ui.components.FlatMarkdown
import com.droidance.ui.components.executionActionDisplayArguments
import com.droidance.ui.components.executionActionLabel
import com.droidance.ui.components.executionActionResource
import java.text.NumberFormat
import java.time.ZoneId

internal object ExecutionLogLayout {
    val HorizontalPadding = 16.dp
    val MarkerWidth = 8.dp
    val TextGap = 8.dp
    /** Two steps below body text, matching the 12sp header; the dot marker centres on its first line. */
    val EntryTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp)
}

/** The timeline retains public narration; selecting a row opens its own secondary surface. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExecutionProcessHistory(
    activity: TurnActivityReadModel,
    progressHistory: List<PublicProgressReadModel>,
    toolDetails: Map<String, ToolDetailReadModel>,
    roundDetails: Map<String, ModelRoundDetailReadModel> = emptyMap(),
    loadAttachment: suspend (DetailAttachmentReadModel) -> ByteArray?,
    modifier: Modifier = Modifier
) {
    val nodes = remember(activity, progressHistory) {
        EventStoreExecutionTimelinePresenter.present(activity, progressHistory)
    }
    var selectedId by remember(activity.turnId) { mutableStateOf<String?>(null) }
    val selected = nodes.firstOrNull { it.id == selectedId }
    if (selected != null) {
        key(selected.id) {
            ExecutionDetailSheet(onDismissRequest = { selectedId = null }) {
                ExecutionProcessDetail(selected, activity, toolDetails, roundDetails, loadAttachment,
                    onClose = { selectedId = null })
            }
        }
    }
    val row: @Composable (Int, ExecutionProgressNode) -> Unit = { index, node ->
        ExecutionProgressRow(node, hasDetails = node.calls.isNotEmpty() ||
            node.roundIds.any { it in activity.executionRoundTimings } ||
            node.roundIds.any { id -> roundDetails[id]?.let { it.finishMetadata.containsKey("model_thought") || it.finishMetadata.containsKey("reasoning") } == true },
            onOpen = { selectedId = node.id },
            isFirst = index == 0, isLast = index == nodes.lastIndex)
    }
    Column(modifier.testTag("execution-process-history")) {
        if (nodes.size > 8) {
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(nodes, key = { _, node -> node.id }) { index, node -> row(index, node) }
                }
            }
        } else {
            nodes.forEachIndexed { index, node -> key(node.id) { row(index, node) } }
        }
    }
}

@Composable
private fun ExecutionProgressRow(node: ExecutionProgressNode, hasDetails: Boolean, onOpen: () -> Unit, isFirst: Boolean, isLast: Boolean) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val openLabel = stringResource(R.string.execution_open_details)
    Row(Modifier.fillMaxWidth().drawBehind {
        val x = (ExecutionLogLayout.MarkerWidth / 2).toPx()
        val y = 14.5.dp.toPx()
        if (!isFirst) drawLine(lineColor, Offset(x, 0f), Offset(x, y), 1.dp.toPx())
        if (!isLast) drawLine(lineColor, Offset(x, y), Offset(x, size.height), 1.dp.toPx())
    }) {
        Box(Modifier.width(ExecutionLogLayout.MarkerWidth).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(dotColor))
        }
        Spacer(Modifier.width(ExecutionLogLayout.TextGap))
        Row(
            Modifier.weight(1f).testTag("execution-progress-row:${node.id}")
                .then(if (hasDetails) Modifier.clickable(onClickLabel = openLabel, onClick = onOpen) else Modifier)
                .padding(top = 6.dp, bottom = if (isLast) 6.dp else 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            val title = node.text.ifBlank {
                timelineActionTitle(node.calls.firstOrNull()?.presentation?.actionTitle)
                    ?: stringResource(executionActionResource(
                        node.calls.first().presentation.labelResourceKey.removePrefix("tool.")))
            }
            // The log is secondary to the reply, so it reads one step below body text.
            FlatMarkdown(title, Modifier.weight(1f).testTag("execution-progress-history-entry"),
                textStyle = ExecutionLogLayout.EntryTextStyle)
            if (hasDetails) Icon(DroidanceIcons.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = 6.dp, top = 0.5.dp).size(AppIconSize.Inline))
        }
    }
}

@Composable
private fun ExecutionProcessDetail(
    node: ExecutionProgressNode,
    activity: TurnActivityReadModel,
    toolDetails: Map<String, ToolDetailReadModel>,
    roundDetails: Map<String, ModelRoundDetailReadModel> = emptyMap(),
    loadAttachment: suspend (DetailAttachmentReadModel) -> ByteArray?,
    onClose: () -> Unit
) {
    val records = remember(node, toolDetails, activity.programShellCalls) {
        executionRecords(node, toolDetails, activity.programShellCalls)
    }
    val thoughts = remember(node, roundDetails) {
        node.roundIds.mapNotNull { roundId ->
            roundDetails[roundId]?.finishMetadata?.get("model_thought")
                ?: roundDetails[roundId]?.finishMetadata?.get("reasoning")
        }.filter { it.isNotBlank() }
    }
    val images = remember(records) { records.flatMap { it.detail?.attachments.orEmpty() } }
    val isRunning = executionProcessTiming(node, activity, System.currentTimeMillis()).state == ExecutionProcessTimingState.RUNNING
    // Only the open sheet ticks. Elapsed updates use a monotonic clock after the initial wall-time anchor.
    val nowMs by produceState(initialValue = System.currentTimeMillis(), key1 = node.id, key2 = isRunning) {
        val wallAnchor = System.currentTimeMillis()
        val monotonicAnchor = System.nanoTime()
        do {
            value = wallAnchor + (System.nanoTime() - monotonicAnchor) / 1_000_000L
            if (isRunning) kotlinx.coroutines.delay(1000)
        } while (isRunning)
    }
    var gallery by remember(node.id) { mutableStateOf<ExecutionGallerySelection?>(null) }
    gallery?.let { selection ->
        ExecutionImageGallery(selection, loadAttachment, onClose = { gallery = null })
    }
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
        .testTag("execution-detail-sheet").padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.execution_detail_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(DroidanceIcons.Close, contentDescription = stringResource(R.string.execution_close_details))
            }
        }
        if (node.text.isNotBlank()) {
            FlatMarkdown(node.text, Modifier.fillMaxWidth().padding(bottom = 10.dp),
                textStyle = MaterialTheme.typography.bodySmall)
        }
        Text(executionProcessTimingText(executionProcessTiming(node, activity, nowMs), nowMs),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 18.dp).testTag("execution-process-timing"))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("execution-detail-records"),
            contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (thoughts.isNotEmpty()) {
                item(key = "thinking_process") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        thoughts.forEach { thought ->
                            ThinkingProcessCard(thought)
                        }
                    }
                }
            }
            items(records.sortedBy { record -> record.detail?.attachments?.any { it.historical } == true },
                key = { it.activity.callId }) { record ->
                if (record === records.firstOrNull { it.detail?.attachments?.any { image -> image.historical } == true }) {
                    Text(stringResource(R.string.execution_historical_evidence), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExecutionCallDetail(record, loadAttachment,
                    onImage = { attachment ->
                        gallery = ExecutionGallerySelection(images, images.indexOf(attachment).coerceAtLeast(0))
                    })
            }
        }
    }
}

@Composable
private fun ThinkingProcessCard(thoughtText: String) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("thinking-process-card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.execution_detail_thinking_process),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) DroidanceIcons.ChevronUp else DroidanceIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    FlatMarkdown(
                        thoughtText,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ExecutionCallDetail(
    record: ExecutionCallRecord,
    loadAttachment: suspend (DetailAttachmentReadModel) -> ByteArray?,
    onImage: (DetailAttachmentReadModel) -> Unit
) {
    val detail = record.detail
    val title = timelineActionTitle(record.activity.presentation.actionTitle)
    val actionLabels = if (detail != null) {
        executionActionDisplayArguments(detail.technicalName, detail.argumentsJson).map {
            executionActionLabel(detail.technicalName.removeSuffix(".batch"), it)
        }
    } else if (title != null) emptyList() else listOf(
        stringResource(executionActionResource(record.activity.presentation.labelResourceKey.removePrefix("tool."))))
    Column(Modifier.fillMaxWidth().testTag("execution-call:${record.activity.callId}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (record.invocation != null && detail != null) {
            Text(title ?: stringResource(executionActionResource(detail.technicalName, detail.argumentsJson)),
                style = MaterialTheme.typography.bodyMedium)
            ExecutionInvocationDetail(record.invocation)
        } else if (detail?.technicalName == "shell") {
            record.commands.forEach { command -> key(command.id) { ExecutionCommandDetail(command) } }
        } else if (record.commands.isNotEmpty()) {
            ExecutionCommandsDetail(record)
        } else {
            if (title != null && title !in actionLabels) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
            }
            actionLabels.forEach { label ->
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Native receipts already show their returned output; GUI receipts explain partial execution.
        if (record.invocation == null && record.commands.isEmpty()) {
            ExecutionOutcomeSummary(record)
        }
        val attachments = detail?.attachments.orEmpty()
        attachments.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { attachment ->
                    ExecutionImageThumbnail(attachment, executionImageCaption(attachment), loadAttachment,
                        onClick = { onImage(attachment) }, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1 && attachments.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExecutionInvocationDetail(invocation: ExecutionInvocationRecord) {
    Text(stringResource(R.string.execution_call), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    SelectionContainer {
        Text(invocation.call, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
    CommandOutput(R.string.execution_command_stdout, invocation.output.orEmpty(), false)
    if (invocation.output != null && invocation.output.isEmpty()) {
        Text(stringResource(R.string.execution_command_empty_output), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExecutionCommandsDetail(record: ExecutionCallRecord) {
    var expanded by rememberSaveable(record.activity.callId) { mutableStateOf(false) }
    val toggleLabel = stringResource(if (expanded) R.string.execution_collapse_command else R.string.execution_expand_command)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().testTag("execution-command-toggle:${record.activity.callId}")
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(executionActionResource(record.detail?.technicalName
                ?: record.activity.presentation.labelResourceKey.removePrefix("tool."))),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(if (expanded) DroidanceIcons.ChevronUp else DroidanceIcons.ChevronDown,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
        if (expanded) {
            record.commands.forEach { command -> key(command.id) { ExecutionCommandDetail(command) } }
        }
    }
}

@Composable
private fun ExecutionCommandDetail(command: ExecutionCommandRecord) {
    Column(Modifier.fillMaxWidth().testTag("execution-command:${command.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer {
            Text(command.command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        CommandOutput(R.string.execution_command_stdout, command.stdout, command.stdoutTruncated)
        CommandOutput(R.string.execution_command_stderr, command.stderr, command.stderrTruncated)
        if (command.stdout.isEmpty() && command.stderr.isEmpty() && !command.stdoutTruncated && !command.stderrTruncated) {
            Text(stringResource(R.string.execution_command_empty_output), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CommandOutput(label: Int, text: String, truncated: Boolean) {
    if (text.isEmpty() && !truncated) return
    Text(stringResource(label), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (text.isNotEmpty()) SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
    if (truncated) Text(stringResource(R.string.execution_command_truncated),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun executionStartTime(startedAt: Long, nowMs: Long): String =
    formatExecutionStartTime(startedAt, nowMs, ZoneId.systemDefault(), LocalConfiguration.current.locales[0])

@Composable
private fun executionProcessTimingText(timing: ExecutionProcessTiming, nowMs: Long): String =
    when {
        timing.startedAt != null && timing.durationMs != null -> stringResource(
            R.string.execution_process_timing, executionStartTime(timing.startedAt, nowMs),
            executionDuration(timing.durationMs))
        timing.startedAt != null && timing.state == ExecutionProcessTimingState.PAUSED -> stringResource(
            R.string.execution_process_paused, executionStartTime(timing.startedAt, nowMs))
        else -> stringResource(R.string.execution_process_duration_unrecorded)
    }

@Composable
private fun executionDuration(durationMs: Long): String {
    return when {
        durationMs < 100L -> stringResource(R.string.execution_duration_subsecond)
        else -> {
            val number = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply {
                maximumFractionDigits = 1
                isGroupingUsed = false
            }.format(durationMs / 1000.0)
            stringResource(R.string.execution_duration_seconds, number)
        }
    }
}
