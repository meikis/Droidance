// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidance.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LicenseBlock(val heading: String, val text: String)

@Composable
internal fun ProjectLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blocks = produceState<List<LicenseBlock>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            fun paths(directory: String): List<String> = context.assets.list(directory).orEmpty().sorted().flatMap { name ->
                val path = "$directory/$name"
                if (context.assets.list(path).orEmpty().isEmpty()) listOf(path) else paths(path)
            }
            paths("licenses").sortedBy { if (it.startsWith("licenses/droidance/")) "0$it" else "1$it" }
                .flatMap { path ->
                    context.assets.open(path).bufferedReader().use { it.readLines() }.chunked(60).mapIndexed { index, lines ->
                        LicenseBlock(if (index == 0) path.removePrefix("licenses/") else "", lines.joinToString("\n"))
                    }
                }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            SubScreenScaffold(title = stringResource(R.string.settings_open_source_licenses), onBack = onDismiss) { padding ->
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp)) {
                    if (blocks.value == null) item { Text(stringResource(R.string.settings_licenses_loading)) }
                    itemsIndexed(blocks.value.orEmpty()) { _, block ->
                        if (block.heading.isNotEmpty()) Text(block.heading,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                        Text(block.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
