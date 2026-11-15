package com.xiaojiwei.memao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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

private data class MemoIndent(
    val marker: String = "",
    val checked: Boolean? = null,
    val quote: Boolean = false,
    val visible: Boolean = true,
)

private data class MemoTextBlock(
    val text: AnnotatedString,
    val indents: List<MemoIndent>,
    val gap: Int,
    val heading: Boolean = false,
    val secondary: Boolean = false,
    val rule: Boolean = false,
)

/** Keep block decorations while exposing each text layout to one shared visual-line budget. */
private fun memoTextBlocks(root: Node, lines: List<String>, color: Color, compact: Boolean): List<MemoTextBlock> {
    val result=mutableListOf<MemoTextBlock>()
    fun visit(node: Node, indents: List<MemoIndent>, gap: Int) {
        fun add(text: AnnotatedString, heading: Boolean=false, secondary: Boolean=false, rule: Boolean=false) {
            result+=MemoTextBlock(text,indents,gap,heading,secondary,rule)
        }
        fun children(parent: Node, nested: List<MemoIndent>, spacing: Int) {
            var child=parent.firstChild
            var first=true
            while(child!=null) {
                if(child !is TaskListItemMarker) {
                    visit(child,nested,if(first)gap else spacing)
                    first=false
                }
                child=child.next
            }
        }
        when(node) {
            is Heading -> {
                val raw=source(node,lines)
                if(raw.lines().drop(1).any {it.trim().matches(Regex("=+|-+"))})add(AnnotatedString(raw))
                else add(inlineText(node,color,nativeLinks=false),heading=true)
            }
            is Paragraph -> add(inlineText(node,color,nativeLinks=false))
            is BlockQuote -> children(node,indents+MemoIndent(quote=true),8)
            is BulletList, is OrderedList -> {
                var item=node.firstChild
                var index=(node as? OrderedList)?.markerStartNumber ?: 1
                var first=true
                while(item!=null) {
                    val task=item.firstChild as? TaskListItemMarker
                    val marker=MemoIndent(marker=if(node is OrderedList)"${index++}." else "•",checked=task?.isChecked)
                    val start=result.size
                    visit(item,indents+marker,if(first)gap else 4)
                    // A wrapped/nested item's subsequent blocks retain the indent, not a second bullet.
                    for(i in start+1 until result.size) {
                        val block=result[i]
                        result[i]=block.copy(indents=block.indents.mapIndexed {j,indent->if(j==indents.size)indent.copy(visible=false)else indent})
                    }
                    first=false
                    item=item.next
                }
            }
            is TaskListItemMarker -> Unit
            is ThematicBreak -> add(AnnotatedString(""),rule=true)
            is FencedCodeBlock -> add(AnnotatedString(source(node,lines).ifBlank {node.literal}),secondary=true)
            is IndentedCodeBlock -> add(AnnotatedString(source(node,lines).ifBlank {node.literal}),secondary=true)
            is HtmlBlock -> add(AnnotatedString(node.literal.trimEnd()),secondary=true)
            else -> if(node.firstChild!=null)children(node,indents,if(node is Document)if(compact)6 else 12 else 4)
                else source(node,lines).takeIf {it.isNotBlank()}?.let {add(AnnotatedString(it))}
        }
    }
    visit(root,emptyList(),0)
    return result
}

@Composable
internal fun memoBodyTextStyle(compact: Boolean): TextStyle {
    val style = if (compact) MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 26.sp)
        else MaterialTheme.typography.bodyLarge
    return style.copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
}

