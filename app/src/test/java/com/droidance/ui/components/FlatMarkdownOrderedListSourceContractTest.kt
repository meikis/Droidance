// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 有序列表被无序列表/段落打断时会拆成多个 ORDERED_LIST 节点，
 * 渲染库默认按节点内位置重排序号（index + 1），第二段会重新从 1 开始。
 * FlatMarkdown 必须覆盖 LocalOrderedListHandler，沿用解析器保留的原文序号。
 */
class FlatMarkdownOrderedListSourceContractTest {
    @Test
    fun orderedListKeepsOriginalNumberFromSource() {
        val source = projectFile("app/src/main/java/com/droidance/ui/components/FlatMarkdown.kt").readText()

        assertTrue(source.contains("LocalOrderedListHandler provides"))
        assertTrue(source.contains("BulletHandler { _, bullet, index ->"))
        assertTrue(source.contains("""if (raw.isNullOrEmpty()) "${'$'}{index + 1}. " else "${'$'}raw """"))
    }

    @Test
    fun headings_lists_and_code_use_one_body_font_size_and_tables_have_a_renderer() {
        val source = projectFile("app/src/main/java/com/droidance/ui/components/FlatMarkdown.kt").readText()

        assertTrue(source.contains("code = body.copy(fontFamily = FontFamily.Monospace)"))
        assertFalse(source.contains("code = MaterialTheme.typography.bodySmall"))
        assertTrue(source.contains("h4 = bold"))
        assertTrue(source.contains("h5 = bold"))
        assertTrue(source.contains("h6 = bold"))
        assertTrue(source.contains("padding = markdownPadding("))
        assertTrue(source.contains("MarkdownTableBlock"))
        assertTrue(source.contains("horizontalScroll"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
