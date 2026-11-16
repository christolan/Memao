package com.xiaojiwei.memao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import java.io.File

data class MediaDisplay(
    val key: String, val filename: String, val type: String, val file: File?,
    val width: Int = 0, val height: Int = 0, val loading: Boolean = false,
) {
    val isImage: Boolean get()=type.startsWith("image/")
    val isAudio: Boolean get()=type.startsWith("audio/")
}

private val FeedSingleImageMaxEdge = 200.dp
private const val FeedImageColumns = 3
private val FeedImageCellMaxSize = 100.dp
private val FeedImageGap = 3.dp
private val FeedImageGridMaxWidth = FeedImageCellMaxSize * FeedImageColumns + FeedImageGap * (FeedImageColumns - 1)

@Composable
fun AttachmentList(
    items: List<MediaDisplay>, audio: AudioState, onLoad: (MediaDisplay) -> Unit,
    onPlay: (MediaDisplay) -> Unit, onSeek: (Long) -> Unit,
    onGallery: (List<MediaDisplay>,Int) -> Unit, feedLayout: Boolean = false,
    onRemove: ((MediaDisplay) -> Unit)? = null,
) {
    val images = items.filter { it.isImage }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (images.size == 1) {
            MediaImage(images.single(), onLoad, { onGallery(images, 0) }, feedLayout = feedLayout, onRemove = onRemove)
        } else if (images.isNotEmpty()) {
            val columns = if (feedLayout) FeedImageColumns else 2
            val gap = if (feedLayout) FeedImageGap else 6.dp
            val grid = if (feedLayout) Modifier.widthIn(max = FeedImageGridMaxWidth) else Modifier
            Column(grid, verticalArrangement = Arrangement.spacedBy(if (feedLayout) gap else 10.dp)) {
                images.chunked(columns).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEachIndexed { columnIndex, item ->
                            MediaImage(
                                item, onLoad, { onGallery(images, rowIndex * columns + columnIndex) },
                                modifier = Modifier.weight(1f), square = true, feedLayout = feedLayout, onRemove = onRemove,
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        items.filter {it.isAudio}.forEach { item ->
            AudioRow(item,audio,{onPlay(item)},onSeek,if(onRemove!=null)({onRemove(item)})else null)
        }
        items.filter {!it.isImage&&!it.isAudio}.forEach { item ->
            val c=LocalMemaoColors.current
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                LineIcon(MmIcon.FILE,tint=c.secondary);Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.filename,color=c.ink,style=MaterialTheme.typography.bodySmall)
                    Text("已有附件 · 保留原文件",color=c.secondary,style=MaterialTheme.typography.labelSmall)
                }
                if(onRemove!=null)MmIconButton(MmIcon.CLOSE,"移除附件") {onRemove(item)}
            }
        }
    }
}

