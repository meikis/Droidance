// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.droidance.core.agent.event.PublicProgressReadModel
import com.droidance.ui.components.FlatMarkdown

/** Results the model handed over with report_result, shown like any other assistant reply. */
@Composable
internal fun ReportedResults(results: List<PublicProgressReadModel>) {
    results.forEach { result ->
        key(result.eventId) {
            SelectionContainer {
                FlatMarkdown(
                    content = mobileMarkdownContent(result.text),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)
                        .testTag("reported-result:${result.eventId}")
                )
            }
        }
    }
}
