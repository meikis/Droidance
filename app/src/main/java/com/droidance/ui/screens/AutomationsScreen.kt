// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFieldColors
import com.droidance.ui.components.DroidanceDialog
import com.droidance.ui.components.DroidanceDropdownMenu

import androidx.activity.compose.BackHandler
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidance.R
import com.droidance.automation.*
import com.droidance.ui.components.FlatMarkdown
import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.viewmodel.AutomationDraft
import com.droidance.ui.viewmodel.AutomationDraftError
import com.droidance.ui.viewmodel.AutomationsViewModel
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(
    onNavigateBack: () -> Unit = {},
    initialPrompt: String = "",
    onOpenConversation: (String, String) -> Unit,
    viewModel: AutomationsViewModel = hiltViewModel()
) {
    val definitions by viewModel.definitions.collectAsStateWithLifecycle()
    val occurrences by viewModel.occurrences.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val generating by viewModel.isGeneratingDraft.collectAsStateWithLifecycle()
    val draftError by viewModel.draftError.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    var prompt by rememberSaveable { mutableStateOf(initialPrompt) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<AutomationDefinition?>(null) }
    var filter by rememberSaveable { mutableStateOf(AutomationListFilter.ALL) }

    LaunchedEffect(initialPrompt) {
        if (prompt.isBlank() && initialPrompt.isNotBlank()) prompt = initialPrompt
    }
    LaunchedEffect(definitions) {
        if (selectedId != null && definitions.none { it.id == selectedId }) selectedId = null
    }

    val currentDraft = draft
    if (currentDraft != null) {
        AutomationEditor(
            draft = currentDraft,
            existingDefinition = definitions.firstOrNull { it.id == currentDraft.editingAutomationId },
            alarmPrecision = viewModel.alarmPrecision(),
            saveError = draftError,
            onBack = viewModel::dismissDraft,
            onSave = viewModel::saveDraft,
            generating = generating,
            failure = failure,
            onRefine = { current, answer -> viewModel.generateDraft(current.originalRequest.ifBlank { current.instruction }, current, answer) }
        )
        return
    }

    val selected = definitions.firstOrNull { it.id == selectedId }
    if (selected != null) {
        BackHandler { selectedId = null; viewModel.clearDraftError() }
        AutomationDetail(
            definition = selected,
            history = occurrences.filter { it.automationId == selected.id }
                .sortedByDescending(AutomationOccurrence::queuedAtMs),
            error = draftError,
            onBack = { selectedId = null; viewModel.clearDraftError() },
            onEdit = { viewModel.edit(selected) },
            onRunNow = { viewModel.runNow(selected.id) },
            onEnabledChange = { viewModel.setEnabled(selected.id, it) },
            onDelete = { pendingDelete = selected },
            onRetryDelivery = viewModel::retryDelivery,
            onOpenConversation = onOpenConversation,
            failure = failure
        )
    } else {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.automation_title)) },
                navigationIcon = { AutomationBackButton(onNavigateBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    failure?.let { Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error) }
                    AutomationComposer(prompt, generating, if (failure == null) draftError else null,
                        onPromptChange = { prompt = it; viewModel.clearDraftError() },
                        onGenerate = { viewModel.generateDraft(prompt) },
                        onManual = { viewModel.startManualDraft(prompt) })
                }
                item {
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.automation_list_title), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { menu = true }) {
                                Text(stringResource(filterLabel(filter)), style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(4.dp))
                                Icon(DroidanceIcons.ChevronDown, null, Modifier.size(16.dp))
                            }
                            DroidanceDropdownMenu(menu, onDismissRequest = { menu = false }) {
                                AutomationListFilter.entries.forEach { value ->
                                    DropdownMenuItem(text = { Text(stringResource(filterLabel(value))) },
                                        onClick = { filter = value; menu = false })
                                }
                            }
                        }
                    }
                }
                val visible = definitions.filter { matchesAutomationFilter(it, filter) }
                if (visible.isEmpty()) item {
                    Text(stringResource(if (definitions.isEmpty()) R.string.automation_empty else R.string.automation_filter_empty),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                items(visible, key = AutomationDefinition::id) { definition ->
                    AutomationListRow(
                        definition = definition,
                        latest = occurrences.filter { it.automationId == definition.id }
                            .maxByOrNull(AutomationOccurrence::queuedAtMs),
                        onOpen = { selectedId = definition.id },
                        onEdit = { viewModel.edit(definition) },
                        onRunNow = { viewModel.runNow(definition.id) },
                        onEnabledChange = { viewModel.setEnabled(definition.id, it) },
                        onDelete = { pendingDelete = definition }
                    )
                }
            }
        }
    }

    pendingDelete?.let { definition ->
        DroidanceDialog(onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.automation_delete_title),
            text = stringResource(R.string.automation_delete_body, definition.title),
            confirmLabel = stringResource(R.string.common_delete), destructive = true,
            onConfirm = { viewModel.delete(definition.id); pendingDelete = null },
            dismissLabel = stringResource(R.string.common_cancel))
    }
}