@Composable
private fun MediaImage(
    item: MediaDisplay, onLoad: (MediaDisplay) -> Unit, onClick: () -> Unit,
    modifier: Modifier = Modifier, square: Boolean = false, feedLayout: Boolean = false,
    onRemove: ((MediaDisplay) -> Unit)? = null,
) {
    val c=LocalMemaoColors.current
    val metadataRatio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1.5f
    var loadedRatio by remember(item.key, item.file, metadataRatio) { mutableFloatStateOf(metadataRatio) }
    LaunchedEffect(item.key,item.file) {if(item.file==null&&!item.loading)onLoad(item)}
    val size = if (feedLayout && !square) {
        modifier.sizeIn(maxWidth = FeedSingleImageMaxEdge, maxHeight = FeedSingleImageMaxEdge)
    } else {
        modifier.fillMaxWidth()
    }
    val ratio = when {
        square -> 1f
        feedLayout -> loadedRatio.coerceIn(.02f, 50f)
        else -> loadedRatio.coerceIn(.1f, 10f)
    }
    Box(size.aspectRatio(ratio).clip(RoundedCornerShape(4.dp)).background(c.soft)) {
        if(item.file!=null) AsyncImage(
            model=item.file,contentDescription=item.filename,modifier=Modifier.fillMaxSize().clickable(onClick=onClick),
            contentScale=if(square)ContentScale.Crop else ContentScale.Fit,
            onSuccess = { result ->
                val image = result.result.image
                if (!square && image.width > 0 && image.height > 0) {
                    loadedRatio = image.width.toFloat() / image.height
                }
            },
        ) else Column(
            Modifier.fillMaxSize().clickable { onLoad(item) }.semantics { contentDescription = item.filename },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (feedLayout) 4.dp else 8.dp, Alignment.CenterVertically),
        ) {
            LineIcon(MmIcon.IMAGE, Modifier.size(if (feedLayout) MmIconSize.Toolbar else MmIconSize.Empty), tint = c.secondary)
            val label = if (feedLayout) {
                if (item.loading) "加载中…" else "点击重试"
            } else {
                if (item.loading) "正在加载图片…" else "图片尚未缓存 · 点击重试"
            }
            Text(label, color = c.secondary, style = MaterialTheme.typography.bodySmall,
                maxLines = if (feedLayout) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
        if(onRemove!=null)Box(Modifier.align(Alignment.TopEnd).background(c.background.copy(alpha=.9f),RoundedCornerShape(bottomStart=8.dp))) {
            MmIconButton(MmIcon.CLOSE,"移除图片") {onRemove(item)}
        }
    }
}

@Composable
private fun AudioRow(item:MediaDisplay,audio:AudioState,onPlay:()->Unit,onSeek:(Long)->Unit,onRemove:(()->Unit)?) {
    val c=LocalMemaoColors.current
    val active=item.file?.absolutePath==audio.path
    val duration=if(active)audio.duration else 0L
    var drag by remember(item.key) {mutableStateOf<Float?>(null)}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.soft).padding(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            MmIconButton(if(active&&audio.playing)MmIcon.PAUSE else MmIcon.PLAY,if(active&&audio.playing)"暂停音频" else "播放音频",enabled=!item.loading,onClick=onPlay)
            Column(Modifier.weight(1f)) {
                Text(item.filename,color=c.ink,style=MaterialTheme.typography.bodySmall)
                Text(if(item.loading)"正在准备音频…" else if(active)formatDuration(audio.position)+" / "+formatDuration(duration) else "点击播放",color=c.secondary,style=MaterialTheme.typography.labelSmall)
            }
            if(onRemove!=null)MmIconButton(MmIcon.CLOSE,"移除音频",onClick=onRemove)
        }
        if(active) {
            Slider(
                value=drag?:audio.position.toFloat().coerceIn(0f,duration.toFloat().coerceAtLeast(1f)),
                onValueChange={drag=it},
                onValueChangeFinished={drag?.let {onSeek(it.toLong())};drag=null},
                valueRange=0f..duration.toFloat().coerceAtLeast(1f),enabled=duration>0,
                modifier=Modifier.fillMaxWidth().height(48.dp),
                colors=SliderDefaults.colors(thumbColor=c.ink,activeTrackColor=c.ink,inactiveTrackColor=c.divider),
            )
            audio.error?.let {Text(it,color=c.danger,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

private fun formatDuration(ms:Long):String {val seconds=ms/1000;return "%d:%02d".format(seconds/60,seconds%60)}

@Composable
fun ImageGallery(items:List<MediaDisplay>,initialIndex:Int,onLoad:(MediaDisplay)->Unit,onClose:()->Unit) {
    val c=LocalMemaoColors.current
    val pager=rememberPagerState(initialPage=initialIndex.coerceIn(0,(items.size-1).coerceAtLeast(0))) {items.size}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                MmIconButton(MmIcon.CLOSE,"关闭图片",onClick=onClose)
                Spacer(Modifier.weight(1f));Text("${pager.currentPage+1} / ${items.size}",color=c.secondary,style=MaterialTheme.typography.bodySmall);Spacer(Modifier.width(24.dp))
            }
            HorizontalPager(state=pager,modifier=Modifier.weight(1f)) { index ->
                val item=items[index]
                var scale by rememberSaveable(item.key) {mutableFloatStateOf(1f)}
                var x by rememberSaveable(item.key) {mutableFloatStateOf(0f)}
                var y by rememberSaveable(item.key) {mutableFloatStateOf(0f)}
                var viewport by remember {mutableStateOf(IntSize.Zero)}
                val transform=rememberTransformableState {zoom,pan,_ ->
                    scale=(scale*zoom).coerceIn(1f,5f)
                    if(scale==1f){x=0f;y=0f}else {
                        val limitX=viewport.width*(scale-1)/2
                        val limitY=viewport.height*(scale-1)/2
                        x=(x+pan.x).coerceIn(-limitX,limitX)
                        y=(y+pan.y).coerceIn(-limitY,limitY)
                    }
                }
                LaunchedEffect(item.key,item.file) {if(item.file==null&&!item.loading)onLoad(item)}
                Box(Modifier.fillMaxSize().onSizeChanged {viewport=it}.clip(RoundedCornerShape(0.dp)).transformable(transform,canPan={scale>1f}),contentAlignment=Alignment.Center) {
                    if(item.file!=null)AsyncImage(model=item.file,contentDescription=item.filename,contentScale=ContentScale.Fit,
                        modifier=Modifier.fillMaxSize().graphicsLayer {scaleX=scale;scaleY=scale;translationX=x;translationY=y})
                    else MmButton(if(item.loading)"正在加载图片…" else "图片尚未缓存，点击重试",{onLoad(item)},enabled=!item.loading)
                }
            }
            Text("双指缩放 · 左右滑动查看",Modifier.align(Alignment.CenterHorizontally).padding(20.dp),color=c.secondary,style=MaterialTheme.typography.bodySmall)
        }
    }
}
