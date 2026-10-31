// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.droidance.core.agent.WorkspaceDeliverable
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.ui.components.FlatMarkdown
import com.droidance.ui.theme.DroidanceTheme
import com.droidance.ui.screens.SubScreenScaffold
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Opens the exact published version, never the latest mutable workspace file. */
@AndroidEntryPoint
class WorkspaceDeliverableActivity : AppCompatActivity() {
    @Inject lateinit var events: AgentEventStore
    @Inject lateinit var workspace: TaskWorkspace
    @Inject lateinit var settings: com.droidance.settings.GeneralSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val document = withContext(Dispatchers.IO) {
                runCatching { WorkspaceDeliverable.read(intent.dataString.orEmpty(), events, workspace) }.getOrNull()
            }
            setContent {
                val themeMode by settings.themeMode.collectAsStateWithLifecycle()
                DroidanceTheme(themeMode = themeMode) {
                    SubScreenScaffold(title = document?.first ?: getString(R.string.workspace_deliverable_title), onBack = { finish() }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
                            if (document == null) Text(getString(R.string.workspace_deliverable_unavailable))
                            else FlatMarkdown(document.second)
                        }
                    }
                }
            }
        }
    }
}
