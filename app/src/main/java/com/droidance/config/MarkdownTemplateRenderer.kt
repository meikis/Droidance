// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

object MarkdownTemplateRenderer {
    fun render(template: String, replacements: Map<String, String>): String {
        return replacements.entries.fold(template) { content, (key, value) ->
            content.replace("{{$key}}", value)
        }.trim()
    }
}
