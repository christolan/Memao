package com.xiaojiwei.memao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.model.Draft
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged

fun displayTime(raw:String):String=runCatching {DateTimeFormatter.ofPattern("yyyy.MM.dd  HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(raw))}.getOrDefault(raw.take(16).replace('T',' '))
fun displayTime(raw:Long):String=displayTime(Instant.ofEpochMilli(raw).toString())
fun visibilityLabel(value:String):String=when(value){"PUBLIC"->"公开";"PROTECTED"->"受保护";else->"私密"}
fun visibilityIcon(value:String):MmIcon=when(value){"PUBLIC"->MmIcon.PUBLIC;"PROTECTED"->MmIcon.PROTECTED;else->MmIcon.LOCK}

@Composable
fun NotesPage(vm:MemaoViewModel,page:NotesViewModel,audio:AudioState,onGallery:(List<MediaDisplay>,Int)->Unit,compact:Boolean) {
    MemoListPage(vm,page,audio,onGallery,compact)
}

@Composable
fun ArchivePage(vm:MemaoViewModel,page:ArchiveViewModel,audio:AudioState,onGallery:(List<MediaDisplay>,Int)->Unit,compact:Boolean) {
    MemoListPage(vm,page,audio,onGallery,compact)
}

@Composable
private fun TrackPageScroll(page:ListPageViewModel) {
    val owner=page.accountId
    val scrollKey=page.scrollKey
    val listState=page.listState
    LaunchedEffect(page,owner,scrollKey,listState) {
        snapshotFlow {listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset}
            .distinctUntilChanged().collect {page.saveScroll(owner,scrollKey,it.first,it.second)}
    }
}

@Composable
fun DraftsPage(vm:MemaoViewModel,page:DraftsViewModel,compact:Boolean) {
    val drafts=page.state.drafts
    val shared=vm.state
    val c=LocalMemaoColors.current
    key(page.accountId) {
        TrackPageScroll(page)
        Column(Modifier.fillMaxSize().testTag("feedPage")) {
            MmTopBar("草稿")
            if(!page.state.cacheReady)Box(Modifier.weight(1f).fillMaxWidth())
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("feedList"),state=page.listState,
                contentPadding=PaddingValues(horizontal=if(compact)20.dp else 24.dp,vertical=16.dp)) {
                item(key="header") {
                    Column {
                        Text("未发送内容只保存在这台设备",color=c.secondary,style=MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(16.dp));MmRule()
                    }
                }
                shared.error?.let {error->item(key="error") {
                    StateBanner(error,error=true,action={MmButton("关闭提示",vm::dismissError)})
                }}
                if(drafts.isEmpty())item {EmptyFeed("还没有草稿","编辑未发送的内容会自动保存在这里。",MmIcon.DRAFT)}
                items(drafts,key={it.id}) {draft->DraftRow(draft,shared.draftDetail?.id==draft.id||shared.editor?.id==draft.id){vm.showDraft(draft)}}
            }
        }
    }
}

@Composable
private fun MemoListPage(vm:MemaoViewModel,page:MemoListViewModel,audio:AudioState,onGallery:(List<MediaDisplay>,Int)->Unit,compact:Boolean) {
    val s=page.state
    val shared=vm.state
    val c=LocalMemaoColors.current
    val notes=page.page==UiPage.NOTES
    val topPadding=if(notes)0.dp else 16.dp
    val horizontalPadding=if(notes||compact)20.dp else 24.dp
    val title=page.page.title+if(notes){if(s.untagged)" 无标签" else ""}else ""
    key(page.accountId,page.scrollKey) {
        TrackPageScroll(page)
        val refreshState=rememberPullToRefreshState()
        Column(Modifier.fillMaxSize().testTag("feedPage")) {
            MmTopBar(title,filterTag=if(notes)s.tag else null,onClearTag={vm.setTag(null)}) {
                if(notes)MmIconButton(MmIcon.SEARCH,"搜索",modifier=Modifier.testTag("openSearch"),onClick=vm::openSearch)
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pullToRefresh(
                state=refreshState,isRefreshing=s.refreshing,enabled=!s.loading,onRefresh=page::pullRefresh,
            )) {
                if(!s.cacheReady)Column(Modifier.fillMaxSize().padding(start=horizontalPadding,end=horizontalPadding,top=topPadding,bottom=16.dp)) {
                    repeat(3) {SkeletonRow()}
                }
                else LazyColumn(Modifier.fillMaxSize().testTag("feedList").semantics {
                    if(!s.loading)customActions=listOf(CustomAccessibilityAction(if(notes)"刷新笔记" else "刷新归档") {page.pullRefresh();true})
                },state=page.listState,contentPadding=PaddingValues(start=horizontalPadding,end=horizontalPadding,top=topPadding,bottom=16.dp)) {
                    if(!notes)item(key="header") {
                        Column {
                            Text("已归档的记录",color=c.secondary,style=MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(16.dp))
                            MmRule()
                        }
                    }
                    shared.error?.let {error->item(key="error") {
                        Column(Modifier.padding(vertical=12.dp)) {
                            Text(error,color=c.danger,style=MaterialTheme.typography.bodySmall)
                            Row {MmButton("重试",{vm.dismissError();page.refresh()});MmButton("关闭提示",vm::dismissError)}
                            MmRule()
                        }
                    }}
                    if(s.loading&&!s.refreshing&&s.memos.isEmpty())items(3) {SkeletonRow()}
                    if((!s.loading||s.refreshing)&&s.memos.isEmpty())item {
                        val filtered=notes&&(s.tag!=null||s.untagged)
                        EmptyFeed(if(filtered)"没有找到记录" else if(!notes)"归档为空" else "写下此刻的想法",
                            if(shared.offline)"当前只显示已缓存的内容，联网后可查看更多。"
                            else if(filtered)"试试其他标签。"
                            else if(!notes)"归档后的笔记会出现在这里，可随时恢复。"
                            else "文字、图片或一段音频，都值得留住。",
                            icon=if(filtered)MmIcon.SEARCH else if(!notes)MmIcon.ARCHIVE else MmIcon.NOTES)
                    }
                    items(s.memos,key={it.name}) {memo->
                        MemoRow(memo,shared.editor?.memoName==memo.name,audio,vm,onGallery,compact,
                            expanded=page.expandedMemos[memo.name]==true,
                            onToggle={if(page.expandedMemos[memo.name]==true)page.expandedMemos.remove(memo.name)else page.expandedMemos[memo.name]=true})
                    }
                    if(s.hasMore)item {Box(Modifier.fillMaxWidth().padding(top=12.dp),contentAlignment=Alignment.Center){MmButton(if(s.loading&&!s.refreshing)"正在加载…" else "加载更多",page::loadMore,enabled=!s.loading)}}
                    else if(s.memos.isNotEmpty())item {Text(if(shared.offline)"以上为已缓存内容" else "已显示全部记录",Modifier.fillMaxWidth().padding(vertical=28.dp),color=c.secondary,style=MaterialTheme.typography.labelSmall)}
                }
                PullToRefreshDefaults.Indicator(
                    state=refreshState,isRefreshing=s.refreshing,
                    modifier=Modifier.align(Alignment.TopCenter).testTag("feedRefreshIndicator"),
                    containerColor=c.background,color=c.ink,
                )
                if (notes) shared.message?.let { message ->
                    MessageNotice(message, vm::dismissMessage, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable
internal fun MemoRow(memo:Memo,selected:Boolean,audio:AudioState,vm:MemaoViewModel,onGallery:(List<MediaDisplay>,Int)->Unit,compact:Boolean,expanded:Boolean,onToggle:()->Unit) {
    val c=LocalMemaoColors.current
    val clipboard=LocalClipboardManager.current
    val shared=vm.state
    var overflowing by remember(memo.content) {mutableStateOf(false)}
    var menuOpen by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    val toggle={if(overflowing)onToggle()}
    val edit={if(!shared.busy)vm.editMemo(memo)}
    val noteSpacing = 12.dp
    val footerIconSize = 15.dp
    val noteTextStyle = memoBodyTextStyle()
    val footerTextStyle = noteTextStyle.copy(fontSize = 13.sp)
    val footerLineHeight = with(LocalDensity.current) { footerTextStyle.lineHeight.toDp() }
    Column(Modifier.fillMaxWidth().then(if(selected)Modifier.background(c.soft,RoundedCornerShape(8.dp))else Modifier).padding(vertical=noteSpacing)) {
        Box(Modifier.fillMaxWidth().testTag("memo:${memo.name}")
            .semantics {stateDescription=if(!overflowing)"全文" else if(expanded)"已展开" else "已折叠"}
            .combinedClickable(role=Role.Button,onClickLabel=if(overflowing)if(expanded)"收起正文" else "展开正文" else null,
                onClick=toggle,onLongClickLabel="编辑笔记",onLongClick=edit)) {
            if(memo.content.isNotBlank())FoldableMarkdown(memo.content,expanded,{overflowing=it},toggle,edit,compact=compact,tags=memo.tags,onTagClick=vm::filterByBodyTag)
            else Text(if(memo.attachments.any {it.isImage})"图片记录" else "音频记录",color=c.secondary,style=noteTextStyle)
        }
        if (overflowing) {
            Text(
                if (expanded) "收起" else "全文",
                modifier = Modifier.widthIn(min = 48.dp).testTag("memoToggle:${memo.name}")
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起正文" else "展开正文",
                        onClick = onToggle,
                        onLongClickLabel = "编辑笔记",
                        onLongClick = edit,
                    ),
                color = c.memoReadAction,
                style = noteTextStyle,
                maxLines = 1,
            )
        }
        if(memo.attachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            AttachmentList(memo.attachments.map(vm::media),audio,{vm.loadMedia(it)},{vm.loadMedia(it,true)},vm.audio::seekTo,onGallery,feedLayout=true)
        }
        Spacer(Modifier.height(noteSpacing))
        Row(
            modifier = Modifier.fillMaxWidth().testTag("memoFooter:${memo.name}"),
            // The 30 dp menu's automatic 48 dp touch target extends 9 dp on either side.
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    displayTime(memo.createTime),
                    modifier = Modifier.weight(1f).testTag("memoTime:${memo.name}")
                        .combinedClickable(onClick = {}, onLongClickLabel = "编辑笔记", onLongClick = edit),
                    color = c.memoFooter,
                    style = footerTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (memo.visibility != "PRIVATE") {
                    Text(visibilityLabel(memo.visibility), color = c.memoFooter, style = noteTextStyle, maxLines = 1)
                }
            }
            Box {
                Box(
                    modifier = Modifier.size(width = 30.dp, height = footerLineHeight).testTag("memoActions:${memo.name}")
                        .clickable(role = Role.Button) { menuOpen = true }
                        .semantics { contentDescription = "笔记操作" },
                    contentAlignment = Alignment.Center,
                ) {
                    LineIcon(MmIcon.MORE, Modifier.size(footerIconSize), c.memoFooter)
                }
                DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false},containerColor=c.background) {
                    DropdownMenuItem(text={Text("编辑",color=c.ink)},leadingIcon={LineIcon(MmIcon.EDIT)},enabled=!shared.busy,onClick={menuOpen=false;edit()})
                    DropdownMenuItem(text={Text("复制正文",color=c.ink)},leadingIcon={LineIcon(MmIcon.COPY)},onClick={menuOpen=false;clipboard.setText(AnnotatedString(memo.content));vm.notify("已复制正文")})
                    DropdownMenuItem(text={Text(if(memo.archived)"取消归档" else "归档笔记",color=c.ink)},leadingIcon={LineIcon(if(memo.archived)MmIcon.UNARCHIVE else MmIcon.ARCHIVE)},enabled=!shared.busy&&!shared.offline,onClick={menuOpen=false;vm.archiveMemo(memo)})
                    DropdownMenuItem(text={Text("删除笔记",color=c.danger)},leadingIcon={LineIcon(MmIcon.DELETE,tint=c.danger)},enabled=!shared.busy&&!shared.offline,onClick={menuOpen=false;deleting=true})
                }
            }
        }
    }
    MmRule()
    if(deleting)MmDialog("删除这条笔记？","此操作会删除服务端的笔记及关联附件，无法在本 APP 中恢复。",onDismiss={deleting=false},
        actions={MmButton("取消",{deleting=false});MmButton("删除笔记",{deleting=false;vm.deleteMemo(memo)},danger=true)})
}

@Composable
private fun DraftRow(draft:Draft,selected:Boolean,onClick:()->Unit) {
    val c=LocalMemaoColors.current
    Column(Modifier.fillMaxWidth().testTag("draft:${draft.id}").clip(RoundedCornerShape(8.dp)).then(if(selected)Modifier.background(c.soft)else Modifier).clickable(onClick=onClick).padding(vertical=20.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(if(draft.isNew)"新建草稿" else "修改草稿",Modifier.weight(1f),color=c.secondary,style=MaterialTheme.typography.labelSmall)
            Text(displayTime(draft.updatedAt),color=c.secondary,style=MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(10.dp))
        if(draft.content.isNotBlank())Markdown(draft.content,compact=true)else Text("附件记录",color=c.ink)
        if(draft.attachments.isNotEmpty()){Spacer(Modifier.height(10.dp));Text("${draft.attachments.count {it.isImage}} 张图片 · ${draft.attachments.count {it.isAudio}} 个音频",color=c.secondary,style=MaterialTheme.typography.labelSmall)}
        draft.error?.let {Text(it,color=c.danger,style=MaterialTheme.typography.bodySmall)}
    }
    MmRule()
}

@Composable
internal fun EmptyFeed(title:String,message:String,icon:MmIcon=MmIcon.NOTES) {
    val c=LocalMemaoColors.current
    Column(Modifier.fillMaxWidth().padding(vertical=64.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        LineIcon(icon,Modifier.size(MmIconSize.Empty),tint=c.secondary);Spacer(Modifier.height(20.dp))
        Text(title,color=c.ink,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(10.dp));Text(message,color=c.secondary,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SkeletonRow() {
    val c=LocalMemaoColors.current
    Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth(.35f).height(8.dp).background(c.soft));Box(Modifier.fillMaxWidth().height(12.dp).background(c.soft));Box(Modifier.fillMaxWidth(.7f).height(12.dp).background(c.soft))
    }
    MmRule()
}
