// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.harness.ResultCardToolItem
import com.droidance.core.agent.harness.renderResultCardMarkdown

data class ResultCard(
    val title: String,
    val items: List<ResultCardToolItem>
) {
    /** Plain Markdown projection for channels that cannot render the structured card. */
    fun toUserFacingText(): String = renderResultCardMarkdown(
        title = title,
        items = items.map { it.text to it.deeplink }
    )
}
