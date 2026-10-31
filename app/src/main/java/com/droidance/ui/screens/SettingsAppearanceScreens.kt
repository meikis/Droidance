// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import com.droidance.config.AppLanguage
import com.droidance.settings.AppThemeMode
import com.droidance.ui.icons.DroidanceIcons

// ── 主题与语言 ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsThemeScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SubScreenScaffold(title = stringResource(R.string.settings_theme), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).selectableGroup(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp)
        ) {
            item {
                SettingsCardGroup {
                    AppThemeMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) SettingsInsetDivider()
                        SettingsSelectionOptionRow(
                            label = when (mode) {
                                AppThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                AppThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                                AppThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                            },
                            selected = uiState.themeMode == mode,
                            onClick = { viewModel.selectThemeMode(mode) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLanguageScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(configuration) { viewModel.refreshLanguage() }

    SubScreenScaffold(title = stringResource(R.string.settings_language), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).selectableGroup(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp)
        ) {
            item {
                SettingsCardGroup {
                    AppLanguage.entries.forEachIndexed { index, language ->
                        if (index > 0) SettingsInsetDivider()
                        SettingsSelectionOptionRow(
                            label = languageLabel(language),
                            selected = uiState.appLanguage == language,
                            onClick = {
                                if (uiState.appLanguage != language) {
                                    viewModel.selectLanguage(language)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
    AppLanguage.SIMPLIFIED_CHINESE -> stringResource(R.string.settings_language_zh)
    AppLanguage.ENGLISH -> stringResource(R.string.settings_language_en)
}

@Composable
private fun SettingsSelectionOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                DroidanceIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