/** Six rendered lines across all Markdown blocks, including wraps caused by width or font scale. */
@Composable
fun FoldableMarkdown(
    content: String,
    expanded: Boolean,
    onOverflowChange: (Boolean)->Unit,
    onClick: ()->Unit,
    onLongClick: ()->Unit,
    modifier: Modifier=Modifier,
    compact: Boolean=false,
) {
    val c=LocalMemaoColors.current
    val density=LocalDensity.current
    val measurer=rememberTextMeasurer()
    val uriHandler=LocalUriHandler.current
    val haptics=LocalHapticFeedback.current
    val latestClick by rememberUpdatedState(onClick)
    val latestLongClick by rememberUpdatedState(onLongClick)
    val body=memoBodyTextStyle(compact)
    val heading=body.copy(fontSize=(if(compact)18 else 24).sp,lineHeight=(if(compact)28 else 34).sp,fontWeight=FontWeight.Medium)
    val blocks=remember(content,c.ink,compact) {memoTextBlocks(markdownParser.parse(content),content.lines(),c.ink,compact)}
    val iconSize=with(density) {body.fontSize.toDp()}.coerceAtLeast(MmIconSize.Toolbar)
    val markerLineHeight=with(density) {body.lineHeight.toDp()}.coerceAtLeast(iconSize)
    fun indentWidth(indent:MemoIndent)=when {
        indent.quote -> 16.dp
        indent.checked!=null -> iconSize+8.dp
        else -> with(density) {measurer.measure(AnnotatedString(indent.marker+"  "),body).size.width.toDp()}
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widths=blocks.map {block->block.indents.map(::indentWidth)}
        val layouts=blocks.mapIndexed {index,block->
            if(block.rule)null else measurer.measure(block.text,if(block.heading)heading else body,
                constraints=Constraints(maxWidth=with(density){(maxWidth-widths[index].fold(0.dp){a,b->a+b}).roundToPx()}.coerceAtLeast(1)))
        }
        val overflowing=layouts.sumOf {it?.lineCount?:0}>6
        SideEffect {onOverflowChange(overflowing)}
        Column {
            var remaining=if(expanded)Int.MAX_VALUE else 6
            blocks.forEachIndexed {index,block->
                if(remaining>0) {
                    val lineCount=layouts[index]?.lineCount?:0
                    val visibleLines=minOf(remaining,lineCount)
                    if(index>0)Spacer(Modifier.height(block.gap.dp))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        block.indents.forEachIndexed {indentIndex,indent->
                            val width=widths[index][indentIndex]
                            when {
                                indent.quote -> Box(Modifier.width(width).fillMaxHeight()) {Box(Modifier.width(2.dp).fillMaxHeight().background(c.divider))}
                                !indent.visible -> Spacer(Modifier.width(width))
                                indent.checked!=null -> Box(Modifier.width(width).height(markerLineHeight).semantics {contentDescription=if(indent.checked)"已完成待办" else "未完成待办"},contentAlignment=Alignment.CenterStart) {
                                    LineIcon(if(indent.checked)MmIcon.CHECKED else MmIcon.UNCHECKED,Modifier.size(iconSize),c.secondary)
                                }
                                else -> Text(indent.marker,Modifier.width(width),color=c.secondary,style=body)
                            }
                        }
                        if(block.rule)Box(Modifier.weight(1f)){MmRule()}
                        else {
                            var textLayout by remember(block.text) {mutableStateOf<TextLayoutResult?>(null)}
                            val links=block.text.getStringAnnotations("url",0,block.text.length)
                            Text(block.text,Modifier.weight(1f).testTag("memoLineText").pointerInput(block.text,links) {
                                detectTapGestures(
                                    onTap={point->
                                        val layout=textLayout
                                        val offset=layout?.getOffsetForPosition(point)
                                        val link=offset?.takeIf {at->
                                            layout!=null&&at<block.text.length&&
                                                at<layout.getLineEnd(layout.getLineForVerticalPosition(point.y),visibleEnd=true)&&
                                                layout.getBoundingBox(at).contains(point)
                                        }
                                            ?.let {at->links.firstOrNull {at>=it.start&&at<it.end}}
                                        if(link!=null)runCatching {uriHandler.openUri(link.item)} else latestClick()
                                    },
                                    onLongPress={haptics.performHapticFeedback(HapticFeedbackType.LongPress);latestLongClick()},
                                )
                            }.semantics {
                                if(links.isNotEmpty())customActions=links.map {link->CustomAccessibilityAction("打开链接："+block.text.text.substring(link.start,link.end)) {runCatching {uriHandler.openUri(link.item)}.isSuccess}}
                            },color=if(block.secondary)c.secondary else c.ink,style=if(block.heading)heading else body,
                                maxLines=visibleLines.coerceAtLeast(1),overflow=TextOverflow.Ellipsis,onTextLayout={textLayout=it})
                        }
                    }
                    remaining-=visibleLines
                }
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
                    // CommonMark prepends the marker to ListItem, before its Paragraph.
                    val task=current.firstChild as? TaskListItemMarker
                    Row {
                        if(task!=null) {
                            val density=LocalDensity.current
                            val iconSize=with(density) {body.fontSize.toDp()}.coerceAtLeast(MmIconSize.Toolbar)
                            val lineHeight=with(density) {body.lineHeight.toDp()}.coerceAtLeast(iconSize)
                            Box(Modifier.width(iconSize+8.dp).height(lineHeight).semantics {
                                contentDescription=if(task.isChecked)"已完成待办" else "未完成待办"
                            },contentAlignment=Alignment.CenterStart) {
                                LineIcon(if(task.isChecked)MmIcon.CHECKED else MmIcon.UNCHECKED,Modifier.size(iconSize),c.secondary)
                            }
                        } else Text(if(node is OrderedList) "${index++}.  " else "•  ",color=c.secondary,style=body)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) { Children(current,lines,compact) }
                    }
                    item=current.next
                }
            }
        }
        is TaskListItemMarker -> Unit // The list row renders its read-only icon.
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

private fun inlineText(node: Node, color: Color, nativeLinks: Boolean = true): AnnotatedString {
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
                        if(nativeLinks)builder.pushLink(LinkAnnotation.Url(n.destination,TextLinkStyles(style=SpanStyle(color=color,textDecoration=TextDecoration.Underline))))
                        else {
                            builder.pushStringAnnotation("url",n.destination)
                            builder.pushStyle(SpanStyle(color=color,textDecoration=TextDecoration.Underline))
                        }
                        visitChildren(n);builder.pop()
                        if(!nativeLinks)builder.pop()
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
