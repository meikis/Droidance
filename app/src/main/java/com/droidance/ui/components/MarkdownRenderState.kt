// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Keep completed top-level blocks intact while parsing only the unfinished suffix. */
internal class MarkdownRenderState {
    private var previous = ""
    private var committedOffset = 0
    private val committed = mutableListOf<String>()
    private val parser = MarkdownParser(GFMFlavourDescriptor())

    fun update(content: String): List<String> {
        if (!content.startsWith(previous) || REFERENCE_LINK.containsMatchIn(content)) {
            committed.clear()
            committedOffset = 0
        }
        previous = content
        // Reference links share document-wide definitions, so keep that context together.
        if (REFERENCE_LINK.containsMatchIn(content)) return listOf(content)
        val tail = content.substring(committedOffset)
        val nodes = parser.buildMarkdownTreeFromString(tail).children.filter {
            it.type != MarkdownTokenTypes.EOL && tail.substring(it.startOffset, it.endOffset).isNotBlank()
        }
        var start = 0
        for (next in nodes.drop(1)) {
            // A partial next line can still become another item of the preceding list,
            // or a setext/table delimiter. Wait until that line is terminated.
            if (tail.indexOf('\n', next.startOffset) < 0) break
            committed += tail.substring(start, next.startOffset)
            start = next.startOffset
        }
        committedOffset += start
        return committed + tail.substring(start)
    }

    private companion object {
        val REFERENCE_LINK = Regex("""(?m)^ {0,3}\[[^\]]+]:|\][ \t]*\[""")
    }
}
