// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.components

import androidx.compose.foundation.BorderStroke
import com.droidance.ui.theme.AppShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidance.R
import com.droidance.ui.theme.AppMotion
import com.droidance.ui.theme.LocalAppMotionEnabled
import com.droidance.ui.theme.LocalAppIsDark
import com.droidance.ui.icons.DroidanceIcons
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.BulletHandler
import com.mikepenz.markdown.model.DefaultMarkdownAnnotator
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import android.icu.text.BreakIterator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Compact reading hierarchy, with stable completed blocks during incremental output. */
@Composable
fun FlatMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    streaming: Boolean = false
) {
    val document = remember { MarkdownRenderState() }
    val fragments = remember(content) { document.update(content) }
    val context = LocalContext.current
    val externalLinks = LocalUriHandler.current
    val annotator = remember { DefaultMarkdownAnnotator(SystemicTokenFallbackAnnotator) }
    val links = remember(context, externalLinks) { object : UriHandler {
        override fun openUri(uri: String) {
            if (com.droidance.core.agent.WorkspaceDeliverable.isLink(uri)) {
                context.startActivity(android.content.Intent(context, com.droidance.WorkspaceDeliverableActivity::class.java)
                    .setData(android.net.Uri.parse(uri)))
            } else externalLinks.openUri(uri)
        }
    } }
    CompositionLocalProvider(
        LocalUriHandler provides links,
        LocalOrderedListHandler provides OriginalNumberBulletHandler,
        LocalMarkdownAnnotator provides annotator,
    ) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fragments.forEachIndexed { index, fragment ->
                key(index) {
                    val blocks = remember(fragment) { parseFlatMarkdownBlocks(fragment) }
                    val active = streaming && index == fragments.lastIndex
                    blocks.forEach { block ->
                        when (block) {
                            is MarkdownTextBlock -> MarkdownBody(block.content, Modifier.fillMaxWidth(), textStyle, active)
                            is MarkdownTableBlock -> FlatMarkdownTable(block, textStyle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownBody(content: String, modifier: Modifier = Modifier, body: TextStyle, streaming: Boolean = false) {
    val reading = body.copy(
        lineBreak = if (streaming) LineBreak.Simple else LineBreak.Paragraph,
        hyphens = if (streaming) Hyphens.None else Hyphens.Auto
    )
    val bold = reading.copy(fontWeight = FontWeight(if (LocalAppIsDark.current) 660 else 700))
    val linkColor = MaterialTheme.colorScheme.primary
    val colors = markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        linkText = linkColor,
        codeText = MaterialTheme.colorScheme.onSurface,
        codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        inlineCodeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        inlineCodeText = MaterialTheme.colorScheme.onSurface,
        dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    )
    val customComponents = remember(body, streaming) {
        markdownComponents(
            paragraph = { model ->
                val annotated = buildAnnotatedString {
                    pushStyle(model.typography.paragraph.toSpanStyle())
                    buildMarkdownAnnotatedString(model.content, model.node)
                    pop()
                }
                MarkdownText(revealNewText(annotated, streaming), style = model.typography.paragraph)
            },
            codeFence = { model ->
                val code = extractCleanCodeFence(model.content, model.node)
                val language = extractCodeFenceLanguage(model.content, model.node)
                MarkdownCodeBox(code, language, body)
            }
        )
    }
    val annotator = remember { DefaultMarkdownAnnotator(SystemicTokenFallbackAnnotator) }

    Markdown(
        content = content,
        modifier = modifier,
        colors = colors,
        annotator = annotator,
        components = customComponents,
        padding = markdownPadding(
            block = 3.dp,
            list = 2.dp,
            listItemBottom = 2.dp,
            indentList = 8.dp,
        ),
        typography = markdownTypography(
            h1 = bold,
            h2 = bold,
            h3 = bold,
            h4 = bold,
            h5 = bold,
            h6 = bold,
            text = reading,
            paragraph = reading,
            ordered = reading,
            bullet = reading,
            list = reading,
            code = body.copy(fontFamily = FontFamily.Monospace),
            inlineCode = body.copy(fontFamily = FontFamily.Monospace),
            link = body.copy(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            )
        )
    )
}

/** Animate only an appended suffix; the annotated text and its link/selection semantics stay whole. */
@Composable
private fun revealNewText(content: AnnotatedString, streaming: Boolean): AnnotatedString {
    val motion = LocalAppMotionEnabled.current
    val color = MaterialTheme.colorScheme.onSurface
    var previous by remember { mutableStateOf(content.text) }
    var revealStart by remember { mutableIntStateOf(content.length) }
    val opacity = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(content.text, streaming, motion) {
        val appended = content.text.startsWith(previous) && content.length > previous.length
        revealStart = if (appended) graphemeBoundaryBefore(content.text, previous.length) else content.length
        previous = content.text
        if (appended && streaming && motion) {
            opacity.snapTo(0.4f)
            opacity.animateTo(1f, tween(AppMotion.PressMillis))
        } else opacity.snapTo(1f)
    }
    if (!streaming || !motion || opacity.value >= 1f || revealStart >= content.length) return content
    return AnnotatedString.Builder(content).apply {
        addStyle(SpanStyle(color = color.copy(alpha = opacity.value)), revealStart, content.length)
    }.toAnnotatedString()
}

internal fun graphemeBoundaryBefore(text: String, offset: Int): Int {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    return if (iterator.isBoundary(offset)) offset else iterator.preceding(offset).coerceAtLeast(0)
}

@Composable
private fun MarkdownCodeBox(code: String, language: String, textStyle: TextStyle) {
    val clipboardManager = LocalClipboardManager.current
    var justCopied by remember { mutableStateOf(false) }
    var copyRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(justCopied, copyRequest) {
        if (justCopied) {
            kotlinx.coroutines.delay(AppMotion.CopyFeedbackMillis)
            justCopied = false
        }
    }

    val displayLang = language.ifBlank { "code" }
    val copyLabel = stringResource(R.string.chat_copy)
    val copiedLabel = stringResource(R.string.chat_copied)
    val feedbackStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val labelWidthPx = remember(measurer, copyLabel, copiedLabel, feedbackStyle) {
        maxOf(measurer.measure(copyLabel, feedbackStyle).size.width,
            measurer.measure(copiedLabel, feedbackStyle.copy(fontWeight = FontWeight.SemiBold)).size.width)
    }
    val labelWidth = with(LocalDensity.current) { labelWidthPx.toDp() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = AppShapes.Control,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .padding(horizontal = 10.dp, vertical = 0.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayLang,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )

                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(code))
                            justCopied = true
                            copyRequest++
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AnimatedContent(
                        targetState = justCopied,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.88f, animationSpec = AppMotion.controlSpring()) + fadeIn(tween(AppMotion.FeedbackMillis)))
                                .togetherWith(scaleOut(targetScale = 0.88f, animationSpec = tween(AppMotion.FeedbackMillis)) + fadeOut(tween(AppMotion.PressMillis)))
                        },
                        label = "codeCopyIcon"
                    ) { copied ->
                        if (copied) {
                            Icon(
                                imageVector = DroidanceIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_conversation_copy),
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                    Text(
                        text = if (justCopied) copiedLabel else copyLabel,
                        modifier = Modifier.widthIn(min = labelWidth),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (justCopied) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (justCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
            )
            HorizontalReadingRegion(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = code,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = textStyle.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

internal fun extractCodeFenceLanguage(content: String, node: ASTNode): String {
    for (child in node.children) {
        if (child.type == MarkdownTokenTypes.FENCE_LANG) {
            val start = child.startOffset.coerceIn(0, content.length)
            val end = child.endOffset.coerceIn(start, content.length)
            val info = content.substring(start, end).trim()
            return info.split("\\s+".toRegex()).firstOrNull()?.lowercase().orEmpty()
        }
        if (child.type == MarkdownTokenTypes.EOL) break
    }
    return ""
}

internal fun extractCleanCodeFence(content: String, node: ASTNode): String {
    var firstEol: ASTNode? = null
    for (child in node.children) {
        if (child.type == MarkdownTokenTypes.EOL) {
            firstEol = child
            break
        }
    }
    val startOffset = firstEol?.endOffset ?: node.startOffset

    var fenceEnd: ASTNode? = null
    var fenceEndIndex = -1
    for (i in node.children.indices) {
        if (node.children[i].type == MarkdownTokenTypes.CODE_FENCE_END) {
            fenceEnd = node.children[i]
            fenceEndIndex = i
            break
        }
    }
    val endOffset = if (fenceEnd != null) {
        if (fenceEndIndex > 0 && node.children[fenceEndIndex - 1].type == MarkdownTokenTypes.EOL) {
            node.children[fenceEndIndex - 1].startOffset
        } else {
            fenceEnd.startOffset
        }
    } else {
        node.endOffset
    }

    if (startOffset > endOffset || startOffset >= content.length) return ""
    val clampedEnd = endOffset.coerceAtMost(content.length)
    return content.substring(startOffset, clampedEnd)
}

@Composable
private fun FlatMarkdownTable(table: MarkdownTableBlock, textStyle: TextStyle) {
    val columnWidths = remember(table) { compactMarkdownTableColumnWidths(table) }
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    HorizontalReadingRegion {
        Surface(
            modifier = Modifier.width(columnWidths.sum().dp),
            shape = AppShapes.Control,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.02f),
            border = BorderStroke(1.dp, dividerColor)
        ) {
            Column {
                MarkdownTableRow(table.headers, columnWidths, table.alignments, header = true, dividerColor, textStyle)
                table.rows.forEach { row ->
                    HorizontalDivider(thickness = 1.dp, color = dividerColor)
                    MarkdownTableRow(row, columnWidths, table.alignments, header = false, dividerColor, textStyle)
                }
            }
        }
    }
}

@Composable
private fun HorizontalReadingRegion(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val surface = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().drawWithContent {
        drawContent()
        val edge = 18.dp.toPx().coerceAtMost(size.width / 2)
        if (scroll.canScrollBackward) drawRect(
            Brush.horizontalGradient(listOf(surface, Color.Transparent), 0f, edge), size = Size(edge, size.height))
        if (scroll.canScrollForward) drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, surface), size.width - edge, size.width),
            topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height))
    }) {
        Box(Modifier.horizontalScroll(scroll)) { content() }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    columnWidths: List<Int>,
    alignments: List<TextAlign>,
    header: Boolean,
    dividerColor: androidx.compose.ui.graphics.Color,
    textStyle: TextStyle
) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .background(
                if (header) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)
            )
            .drawBehind {
                var boundaryX = 0f
                columnWidths.dropLast(1).forEach { width ->
                    boundaryX += width.dp.toPx()
                    drawLine(
                        color = dividerColor,
                        start = Offset(boundaryX, 0f),
                        end = Offset(boundaryX, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
    ) {
        columnWidths.forEachIndexed { index, width ->
            val align = alignments.getOrNull(index) ?: TextAlign.Start
            val contentAlignment = when (align) {
                TextAlign.Center -> Alignment.Center
                TextAlign.End -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }
            Box(
                modifier = Modifier.width(width.dp),
                contentAlignment = contentAlignment
            ) {
                val cell = cells.getOrNull(index).orEmpty()
                MarkdownBody(
                    content = if (header) "**$cell**" else cell,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    body = textStyle
                )
            }
        }
    }
}

/**
 * 库默认按列表内位置重排序号（index + 1），有序列表一旦被无序列表或段落打断就会拆成多个
 * ORDERED_LIST 节点，第二段重新从 1 开始。解析器保留了原文序号，这里直接沿用。
 */
private val OriginalNumberBulletHandler = BulletHandler { _, bullet, index ->
    val raw = bullet?.trimEnd()
    if (raw.isNullOrEmpty()) "${index + 1}. " else "$raw "
}

/**
 * multiplatform-markdown-renderer 库在 buildMarkdownAnnotatedString 中只枚举了少数几个 Token，
 * 未枚举的 AST 叶子 Token（如 GFMTokenTypes.TILDE、DOLLAR、HTML_TAG、CHECK_BOX 等）会被静默丢弃。
 * 这里定义通用兜底回退：未被样式容器消费的叶子 Token，一律提取原始切片文本追加到输出流中。
 */
private val LIBRARY_HANDLED_TYPES = setOf(
    MarkdownElementTypes.PARAGRAPH,
    MarkdownElementTypes.IMAGE,
    MarkdownElementTypes.EMPH,
    MarkdownElementTypes.STRONG,
    GFMElementTypes.STRIKETHROUGH,
    MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.AUTOLINK,
    MarkdownElementTypes.INLINE_LINK,
    MarkdownElementTypes.SHORT_REFERENCE_LINK,
    MarkdownElementTypes.FULL_REFERENCE_LINK,
    MarkdownTokenTypes.TEXT,
    GFMTokenTypes.GFM_AUTOLINK,
    MarkdownTokenTypes.SINGLE_QUOTE,
    MarkdownTokenTypes.DOUBLE_QUOTE,
    MarkdownTokenTypes.LPAREN,
    MarkdownTokenTypes.RPAREN,
    MarkdownTokenTypes.LBRACKET,
    MarkdownTokenTypes.RBRACKET,
    MarkdownTokenTypes.LT,
    MarkdownTokenTypes.GT,
    MarkdownTokenTypes.COLON,
    MarkdownTokenTypes.EXCLAMATION_MARK,
    MarkdownTokenTypes.BACKTICK,
    MarkdownTokenTypes.HARD_LINE_BREAK,
    MarkdownTokenTypes.EMPH,
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.WHITE_SPACE,
    MarkdownTokenTypes.BLOCK_QUOTE
)

internal val SystemicTokenFallbackAnnotator: AnnotatedString.Builder.(String, ASTNode) -> Boolean = { content, child ->
    if (child.parent?.type == GFMElementTypes.STRIKETHROUGH && child.type == GFMTokenTypes.TILDE) {
        false
    } else if (child.children.isEmpty() && child.type !in LIBRARY_HANDLED_TYPES) {
        val raw = content.substring(child.startOffset, child.endOffset)
        when (child.type) {
            MarkdownTokenTypes.HTML_TAG -> {
                if (raw.equals("<br>", ignoreCase = true) ||
                    raw.equals("<br/>", ignoreCase = true) ||
                    raw.equals("<br />", ignoreCase = true)
                ) {
                    append('\n')
                } else {
                    append(raw)
                }
            }
            GFMTokenTypes.CHECK_BOX -> {
                if (raw.contains('x', ignoreCase = true)) {
                    append("☑ ")
                } else {
                    append("☐ ")
                }
            }
            else -> {
                append(raw)
            }
        }
        true
    } else {
        false
    }
}

internal sealed interface FlatMarkdownBlock

internal data class MarkdownTextBlock(val content: String) : FlatMarkdownBlock

internal data class MarkdownTableBlock(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<TextAlign> = emptyList()
) : FlatMarkdownBlock

internal fun compactMarkdownTableColumnWidths(table: MarkdownTableBlock): List<Int> {
    return table.headers.indices.map { columnIndex ->
        val cells = buildList {
            add(table.headers[columnIndex])
            table.rows.forEach { row -> add(row.getOrNull(columnIndex).orEmpty()) }
        }
        val visualUnits = cells.maxOfOrNull(::markdownTableVisualUnits) ?: 0
        (visualUnits * 7 + 24).coerceIn(56, 196)
    }
}

private val MARKDOWN_LINK = Regex("""\[([^]]+)]\([^)]*\)""")
private val MARKDOWN_WIDTH_MARKERS = Regex("""[`*_~]""")

private fun markdownTableVisualUnits(markdown: String): Int {
    val plainText = markdown
        .replace(MARKDOWN_LINK) { match -> match.groupValues[1] }
        .replace(MARKDOWN_WIDTH_MARKERS, "")
    return plainText.fold(0) { total, character ->
        total + if (character.code <= 0x7f) 1 else 2
    }
}

internal fun parseFlatMarkdownBlocks(content: String): List<FlatMarkdownBlock> {
    val lines = content.lines()
    val blocks = mutableListOf<FlatMarkdownBlock>()
    var textStart = 0
    var index = 0
    var fence: String? = null

    fun appendText(until: Int) {
        val text = lines.subList(textStart, until).joinToString("\n").trim()
        if (text.isNotBlank()) blocks += MarkdownTextBlock(text)
    }

    while (index < lines.size) {
        val trimmed = lines[index].trimStart()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (marker != null) {
            fence = if (fence == null) marker else if (fence == marker) null else fence
            index++
            continue
        }
        if (fence == null && index + 1 < lines.size) {
            val headers = parseTableCells(lines[index])
            val separator = parseTableCells(lines[index + 1])
            val isTable = headers.size >= 2 &&
                separator.size == headers.size &&
                separator.all { TABLE_SEPARATOR.matches(it) }
            if (isTable) {
                appendText(index)
                val alignments = parseTableAlignments(separator)
                var rowIndex = index + 2
                val rows = mutableListOf<List<String>>()
                while (rowIndex < lines.size && lines[rowIndex].isNotBlank()) {
                    val row = parseTableCells(lines[rowIndex])
                    if (row.size < 2) break
                    rows += row
                    rowIndex++
                }
                blocks += MarkdownTableBlock(headers, rows, alignments)
                index = rowIndex
                textStart = rowIndex
                continue
            }
        }
        index++
    }
    appendText(lines.size)
    return blocks.ifEmpty { listOf(MarkdownTextBlock(content.trim())) }
}

private val TABLE_SEPARATOR = Regex("""^:?-+:?$""")

private fun parseTableAlignments(separatorCells: List<String>): List<TextAlign> {
    return separatorCells.map { cell ->
        val trimmed = cell.trim()
        val left = trimmed.startsWith(':')
        val right = trimmed.endsWith(':')
        when {
            left && right -> TextAlign.Center
            right -> TextAlign.End
            else -> TextAlign.Start
        }
    }
}

private fun parseTableCells(line: String): List<String> {
    var row = line.trim()
    if (row.startsWith('|')) row = row.drop(1)
    if (row.endsWith('|') && !row.endsWith("\\|")) row = row.dropLast(1)
    return row.split(Regex("""(?<!\\)\|"""))
        .map { it.replace("\\|", "|").trim() }
}
