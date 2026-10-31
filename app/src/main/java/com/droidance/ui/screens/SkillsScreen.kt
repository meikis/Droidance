// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFieldColors
import com.droidance.ui.components.DroidanceDropdownMenu

import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppIconSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.droidance.R
import com.droidance.agent.OperationRecorder
import com.droidance.core.knowledge.skill.SkillInfo
import com.droidance.ui.components.FlatMarkdown
import com.droidance.ui.components.DroidanceDialog
import com.droidance.ui.components.DroidanceSnackbar
import androidx.activity.compose.BackHandler
import com.droidance.ui.viewmodel.SkillEditorState
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.saveable.rememberSaveable
import com.droidance.ui.viewmodel.RecorderViewModel
import com.droidance.ui.viewmodel.SkillsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit = {},
    onAuthorSkill: (String) -> Unit,
    onSelectSkill: (SkillInfo) -> Unit,
    initialSelectedSkillKey: String? = null,
    viewModel: SkillsViewModel = hiltViewModel(),
    recorderViewModel: RecorderViewModel = hiltViewModel()
) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val message by viewModel.operationMessage.collectAsStateWithLifecycle()
    val failure by viewModel.operationFailure.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearOperationMessage() }
    }
    failure?.let { issue ->
        DroidanceDialog(onDismissRequest = viewModel::clearOperationFailure,
            title = stringResource(R.string.ui_operation_failed), text = stringResource(issue.messageRes),
            confirmLabel = stringResource(R.string.ui_ask_assistant),
            onConfirm = { val request = viewModel.buildRecoveryPrompt(issue, editing?.let { "skill_id=${it.skillId}\nrevision=${it.revision}" }.orEmpty()); viewModel.clearOperationFailure(); onAuthorSkill(request) },
            dismissLabel = stringResource(R.string.common_close))
    }
    val assessments by viewModel.assessments.collectAsStateWithLifecycle()
    val autoDraft by viewModel.autoDraft.collectAsStateWithLifecycle()
    val draftWork by viewModel.draftWorkState.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].language
    val recording by recorderViewModel.recordingState.collectAsStateWithLifecycle()
    val preparing by recorderViewModel.preparing.collectAsStateWithLifecycle()
    val recordingError by recorderViewModel.recordingError.collectAsStateWithLifecycle()
    val completion by recorderViewModel.completionNonce.collectAsStateWithLifecycle()
    val operations by recorderViewModel.operations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedSkillId by rememberSaveable(initialSelectedSkillKey) { mutableStateOf(initialSelectedSkillKey) }
    var awaitingRecordingCompletion by rememberSaveable { mutableStateOf(false) }
    var recordingCompletionBaseline by rememberSaveable { mutableStateOf(0L) }
    var create by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var demonstrate by remember { mutableStateOf(false) }
    var app by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var recorded by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<SkillInfo?>(null) }
    var export by remember { mutableStateOf<SkillInfo?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(viewModel::installPackage) ?: error("Cannot read document")
            } }.onFailure { viewModel.reportOperationFailure(it) }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val skill = export
        if (uri != null && skill != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportPackage(skill)) } ?: error("Cannot write document")
            } }.onFailure { viewModel.reportOperationFailure(it) }
        }
        export = null
    }
    LaunchedEffect(completion, recording) {
        if (awaitingRecordingCompletion && completion > recordingCompletionBaseline &&
            recording != OperationRecorder.RecordingState.RECORDING && operations.isNotEmpty()) {
            recorded = true; awaitingRecordingCompletion = false
        }
    }
    LaunchedEffect(draftWork) { viewModel.loadSkills() }

    editing?.let { editor ->
        SkillEditor(editor, viewModel, working, snackbar)
        return
    }
    val selectedSkill = skills.firstOrNull { it.selectionKey == selectedSkillId }
    if (selectedSkill != null) {
        val back = {
            if (initialSelectedSkillKey != null) onNavigateBack() else selectedSkillId = null
        }
        BackHandler { back() }
        SkillDetailPage(selectedSkill, language, assessments[selectedSkill.selectionKey].orEmpty(),
            onBack = back, onSelect = { onSelectSkill(selectedSkill) },
            onAutoInvocationChanged = { viewModel.setAutoInvocation(selectedSkill, it) })
        return
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) { DroidanceSnackbar(it) } }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.skills_title), fontWeight = FontWeight.SemiBold) }, navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(DroidanceIcons.ArrowBack, stringResource(R.string.common_back))
            }
        }, actions = {
            TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "text/markdown", "text/plain", "application/octet-stream")) }) {
                Icon(DroidanceIcons.FileText, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.skill_import), style = MaterialTheme.typography.labelMedium)
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Text(stringResource(R.string.skill_library_intro), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
            }
            item {
                SettingsGroup(stringResource(R.string.skill_create_section)) {
                    SkillCreationRow(DroidanceIcons.Sparkles, R.string.skill_create, R.string.skill_create_hint) { create = true }
                    SettingsInsetDivider(startIndent = 48.dp)
                    SkillCreationRow(DroidanceIcons.Play, R.string.skill_demonstrate, R.string.skill_demonstrate_hint,
                        enabled = recording != OperationRecorder.RecordingState.RECORDING) { demonstrate = true }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsCardGroup {
                        SettingsSwitchItem(
                            title = stringResource(R.string.skill_auto_draft),
                            subtitle = if (autoDraft) when {
                                draftWork.running -> stringResource(R.string.skill_background_running)
                                draftWork.pending > 0 -> stringResource(R.string.skill_background_pending, draftWork.pending)
                                draftWork.failures > 0 -> stringResource(R.string.skill_background_failed)
                                else -> stringResource(R.string.skill_background_idle)
                            } else null,
                            checked = autoDraft,
                            onToggle = { viewModel.setAutoDraft(!autoDraft) }
                        )
                    }
                    Text(stringResource(R.string.skill_auto_draft_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
            if (recording == OperationRecorder.RecordingState.RECORDING) item {
                Button(onClick = { recorderViewModel.stopRecording { recorded = true; awaitingRecordingCompletion = false } },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.skill_finish_demonstration))
                }
            }
            if (skills.isEmpty()) item {
                SettingsGroup(stringResource(R.string.skill_library_count, 0)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(DroidanceIcons.Sparkles, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.skill_library_empty), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(skills, key = { _, skill -> skill.selectionKey }) { index, skill ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index == 0) SettingsGroupTitle(stringResource(R.string.skill_library_count, skills.distinctBy { it.id }.size))
                    SettingsCardGroup {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(skill.displayName(language), style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(if (skill.isDraft) R.string.skill_draft else R.string.skill_enabled_version, skill.version),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp))
                                }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(DroidanceIcons.MoreVertical, stringResource(R.string.common_more), Modifier.size(AppIconSize.Action))
                                    }
                                    DroidanceDropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.skill_generate_presentation)) },
                                            leadingIcon = { Icon(DroidanceIcons.Sparkles, null, Modifier.size(18.dp)) },
                                            onClick = { menu = false; onAuthorSkill(viewModel.buildPresentationPrompt(skill)) }, enabled = !skill.isReadOnly)
                                        DropdownMenuItem(text = { Text(stringResource(R.string.skill_edit)) }, onClick = { menu = false; viewModel.startEditing(skill) }, enabled = !skill.isReadOnly)
                                        DropdownMenuItem(text = { Text(stringResource(R.string.skill_export)) }, onClick = { menu = false; export = skill; exportLauncher.launch("${skill.name}.zip") })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.skills_uninstall), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete = skill }, enabled = !skill.isReadOnly)
                                    }
                                }
                            }
                            Text(skill.displayDescription(language), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            if (skill.isDraft && skill.hasInstalledVersion) Text(stringResource(R.string.skill_active_preserved), style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (!skill.isDraft) Text(stringResource(if (skill.enabled && skill.allowImplicitInvocation) R.string.skill_auto_on else R.string.skill_auto_off),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f))
                                else Spacer(Modifier.weight(1f))
                                SettingsPillButton(
                                    text = stringResource(if (skill.isDraft) R.string.skill_try else R.string.skill_use),
                                    onClick = { selectedSkillId = skill.selectionKey }
                                )
                                if (skill.isDraft) TextButton(onClick = { viewModel.publish(skill) }) {
                                    Text(stringResource(R.string.skill_publish), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (create) DroidanceDialog(onDismissRequest = { create = false }, title = stringResource(R.string.skill_create),
        text = stringResource(R.string.skill_create_explanation),
        confirmLabel = stringResource(R.string.skill_generate), confirmEnabled = description.isNotBlank(),
        onConfirm = { create = false; onAuthorSkill(viewModel.buildCreationPrompt(description)) },
        dismissLabel = stringResource(R.string.common_cancel)) {
        OutlinedTextField(description, onValueChange = { description = it }, label = { Text(stringResource(R.string.skill_describe)) },
            placeholder = { Text(stringResource(R.string.skill_description_example)) }, minLines = 4,
            modifier = Modifier.fillMaxWidth(), shape = AppShapes.Control, colors = droidanceFieldColors())
    }
    if (demonstrate) DroidanceDialog(onDismissRequest = { demonstrate = false }, title = stringResource(R.string.skill_demonstrate),
        text = stringResource(R.string.skill_demonstration_scope), dismissEnabled = !preparing,
        confirmLabel = stringResource(if (preparing) R.string.ui_preparing else R.string.skills_setup_start),
        confirmEnabled = !preparing && app.isNotBlank() && goal.isNotBlank(),
        onConfirm = {
            recordingCompletionBaseline = completion
            recorderViewModel.startDemonstration("", goal, app) { if (it) { demonstrate = false; awaitingRecordingCompletion = true } }
        }, dismissLabel = stringResource(R.string.common_cancel)) {
        OutlinedTextField(app, onValueChange = { app = it }, label = { Text(stringResource(R.string.skills_setup_app)) },
            enabled = !preparing, modifier = Modifier.fillMaxWidth(), shape = AppShapes.Control, colors = droidanceFieldColors())
        OutlinedTextField(goal, onValueChange = { goal = it }, label = { Text(stringResource(R.string.skill_describe)) },
            enabled = !preparing, modifier = Modifier.fillMaxWidth(), shape = AppShapes.Control, colors = droidanceFieldColors())
        recordingError?.let { issue ->
            Text(stringResource(issue.messageRes), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { demonstrate = false; onAuthorSkill(viewModel.buildRecoveryPrompt(issue, "target_app=$app\ngoal=$goal")) }) {
                Text(stringResource(R.string.ui_ask_assistant))
            }
        }
    }
    if (recorded) DroidanceDialog(onDismissRequest = { recorded = false }, title = stringResource(R.string.skill_generate),
        text = stringResource(R.string.skill_recorded_count, operations.size),
        confirmLabel = stringResource(R.string.skill_generate), confirmEnabled = operations.isNotEmpty() && recordingError == null,
        onConfirm = { val prompt = recorderViewModel.buildLearningPrompt(); recorded = false; onAuthorSkill(prompt) },
        dismissLabel = stringResource(R.string.common_cancel)) {
        recordingError?.let { Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error) }
    }
    delete?.let { skill -> DroidanceDialog(onDismissRequest = { delete = null }, title = stringResource(R.string.skills_uninstall_title),
        text = stringResource(R.string.skills_uninstall_body, skill.name), destructive = true,
        confirmLabel = stringResource(R.string.skills_uninstall),
        onConfirm = { viewModel.uninstall(skill); delete = null }, dismissLabel = stringResource(R.string.common_cancel)) }

}

@Composable
private fun SkillCreationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: Int,
    hint: Int,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 64.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = contentColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = contentColor)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Icon(DroidanceIcons.ChevronRight, null, Modifier.size(16.dp), tint = contentColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditor(editor: SkillEditorState, viewModel: SkillsViewModel, working: Boolean, snackbar: SnackbarHostState) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val close = { if (viewModel.hasUnsavedChanges()) discard = true else viewModel.cancelEditing() }
    BackHandler { if (!working) close() }
    if (discard) DroidanceDialog(onDismissRequest = { discard = false },
        title = stringResource(R.string.form_unsaved_title), text = stringResource(R.string.form_unsaved_body),
        confirmLabel = stringResource(R.string.form_discard), destructive = true,
        onConfirm = { discard = false; viewModel.cancelEditing() }, dismissLabel = stringResource(R.string.form_keep_editing))
    SubScreenScaffold(stringResource(R.string.skill_edit), onBack = { if (!working) close() },
        actions = { TextButton(enabled = !working && editor.displayName.isNotBlank() && editor.displayDescription.isNotBlank() &&
            editor.description.isNotBlank() && editor.body.isNotBlank(), onClick = viewModel::saveEditing) {
            if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.skill_save_draft))
        } }, snackbarHost = { SnackbarHost(snackbar) { DroidanceSnackbar(it) } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(editor.displayName, onValueChange = viewModel::updateEditingName, label = { Text(stringResource(R.string.skill_display_name)) },
                enabled = !working, modifier = Modifier.fillMaxWidth(), shape = AppShapes.Control, colors = droidanceFieldColors())
            OutlinedTextField(editor.displayDescription, onValueChange = viewModel::updateEditingSummary, label = { Text(stringResource(R.string.skill_display_summary)) },
                enabled = !working, modifier = Modifier.fillMaxWidth(), shape = AppShapes.Control, colors = droidanceFieldColors())
            OutlinedTextField(editor.description, onValueChange = viewModel::updateEditingDescription, label = { Text(stringResource(R.string.skill_describe)) },
                enabled = !working, modifier = Modifier.fillMaxWidth(), shape = AppShapes.Control, colors = droidanceFieldColors())
            OutlinedTextField(editor.body, onValueChange = viewModel::updateEditingBody, label = { Text(stringResource(R.string.ui_markdown)) },
                enabled = !working, minLines = 12, modifier = Modifier.fillMaxWidth(), shape = AppShapes.Control, colors = droidanceFieldColors())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillDetailPage(
    skill: SkillInfo,
    language: String,
    assessments: List<String>,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onAutoInvocationChanged: (Boolean) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text(skill.displayName(language), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) {
                    Icon(DroidanceIcons.ArrowBack, stringResource(R.string.common_back))
                } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            Button(onClick = onSelect, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = AppShapes.Control) {
                Text(stringResource(R.string.skill_add_to_chat))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(skill.displayDescription(language), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!skill.isDraft) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.skill_auto_use), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(skill.enabled && skill.allowImplicitInvocation, onCheckedChange = onAutoInvocationChanged)
            } else Text(stringResource(R.string.skill_draft_use_hint), style = MaterialTheme.typography.bodySmall)
            Text(skill.name, style = MaterialTheme.typography.labelMedium)
            Text(when (skill.source) {
                "system" -> stringResource(R.string.skill_source_system)
                "user" -> stringResource(R.string.skill_source_user)
                else -> stringResource(R.string.skill_source_other, skill.source)
            }, style = MaterialTheme.typography.labelMedium)
            FlatMarkdown(skill.rawContent)
            Text(stringResource(R.string.skill_parameters), style = MaterialTheme.typography.labelLarge)
            Text(skill.parameters.toString(), style = MaterialTheme.typography.bodySmall)
            if (skill.files.isNotEmpty()) Text(skill.files.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.skill_assessments, assessments.size), style = MaterialTheme.typography.labelLarge)
            assessments.forEach { raw ->
                val record = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject }.getOrNull()
                val outcome = (record?.get("outcome") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val summary = (record?.get("summary") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val label = when (outcome) {
                    "succeeded" -> R.string.skill_assessment_succeeded
                    "failed" -> R.string.skill_assessment_failed
                    else -> R.string.skill_assessment_unknown
                }
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
