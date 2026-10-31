// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFieldColors
import androidx.activity.compose.BackHandler
import com.droidance.ui.components.DroidanceDialog

import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.droidance.R
import com.droidance.ui.viewmodel.AgentsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onNavigateBack: (() -> Unit)? = null,
    viewModel: AgentsViewModel = hiltViewModel()
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val editingAgent by viewModel.editingAgent.collectAsStateWithLifecycle()
    val editingSoul by viewModel.editingSoul.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveFailure by viewModel.saveFailure.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var discard by remember { mutableStateOf(false) }
    val exit = { viewModel.closeEdit(); onNavigateBack?.invoke(); Unit }
    val close = { if (viewModel.hasUnsavedChanges()) discard = true else exit() }
    BackHandler { if (!isSaving) close() }
    if (discard) DroidanceDialog(onDismissRequest = { discard = false },
        title = stringResource(R.string.form_unsaved_title), text = stringResource(R.string.form_unsaved_body),
        confirmLabel = stringResource(R.string.form_discard), destructive = true,
        onConfirm = { discard = false; exit() }, dismissLabel = stringResource(R.string.form_keep_editing))
    val activeAgent = agents.firstOrNull { it.isActive } ?: agents.firstOrNull()

    LaunchedEffect(activeAgent?.name) {
        if (editingAgent == null) activeAgent?.let { viewModel.openEdit(it.name) }
    }

    if (editingAgent == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else {
        SoulEditScreen(
            soul = editingSoul,
            isSaving = isSaving,
            onSoulChanged = viewModel::onSoulChanged,
            onSave = viewModel::saveSoul,
            onBack = { if (!isSaving) close() },
            feedback = saveFailure?.let { stringResource(it.messageRes) } ?: if (saved) stringResource(R.string.ui_saved) else null,
            failed = saveFailure != null
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoulEditScreen(
    soul: String,
    isSaving: Boolean,
    onSoulChanged: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    feedback: String? = null,
    failed: Boolean = false
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(DroidanceIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = { Text(stringResource(R.string.assistant_custom_instructions)) },
                actions = {
                    TextButton(onClick = onSave, enabled = !isSaving) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues).imePadding().padding(16.dp)) {
            Text(
                stringResource(R.string.memory_soul_document),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            feedback?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
            OutlinedTextField(
                value = soul,
                onValueChange = onSoulChanged,
                modifier = Modifier
                    .fillMaxWidth().weight(1f),
                enabled = !isSaving,
                placeholder = { Text(stringResource(R.string.agents_soul_hint)) },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = droidanceFieldColors(),
                shape = AppShapes.Control
            )
        }
    }
}
