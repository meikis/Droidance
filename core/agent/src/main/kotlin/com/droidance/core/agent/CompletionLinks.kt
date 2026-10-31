// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.intellij.markdown.MarkdownElementTypes as Types
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/** Extract rendered Markdown links with the same parser used by the public UI. */
object CompletionLinks {
    fun destinations(text: String): Set<String> {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
        val references = linkedMapOf<String, String>()
        val links = linkedSetOf<String>()
        // Match Compose's Markdown punctuation unescaping, not HTML destination encoding.
        fun content(node: ASTNode): String = buildString {
            val raw = text.substring(node.startOffset, node.endOffset)
            var i = 0
            while (i < raw.length) {
                if (raw[i] == '\\' && i + 1 < raw.length &&
                    (raw[i + 1].code in 33..47 || raw[i + 1].code in 58..64 ||
                        raw[i + 1].code in 91..96 || raw[i + 1].code in 123..126)) i++
                append(raw[i++])
            }
        }
        fun definitions(node: ASTNode) {
            if (node.type == Types.LINK_DEFINITION) {
                val label = node.children.firstOrNull { it.type == Types.LINK_LABEL }
                val dest = node.children.firstOrNull { it.type == Types.LINK_DESTINATION }
                if (label != null && dest != null) references[content(label)] = content(dest)
            } else node.children.forEach(::definitions)
        }
        definitions(root)
        fun visit(node: ASTNode) {
            when (node.type) {
                Types.CODE_FENCE, Types.CODE_BLOCK, Types.CODE_SPAN, Types.LINK_DEFINITION, Types.IMAGE -> return
                Types.INLINE_LINK -> node.children.firstOrNull { it.type == Types.LINK_DESTINATION }?.let {
                    links.add(content(it))
                }
                Types.FULL_REFERENCE_LINK, Types.SHORT_REFERENCE_LINK -> {
                    // The renderer emits reference nodes without LINK_TEXT as plain text.
                    if (node.children.none { it.type == Types.LINK_TEXT }) return
                    val label = node.children.firstOrNull { it.type == Types.LINK_LABEL }
                        ?: node.children.firstOrNull { it.type == Types.LINK_TEXT }
                    label?.let { links.add(references[content(it)] ?: content(it)) }
                }
                Types.AUTOLINK -> links.add(content(node).removeSurrounding("<", ">"))
                MarkdownTokenTypes.AUTOLINK -> links.add(content(node))
                GFMTokenTypes.GFM_AUTOLINK -> if (node.parent?.type != Types.LINK_TEXT) links.add(content(node))
            }
            node.children.forEach(::visit)
        }
        visit(root)
        return links
    }
}
