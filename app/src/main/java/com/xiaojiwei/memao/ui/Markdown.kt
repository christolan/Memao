package com.xiaojiwei.memao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.net.URI

private val markdownParser = Parser.builder()
    .extensions(listOf(TaskListItemsExtension.create()))
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .build()

/** CommonMark is only parsed to native text. HTML is shown literally and is never executed. */
@Composable
fun Markdown(content: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val root = remember(content) { markdownParser.parse(content) }
    val lines = remember(content) { content.lines() }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(if(compact) 6.dp else 12.dp)) {
            var child = root.firstChild
            while(child != null) {
                MarkdownBlock(child, lines, compact)
                child = child.next
            }
        }
    }
}

private fun source(node: Node, lines: List<String>): String = node.sourceSpans.joinToString("\n") { span ->
    lines.getOrNull(span.lineIndex)?.let { line ->
        val start = span.columnIndex.coerceIn(0,line.length)
        line.substring(start,(start+span.length).coerceAtMost(line.length))
    }.orEmpty()
}

@Composable
private fun MarkdownBlock(node: Node, lines: List<String>, compact: Boolean) {
    val c = LocalMemaoColors.current
    val body = if(compact) MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp,lineHeight=26.sp)
        else MaterialTheme.typography.bodyLarge
    when(node) {
        is Heading -> {
            val raw = source(node, lines)
            // Memos 0.31 disables Setext headings; preserve them as ordinary text here too.
            if(raw.lines().drop(1).any { it.trim().matches(Regex("=+|-+")) }) {
                Text(raw,color=c.ink,style=body)
            } else Text(inlineText(node,c.ink),color=c.ink,style=body.copy(
                fontSize=(if(compact) 18 else 24).sp,
                lineHeight=(if(compact) 28 else 34).sp,
                fontWeight=FontWeight.Medium,
            ))
        }
        is Paragraph -> Text(inlineText(node,c.ink),color=c.ink,style=body)
        is BlockQuote -> Row {
            Box(Modifier.width(2.dp).heightIn(min=28.dp).background(c.divider))
            Column(Modifier.padding(start=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Children(node,lines,compact) }
        }
        is BulletList, is OrderedList -> {
            var item=node.firstChild
            var index=(node as? OrderedList)?.markerStartNumber ?: 1
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                while(item!=null) {
                    val current=item
                    val first=current.firstChild
                    val task=first?.firstChild as? TaskListItemMarker
                    Row {
                        Text(if(task!=null) (if(task.isChecked) "☑  " else "☐  ") else if(node is OrderedList) "${index++}.  " else "•  ",color=c.secondary,style=body)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) { Children(current,lines,compact) }
                    }
                    item=current.next
                }
            }
        }
        is ThematicBreak -> MmRule()
        is FencedCodeBlock -> Text(source(node,lines).ifBlank { node.literal },color=c.secondary,style=body)
        is IndentedCodeBlock -> Text(source(node,lines).ifBlank { node.literal },color=c.secondary,style=body)
        is HtmlBlock -> Text(node.literal.trimEnd(),color=c.secondary,style=body)
        else -> {
            if(node.firstChild!=null) Column { Children(node,lines,compact) }
            else source(node,lines).takeIf { it.isNotBlank() }?.let { Text(it,color=c.ink,style=body) }
        }
    }
}

@Composable
private fun Children(node: Node, lines: List<String>, compact: Boolean) {
    var child=node.firstChild
    while(child!=null) { MarkdownBlock(child,lines,compact);child=child.next }
}

private fun inlineText(node: Node, color: Color): AnnotatedString {
    val builder=AnnotatedString.Builder()
    fun visitChildren(parent: Node) {
        var child=parent.firstChild
        while(child!=null) {
            val n=child
            when(n) {
                is org.commonmark.node.Text -> builder.append(n.literal)
                is SoftLineBreak, is HardLineBreak -> builder.append("\n")
                is StrongEmphasis -> {builder.pushStyle(SpanStyle(fontWeight=FontWeight.Bold));visitChildren(n);builder.pop()}
                is Emphasis -> {builder.pushStyle(SpanStyle(fontStyle=FontStyle.Italic));visitChildren(n);builder.pop()}
                is Link -> {
                    val scheme=runCatching { URI(n.destination).scheme?.lowercase() }.getOrNull()
                    if(scheme in setOf("http","https","mailto")) {
                        builder.pushLink(LinkAnnotation.Url(n.destination,TextLinkStyles(style=SpanStyle(color=color,textDecoration=TextDecoration.Underline))))
                        visitChildren(n);builder.pop()
                    } else visitChildren(n)
                }
                is Image -> {builder.append("[图片：");visitChildren(n);builder.append("]")}
                is Code -> builder.append("`"+n.literal+"`")
                is HtmlInline -> builder.append(n.literal)
                is TaskListItemMarker -> Unit
                else -> visitChildren(n)
            }
            child=n.next
        }
    }
    visitChildren(node)
    return builder.toAnnotatedString()
}