@Composable
private fun AutomationBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(DroidanceIcons.ArrowBack, stringResource(R.string.common_back))
    }
}

@Composable
private fun AutomationComposer(
    prompt: String, generating: Boolean, error: AutomationDraftError?,
    onPromptChange: (String) -> Unit, onGenerate: () -> Unit, onManual: () -> Unit
) {
    Surface(shape = AppShapes.Card, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.automation_create_title), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = prompt, onValueChange = onPromptChange, modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 4, textStyle = MaterialTheme.typography.bodyMedium,
                colors = droidanceFieldColors(), shape = AppShapes.Control,
                placeholder = { Text(stringResource(R.string.automation_instruction_hint), style = MaterialTheme.typography.bodyMedium) }
            )
            AutomationError(error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onManual, enabled = !generating) { Text(stringResource(R.string.automation_manual_setup)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onGenerate, enabled = prompt.isNotBlank() && !generating,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    if (generating) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.automation_generate_draft), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun AutomationListRow(
    definition: AutomationDefinition, latest: AutomationOccurrence?, onOpen: () -> Unit,
    onEdit: () -> Unit, onRunNow: () -> Unit, onEnabledChange: (Boolean) -> Unit, onDelete: () -> Unit
) {
    Surface(shape = AppShapes.Card, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(definition.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(scheduleText(definition.schedule), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val activeState = automationListExecutionState(latest)
                when {
                    activeState != null -> Text(executionStateText(activeState), style = MaterialTheme.typography.labelSmall,
                        color = latestStatusColor(requireNotNull(latest)))
                    automationDeliveryNeedsAttention(latest) -> Text(stringResource(R.string.automation_delivery_failed),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    automationPlanState(definition) != AutomationPlanState.ACTIVE -> Text(planStateText(definition),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AutomationActions(definition, onEdit, onRunNow, onEnabledChange, onDelete)
        }
    }
}

@Composable
private fun AutomationActions(
    definition: AutomationDefinition, onEdit: () -> Unit, onRunNow: () -> Unit,
    onEnabledChange: (Boolean) -> Unit, onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(DroidanceIcons.MoreVertical, stringResource(R.string.common_more), Modifier.size(18.dp)) }
        DroidanceDropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(if (definition.finishedAtMs > 0L) R.string.automation_reschedule else R.string.common_edit)) },
                leadingIcon = { Icon(DroidanceIcons.Pencil, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.automation_run_now)) },
                leadingIcon = { Icon(DroidanceIcons.Play, null, Modifier.size(18.dp)) },
                onClick = { menu = false; onRunNow() })
            if (definition.finishedAtMs == 0L) DropdownMenuItem(
                text = { Text(stringResource(if (definition.enabled) R.string.automation_pause else R.string.automation_resume)) },
                onClick = { menu = false; onEnabledChange(!definition.enabled) })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(DroidanceIcons.Trash, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutomationDetail(
    definition: AutomationDefinition, history: List<AutomationOccurrence>, error: AutomationDraftError?,
    onBack: () -> Unit, onEdit: () -> Unit, onRunNow: () -> Unit,
    onEnabledChange: (Boolean) -> Unit, onDelete: () -> Unit, onRetryDelivery: (String) -> Unit,
    onOpenConversation: (String, String) -> Unit,
    failure: com.droidance.ui.viewmodel.UiOperationFailure? = null
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.automation_detail_title)) }, navigationIcon = { AutomationBackButton(onBack) },
            actions = { AutomationActions(definition, onEdit, onRunNow, onEnabledChange, onDelete) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(definition.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(scheduleText(definition.schedule), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(planStateText(definition), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (definition.enabled && definition.nextRunAtMs > 0L) Text(
                        stringResource(R.string.automation_next_run, formatDateTime(definition.nextRunAtMs, definition.schedule.timeZoneId)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AutomationError(error, failure)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.automation_instruction_label), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlatMarkdown(definition.instruction, Modifier.fillMaxWidth())
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.automation_delivery_local_detail), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (definition.schedule.maxOccurrences > 0L) Text(
                        stringResource(R.string.automation_limit_summary, definition.triggeredCount, definition.schedule.maxOccurrences),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (definition.schedule.endAtMs > 0L) Text(
                        stringResource(R.string.automation_end_summary, formatDateTime(definition.schedule.endAtMs, definition.schedule.timeZoneId)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (definition.alarmPrecision == AutomationAlarmPrecision.APPROXIMATE) Text(
                        stringResource(R.string.automation_scheduled_approximate), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.automation_history), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp))
            }
            if (history.isEmpty()) item { Text(stringResource(R.string.automation_history_empty),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(history, key = AutomationOccurrence::id) { occurrence -> AutomationHistoryRow(occurrence, onRetryDelivery, onOpenConversation) }
        }
    }
}

@Composable
private fun AutomationHistoryRow(
    occurrence: AutomationOccurrence, onRetryDelivery: (String) -> Unit, onOpenConversation: (String, String) -> Unit
) {
    val turnId = occurrence.turnId?.takeIf { it.isNotBlank() }
    val hasConversation = turnId != null && occurrence.threadId.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable(
            enabled = hasConversation, role = Role.Button,
            onClickLabel = stringResource(R.string.automation_open_conversation),
            onClick = { if (hasConversation) onOpenConversation(occurrence.threadId, requireNotNull(turnId)) }
        ).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDateTime(occurrence.scheduledForMs), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f))
            Text(executionStateText(occurrence.executionState), style = MaterialTheme.typography.labelSmall,
                color = latestStatusColor(occurrence))
            if (hasConversation) {
                Spacer(Modifier.width(8.dp))
                Icon(DroidanceIcons.ChevronRight, stringResource(R.string.automation_open_conversation), Modifier.size(16.dp))
            }
        }
        if (occurrence.startedAtMs > 0L) Text(stringResource(R.string.automation_actual_start, formatDateTime(occurrence.startedAtMs)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (occurrence.coalescedCount > 0L) Text(stringResource(R.string.automation_merged_count, occurrence.coalescedCount),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (occurrence.deliveryState != AutomationDeliveryState.NOT_REQUESTED) {
            Text(deliveryStateText(occurrence), style = MaterialTheme.typography.bodySmall,
                color = if (occurrence.deliveryState == AutomationDeliveryState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (occurrence.deliveryState == AutomationDeliveryState.FAILED) TextButton(onClick = { onRetryDelivery(occurrence.id) }) {
            Text(stringResource(R.string.automation_retry_delivery))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutomationEditor(
    draft: AutomationDraft, existingDefinition: AutomationDefinition?,
    alarmPrecision: AutomationAlarmPrecision, saveError: AutomationDraftError?, onBack: () -> Unit,
    onSave: (String, String, AutomationSchedule) -> Unit,
    generating: Boolean = false,
    failure: com.droidance.ui.viewmodel.UiOperationFailure? = null,
    onRefine: ((AutomationDraft, String) -> Unit)? = null
) {
    var refinement by rememberSaveable(draft) { mutableStateOf("") }
    var title by rememberSaveable(draft) { mutableStateOf(draft.title) }
    var instruction by rememberSaveable(draft) { mutableStateOf(draft.instruction) }
    var editingInstruction by rememberSaveable(draft) { mutableStateOf(draft.instruction.isBlank()) }
    var schedule by remember(draft) { mutableStateOf(draft.schedule) }
    var delayMinutes by remember(draft) { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000L); now = System.currentTimeMillis() } }
    val effectiveSchedule = runCatching {
        schedule?.let { value -> delayMinutes?.let { value.copy(runAtMs = Math.addExact(now, Math.multiplyExact(it, 60_000L))) } ?: value }
    }.getOrNull()
    val next = effectiveSchedule?.let { runCatching {
        val freshInterval = it.cadence == AutomationCadence.INTERVAL && (existingDefinition == null || it != existingDefinition.schedule)
        if (freshInterval && it.runAtMs <= now && it.missedPolicy == AutomationMissedPolicy.RUN_ONCE && (it.endAtMs == 0L || it.endAtMs > now)) now
        else AutomationScheduleCalculator.nextAfter(it, now)
    }.getOrNull() } ?: 0L
    var discard by remember { mutableStateOf(false) }
    var showRequest by rememberSaveable(draft) { mutableStateOf(false) }
    val dirty = refinement.isNotBlank() || title != draft.title || instruction != draft.instruction || schedule != draft.schedule || delayMinutes != null
    val leave = { if (dirty) discard = true else onBack() }
    BackHandler(onBack = leave)
    val valid = title.isNotBlank() && instruction.isNotBlank() && effectiveSchedule != null && next > 0L &&
        (effectiveSchedule.cadence != AutomationCadence.ONCE || effectiveSchedule.runAtMs > now)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (draft.editingAutomationId == null) R.string.automation_review_title else R.string.automation_edit_title)) },
            navigationIcon = { AutomationBackButton(leave) },
            actions = { TextButton(onClick = {
                val savedSchedule = runCatching {
                    schedule?.let { value -> delayMinutes?.let { value.copy(runAtMs = Math.addExact(System.currentTimeMillis(), Math.multiplyExact(it, 60_000L))) } ?: value }
                }.getOrNull()
                if (savedSchedule != null) onSave(title.trim(), instruction.trim(), savedSchedule)
            }, enabled = valid && !generating) { Text(stringResource(if (existingDefinition != null && existingDefinition.finishedAtMs == 0L) R.string.common_save else R.string.automation_save)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
    }) { padding ->
        if (generating) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.automation_refining), modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (onRefine != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (draft.clarification.isNotBlank()) FlatMarkdown(draft.clarification, textStyle = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(refinement, { refinement = it }, label = { Text(stringResource(R.string.automation_refine_label)) },
                            enabled = !generating, modifier = Modifier.fillMaxWidth(), shape = AppShapes.Control, colors = droidanceFieldColors())
                        TextButton(enabled = refinement.isNotBlank() && !generating, onClick = {
                            onRefine(draft.copy(title = title, instruction = instruction, schedule = effectiveSchedule), refinement)
                        }) {
                            if (generating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.automation_refine_action))
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.automation_name_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                    colors = droidanceFieldColors(), shape = AppShapes.Control)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.automation_instruction_label), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { editingInstruction = !editingInstruction }) {
                            Text(stringResource(if (editingInstruction) R.string.automation_preview else R.string.common_edit))
                        }
                    }
                    if (editingInstruction) OutlinedTextField(instruction, { instruction = it }, minLines = 3,
                        modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium, colors = droidanceFieldColors(), shape = AppShapes.Control)
                    else Surface(shape = AppShapes.Control, color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        FlatMarkdown(instruction, Modifier.fillMaxWidth().padding(14.dp))
                    }
                }
                AutomationScheduleFields(draft.schedule) { value, delay -> schedule = value; delayMinutes = delay }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (next > 0L && effectiveSchedule != null) Text(
                        stringResource(R.string.automation_next_preview, formatDateTime(next, effectiveSchedule.timeZoneId), effectiveSchedule.timeZoneId),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (schedule != null) Text(stringResource(R.string.automation_schedule_expired),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (alarmPrecision == AutomationAlarmPrecision.APPROXIMATE) Text(stringResource(R.string.automation_approximate_timing),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AutomationError(saveError, failure)
                    if (onRefine == null && draft.clarification.isNotBlank()) FlatMarkdown(draft.clarification, textStyle = MaterialTheme.typography.bodySmall)
                }
                if (draft.originalRequest.isNotBlank() || draft.referenceTimeMs > 0L) {
                    TextButton(onClick = { showRequest = !showRequest }) {
                        Text(stringResource(R.string.automation_request_details))
                        Spacer(Modifier.width(4.dp))
                        Icon(if (showRequest) DroidanceIcons.ChevronUp else DroidanceIcons.ChevronDown, null, Modifier.size(16.dp))
                    }
                    if (showRequest) {
                        if (draft.originalRequest.isNotBlank()) FlatMarkdown(draft.originalRequest, textStyle = MaterialTheme.typography.bodySmall)
                        if (draft.referenceTimeMs > 0L && delayMinutes == null) Text(
                            stringResource(R.string.automation_reference_time, formatDateTime(draft.referenceTimeMs)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (discard) DroidanceDialog(onDismissRequest = { discard = false },
        title = stringResource(R.string.form_unsaved_title), text = stringResource(R.string.form_unsaved_body),
        confirmLabel = stringResource(R.string.form_discard), destructive = true,
        onConfirm = { discard = false; onBack() }, dismissLabel = stringResource(R.string.form_keep_editing))
}

@Composable
private fun AutomationError(error: AutomationDraftError?, failure: com.droidance.ui.viewmodel.UiOperationFailure? = null) {
    if (failure != null) {
        Text(stringResource(failure.messageRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    if (error != null) Text(stringResource(when (error) {
        AutomationDraftError.INCOMPLETE_REQUEST -> R.string.automation_draft_incomplete
        AutomationDraftError.MODEL_UNAVAILABLE -> R.string.automation_draft_unavailable
        AutomationDraftError.INVALID_RESPONSE -> R.string.automation_draft_invalid_response
        AutomationDraftError.SAVE_FAILED -> R.string.automation_save_failed
        AutomationDraftError.ACTION_FAILED -> R.string.automation_action_failed
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun filterLabel(filter: AutomationListFilter): Int = when (filter) {
    AutomationListFilter.ALL -> R.string.automation_filter_all
    AutomationListFilter.ACTIVE -> R.string.automation_filter_active
    AutomationListFilter.PAUSED -> R.string.automation_filter_paused
    AutomationListFilter.ENDED -> R.string.automation_filter_ended
}

@Composable
private fun planStateText(definition: AutomationDefinition): String = stringResource(when (automationPlanState(definition)) {
    AutomationPlanState.ACTIVE -> R.string.automation_filter_active
    AutomationPlanState.PAUSED -> R.string.automation_filter_paused
    AutomationPlanState.ENDED -> R.string.automation_filter_ended
})

@Composable
private fun scheduleText(schedule: AutomationSchedule): String {
    val time = String.format(Locale.getDefault(), "%02d:%02d", schedule.hour, schedule.minute)
    return when (schedule.cadence) {
        AutomationCadence.ONCE -> formatDateTime(schedule.runAtMs, schedule.timeZoneId)
        AutomationCadence.INTERVAL -> pluralStringResource(R.plurals.automation_schedule_interval, schedule.intervalMinutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), schedule.intervalMinutes)
        AutomationCadence.DAILY -> stringResource(R.string.automation_schedule_daily_at, time)
        AutomationCadence.WEEKLY -> stringResource(R.string.automation_schedule_weekly_at,
            schedule.daysOfWeek.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }, time)
    }
}

@Composable
private fun executionStateText(state: AutomationExecutionState): String = stringResource(when (state) {
    AutomationExecutionState.QUEUED -> R.string.automation_state_queued
    AutomationExecutionState.RUNNING -> R.string.automation_state_running
    AutomationExecutionState.SUCCEEDED -> R.string.automation_state_succeeded
    AutomationExecutionState.FAILED -> R.string.automation_state_failed
    AutomationExecutionState.WAITING_FOR_USER -> R.string.automation_state_waiting
    AutomationExecutionState.UNKNOWN -> R.string.automation_state_unknown
    AutomationExecutionState.SKIPPED -> R.string.automation_state_skipped
    AutomationExecutionState.CANCELLED -> R.string.automation_state_cancelled
})

@Composable
private fun deliveryStateText(occurrence: AutomationOccurrence): String = stringResource(when (occurrence.deliveryState) {
    AutomationDeliveryState.NOT_REQUESTED -> R.string.automation_delivery_not_requested
    AutomationDeliveryState.PENDING -> R.string.automation_delivery_pending
    AutomationDeliveryState.HANDOFF_ACCEPTED -> R.string.automation_notification_sent
    AutomationDeliveryState.FAILED -> R.string.automation_delivery_failed
})

@Composable
private fun latestStatusColor(occurrence: AutomationOccurrence) = when {
    occurrence.executionState in setOf(AutomationExecutionState.FAILED, AutomationExecutionState.UNKNOWN) ||
        occurrence.deliveryState == AutomationDeliveryState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatDateTime(epochMs: Long, zoneId: String = java.time.ZoneId.systemDefault().id): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).apply {
        timeZone = java.util.TimeZone.getTimeZone(zoneId)
    }.format(Date(epochMs))
