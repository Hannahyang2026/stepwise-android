package com.hannah.stepwise

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.net.URI
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.parser.Parser

// CommonMark 将正文拆成节点。每次收到新文字时重新解析，未闭合的格式会随下一块自动更新。
private val replyParser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

@Composable
internal fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
    val document = remember(text) { replyParser.parse(text) }
    SelectionContainer(modifier) { MarkdownBlocks(document) }
}

// 块节点决定版式。列表序号沿用原文，代码和表格过宽时可横向滚动。
@Composable
private fun MarkdownBlocks(container: Node) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        container.children().forEach { block ->
            when (block) {
                is Heading -> Text(inlineText(block), style = if (block.level <= 2)
                    MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                is BlockQuote -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("│", color = stepAccent, fontWeight = FontWeight.Bold)
                    MarkdownBlocks(block)
                }
                is ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.children().forEachIndexed { index, item ->
                        val marker = if (block is OrderedList) "${(block.markerStartNumber ?: 1) + index}." else "•"
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(marker, color = stepAccent)
                            MarkdownBlocks(item)
                        }
                    }
                }
                is FencedCodeBlock -> CodeBlock(block.literal)
                is IndentedCodeBlock -> CodeBlock(block.literal)
                is ThematicBreak -> HorizontalDivider(color = stepLine)
                is TableBlock -> MarkdownTable(block)
                is HtmlBlock -> Text(block.literal)
                else -> Text(inlineText(block))
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Box(Modifier.fillMaxWidth().background(stepPaper, stepShape).horizontalScroll(rememberScrollState())) {
        Text(code.trimEnd('\n'), Modifier.padding(8.dp), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, softWrap = false)
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        table.children().forEach { section ->
            section.children().forEach { row ->
                Row {
                    row.children().forEach { cell ->
                        Text(inlineText(cell), Modifier.width(136.dp).padding(6.dp),
                            fontWeight = if (section is TableHead) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = stepLine)
            }
        }
    }
}

// 行内节点只生成可选中的文本。用户主动点按 HTTP(S) 链接才打开网页，HTML 和远程图片以文字显示。
private fun inlineText(node: Node): AnnotatedString = buildAnnotatedString { appendChildren(node) }

private fun AnnotatedString.Builder.appendChildren(node: Node) {
    for (child in node.children()) {
        when (child) {
            is MarkdownTextNode -> append(child.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Code -> styled(SpanStyle(fontFamily = FontFamily.Monospace, background = stepPaper)) { append(child.literal) }
            is HtmlInline -> append(child.literal)
            is StrongEmphasis -> styled(SpanStyle(fontWeight = FontWeight.Bold)) { appendChildren(child) }
            is Emphasis -> styled(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren(child) }
            is Link -> {
                val link = runCatching { URI(child.destination) }.getOrNull()
                if (link?.scheme?.lowercase() in setOf("https", "http") && link?.host != null) {
                    pushLink(LinkAnnotation.Url(child.destination, TextLinkStyles(
                        style = SpanStyle(color = stepAccent, textDecoration = TextDecoration.Underline))))
                    appendChildren(child)
                    pop()
                } else appendChildren(child)
            }
            else -> appendChildren(child)
        }
    }
}

private inline fun AnnotatedString.Builder.styled(style: SpanStyle, content: () -> Unit) {
    pushStyle(style)
    content()
    pop()
}
