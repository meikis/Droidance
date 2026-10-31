// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatMarkdownTableTest {
    @Test
    fun pipe_table_is_split_from_surrounding_markdown() {
        val blocks = parseFlatMarkdownBlocks(
            """
            结果如下：

            | 型号 | 价格 |
            | --- | ---: |
            | 9850X3D | ¥3357.88 |
            | 9800X3D | ¥3049 |

            以上为当前页面数据。
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        val table = blocks[1] as MarkdownTableBlock
        assertEquals(listOf("型号", "价格"), table.headers)
        assertEquals(
            listOf(listOf("9850X3D", "¥3357.88"), listOf("9800X3D", "¥3049")),
            table.rows
        )
    }

    @Test
    fun fenced_code_with_pipes_is_not_parsed_as_a_table() {
        val blocks = parseFlatMarkdownBlocks(
            """
            ```text
            | key | value |
            | --- | --- |
            ```
            """.trimIndent()
        )

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MarkdownTextBlock)
    }

    @Test
    fun short_columns_stay_narrow_while_long_text_gets_a_bounded_readable_column() {
        val table = MarkdownTableBlock(
            headers = listOf("序号", "型号", "价格"),
            rows = listOf(
                listOf("1", "倍思 20000毫安自带线充电宝 30W快充", "¥89.9"),
                listOf("2", "倍思 20000毫安双线快充", "¥119")
            )
        )

        assertEquals(listOf(56, 196, 66), compactMarkdownTableColumnWidths(table))
    }

    @Test
    fun markdown_links_size_from_the_label_instead_of_the_url() {
        val table = MarkdownTableBlock(
            headers = listOf("项目", "链接"),
            rows = listOf(listOf("A", "[查看](https://example.com/a/very/long/path)"))
        )

        assertEquals(listOf(56, 56), compactMarkdownTableColumnWidths(table))
    }

    @Test
    fun table_supports_single_hyphen_and_column_alignments() {
        val blocks = parseFlatMarkdownBlocks(
            """
            | 左对齐 | 居中 | 右对齐 |
            | :- | :-: | -: |
            | A | B | C |
            """.trimIndent()
        )

        assertEquals(1, blocks.size)
        val table = blocks.single() as MarkdownTableBlock
        assertEquals(listOf("左对齐", "居中", "右对齐"), table.headers)
        assertEquals(listOf(listOf("A", "B", "C")), table.rows)
        assertEquals(
            listOf(
                androidx.compose.ui.text.style.TextAlign.Start,
                androidx.compose.ui.text.style.TextAlign.Center,
                androidx.compose.ui.text.style.TextAlign.End
            ),
            table.alignments
        )
    }

    @Test
    fun table_renderer_uses_content_widths_and_a_single_compact_frame() {
        val source = File("src/main/java/com/droidance/ui/components/FlatMarkdown.kt").readText()

        assertTrue(source.contains("compactMarkdownTableColumnWidths(table)"))
        assertTrue(source.contains("AppShapes.Control"))
        assertFalse(source.contains("Modifier.width(148.dp)"))
    }
}
