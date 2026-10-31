// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.compose.ui.text.AnnotatedString
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatMarkdownRenderingTest {

    private fun parseAndAnnotateLeaf(content: String): String {
        val flavour = GFMFlavourDescriptor()
        val parser = MarkdownParser(flavour)
        val tree = parser.buildMarkdownTreeFromString(content)

        val builder = AnnotatedString.Builder()
        fun traverse(node: ASTNode) {
            val handled = builder.SystemicTokenFallbackAnnotator(content, node)
            if (!handled) {
                if (node.children.isEmpty()) {
                    builder.append(content.substring(node.startOffset, node.endOffset))
                } else {
                    for (child in node.children) {
                        traverse(child)
                    }
                }
            }
        }
        traverse(tree)
        return builder.toAnnotatedString().text
    }

    @Test
    fun tilde_symbol_is_preserved_without_being_dropped() {
        val input = "搜索100~200元价位"
        val output = parseAndAnnotateLeaf(input)
        assertEquals("搜索100~200元价位", output)
    }

    @Test
    fun dollar_symbol_is_preserved() {
        val input = "价格为$100美元"
        val output = parseAndAnnotateLeaf(input)
        assertEquals("价格为$100美元", output)
    }

    @Test
    fun angle_bracket_placeholders_and_tags_are_preserved() {
        val input = "按键 <Enter> 或 <Tab> 以及占位符 <package_name>"
        val output = parseAndAnnotateLeaf(input)
        assertEquals("按键 <Enter> 或 <Tab> 以及占位符 <package_name>", output)
    }

    @Test
    fun br_tags_are_converted_to_newline() {
        val input = "第一行<br>第二行<br/>第三行"
        val output = parseAndAnnotateLeaf(input)
        assertEquals("第一行\n第二行\n第三行", output)
    }

    @Test
    fun strikethrough_delimiters_are_not_echoed_by_fallback() {
        val input = "~~已删除~~"
        val flavour = GFMFlavourDescriptor()
        val parser = MarkdownParser(flavour)
        val tree = parser.buildMarkdownTreeFromString(input)

        var strikethroughTildeFound = false
        fun findTilde(node: ASTNode) {
            if (node.parent?.type?.name == "STRIKETHROUGH" && node.type.name == "~") {
                strikethroughTildeFound = true
                val builder = AnnotatedString.Builder()
                val handled = builder.SystemicTokenFallbackAnnotator(input, node)
                assertFalse("Strikethrough delimiter should return false to let container handle", handled)
            }
            node.children.forEach(::findTilde)
        }
        findTilde(tree)
        assertTrue(strikethroughTildeFound)
    }

    @Test
    fun clean_code_fence_removes_leading_newline_from_language_tags() {
        val content = "```kotlin\nval x = 1\n```"
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(content)
        val codeFenceNode = tree.children.first()

        val cleanCode = extractCleanCodeFence(content, codeFenceNode)
        assertEquals("val x = 1", cleanCode)
    }

    @Test
    fun clean_code_fence_handles_no_language_tag() {
        val content = "```\nval x = 1\n```"
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(content)
        val codeFenceNode = tree.children.first()

        val cleanCode = extractCleanCodeFence(content, codeFenceNode)
        assertEquals("val x = 1", cleanCode)
    }

    @Test
    fun clean_code_fence_handles_multiline_and_empty_blocks() {
        val multi = "```python\nline 1\nline 2\n```"
        val multiTree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(multi)
        assertEquals("line 1\nline 2", extractCleanCodeFence(multi, multiTree.children.first()))

        val empty = "```\n\n```"
        val emptyTree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(empty)
        assertEquals("", extractCleanCodeFence(empty, emptyTree.children.first()))
    }
}
