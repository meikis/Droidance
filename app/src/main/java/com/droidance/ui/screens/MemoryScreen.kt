// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.ui.components.droidanceFieldColors
import androidx.activity.compose.BackHandler
import com.droidance.ui.components.DroidanceDialog

import com.droidance.ui.icons.DroidanceIcons
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.droidance.core.knowledge.memory.MemoryDisplaySaveResult
import com.droidance.ui.viewmodel.MemoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onNavigateBack: () -> Unit,
    onOpenExecutionPreferences: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val longTermMemory by viewModel.longTermMemory.collectAsStateWithLifecycle()
    val isEditingUserProfile by viewModel.isEditingUserProfile.collectAsStateWithLifecycle()
    val editingUserProfileText by viewModel.editingUserProfileText.collectAsStateWithLifecycle()
    val isEditingMemory by viewModel.isEditingMemory.collectAsStateWithLifecycle()
    val editingMemoryText by viewModel.editingMemoryText.collectAsStateWithLifecycle()
    val memoryDisplaySaveResult by viewModel.memoryDisplaySaveResult.collectAsStateWithLifecycle()
    val editErrorText = memoryDisplaySaveResult?.let { result ->
        stringResource(
            if (result == MemoryDisplaySaveResult.STALE) {
                R.string.memory_edit_stale
            } else {
                R.string.memory_edit_ambiguous
            }
        )
    }

    if (isEditingUserProfile) {
        FullScreenEditor(
            title = stringResource(R.string.memory_about_you),
            fileName = stringResource(R.string.memory_profile_document),
            text = editingUserProfileText,
            onTextChanged = viewModel::onUserProfileTextChanged,
            onSave = viewModel::saveUserProfile,
            onBack = viewModel::cancelEditUserProfile,
            errorText = editErrorText
        )
        return
    }

    if (isEditingMemory) {
        FullScreenEditor(
            title = stringResource(R.string.memory_saved_memories),
            fileName = stringResource(R.string.memory_long_term_document),
            text = editingMemoryText,
            onTextChanged = viewModel::onMemoryTextChanged,
            onSave = viewModel::saveMemory,
            onBack = viewModel::cancelEditMemory,
            errorText = editErrorText
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(DroidanceIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_preferences_memory), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
        ) {
            item {
                SettingsCardGroup {
                    SettingsNavItem(
                        title = stringResource(R.string.assistant_custom_instructions),
                        subtitle = stringResource(
                            R.string.memory_document_summary,
                            stringResource(R.string.memory_soul_document),
                            stringResource(R.string.assistant_custom_instructions_subtitle)
                        ),
                        onClick = onOpenExecutionPreferences
                    )
                    SettingsInsetDivider()
                    SettingsNavItem(
                        title = stringResource(R.string.memory_about_you),
                        subtitle = stringResource(
                            R.string.memory_document_summary,
                            stringResource(R.string.memory_profile_document),
                            userProfile.toSettingsPreview(stringResource(R.string.memory_about_you_empty))
                        ),
                        onClick = viewModel::startEditUserProfile
                    )
                    SettingsInsetDivider()
                    SettingsNavItem(
                        title = stringResource(R.string.memory_saved_memories),
                        subtitle = stringResource(
                            R.string.memory_document_summary,
                            stringResource(R.string.memory_long_term_document),
                            longTermMemory.toSettingsPreview(stringResource(R.string.memory_saved_memories_empty))
                        ),
                        onClick = viewModel::startEditMemory
                    )
                }
            }
        }
    }
}

private fun String.toSettingsPreview(emptyLabel: String): String =
    lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("#") || it.startsWith("<!--") }
        .map { it.trimStart('-', '*').trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(56)
        ?: emptyLabel

// ====== 全屏编辑器 ======

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenEditor(
    title: String,
    fileName: String,
    text: String,
    onTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    errorText: String? = null
) {
    val initialText = remember { text }
    var discard by remember { mutableStateOf(false) }
    val close = { if (text != initialText) discard = true else onBack() }
    BackHandler(onBack = close)
    if (discard) DroidanceDialog(onDismissRequest = { discard = false },
        title = stringResource(R.string.form_unsaved_title), text = stringResource(R.string.form_unsaved_body),
        confirmLabel = stringResource(R.string.form_discard), destructive = true,
        onConfirm = { discard = false; onBack() }, dismissLabel = stringResource(R.string.form_keep_editing))
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(DroidanceIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = { Text(title) },
                actions = {
                    TextButton(onClick = onSave) {
                        Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
                fileName,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge,
                isError = errorText != null,
                supportingText = errorText?.let { message -> { Text(message) } },
                colors = droidanceFieldColors(),
                shape = AppShapes.Control
            )
        }
    }
}
