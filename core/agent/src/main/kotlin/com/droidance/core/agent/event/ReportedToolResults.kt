// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.PublicAssistantText
import com.droidance.core.agent.tools.ReportResultTool

/** The same settled, public handovers feed the UI and later model turns. */
fun reportedToolResults(
    turnId: String,
    activity: TurnActivityReadModel?,
    toolDetails: Map<String, ToolDetailReadModel>,
): List<PublicProgressReadModel> {
    val calls = activity?.entries.orEmpty().filterIsInstance<ToolActivityReadModel>()
        .associateBy { it.callId }
    return toolDetails.values.filter {
        it.turnId == turnId && it.technicalName == ReportResultTool.NAME &&
            it.executionStatus == ToolExecutionStatus.SUCCEEDED
    }.mapNotNull { detail ->
        val call = calls[detail.callId] ?: return@mapNotNull null
        ReportResultTool.text(detail.argumentsJson)?.let { text ->
            PublicProgressReadModel("report:${detail.callId}", call.sequence,
                PublicAssistantText.sanitize(text).trim(), listOf(detail.roundId))
        }
    }.filter { it.text.isNotBlank() }.sortedBy { it.sequence }
}
