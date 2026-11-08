package com.xiaojiwei.memao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.model.Draft
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import java.time.Instant
import java.time.LocalDate
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
    val title=page.page.title+if(notes)s.tag?.let {" #$it"}.orEmpty() else ""
    key(page.accountId,page.scrollKey) {
        TrackPageScroll(page)
        val refreshState=rememberPullToRefreshState()
        Column(Modifier.fillMaxSize().testTag("feedPage")) {
            MmTopBar(title)
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pullToRefresh(
                state=refreshState,isRefreshing=s.refreshing,enabled=!s.loading,onRefresh=page::pullRefresh,
            )) {
                if(!s.cacheReady)Column(Modifier.fillMaxSize().padding(horizontal=24.dp,vertical=16.dp)) {
                    repeat(3) {SkeletonRow()}
                }
                else LazyColumn(Modifier.fillMaxSize().testTag("feedList").semantics {
                    if(!s.loading)customActions=listOf(CustomAccessibilityAction(if(notes)"刷新笔记" else "刷新归档") {page.pullRefresh();true})
                },state=page.listState,contentPadding=PaddingValues(horizontal=if(compact)20.dp else 24.dp,vertical=16.dp)) {
                    item(key="header") {
                        Column {
                            Text(if(notes)LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")) else "已归档的记录",color=c.secondary,style=MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(16.dp))
                            if(notes) {
                                MmField(s.keyword,vm::setKeyword,"",placeholder="搜索你的记录",inputTag="search",trailing={if(s.keyword.isNotEmpty())MmIconButton(MmIcon.CLOSE,"清除关键词"){vm.setKeyword("")}else Box(Modifier.size(48.dp),contentAlignment=Alignment.Center){LineIcon(MmIcon.SEARCH,tint=c.secondary)}})
                                Spacer(Modifier.height(16.dp))
                            }
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
                        val filtered=notes&&(s.keyword.isNotBlank()||s.tag!=null)
                        EmptyFeed(if(filtered)"没有找到记录" else if(!notes)"归档为空" else "写下此刻的想法",
                            if(shared.offline)"当前只搜索已缓存的内容，联网后可以查找更多。"
                            else if(filtered)"试试其他关键词或标签。"
                            else if(!notes)"归档后的笔记会出现在这里，可随时恢复。"
                            else "文字、图片或一段音频，都值得留住。",
                            icon=if(filtered)MmIcon.SEARCH else if(!notes)MmIcon.ARCHIVE else MmIcon.NOTES)
                    }
                    items(s.memos,key={it.name}) {memo->MemoRow(memo,shared.selected?.name==memo.name,audio,vm,onGallery,compact)}
                    if(s.hasMore)item {Box(Modifier.fillMaxWidth().padding(top=12.dp),contentAlignment=Alignment.Center){MmButton(if(s.loading&&!s.refreshing)"正在加载…" else "加载更多",page::loadMore,enabled=!s.loading)}}
                    else if(s.memos.isNotEmpty())item {Text(if(shared.offline)"以上为已缓存内容" else "已显示全部记录",Modifier.fillMaxWidth().padding(vertical=28.dp),color=c.secondary,style=MaterialTheme.typography.labelSmall)}
                }
                PullToRefreshDefaults.Indicator(
                    state=refreshState,isRefreshing=s.refreshing,
                    modifier=Modifier.align(Alignment.TopCenter).testTag("feedRefreshIndicator"),
                    containerColor=c.background,color=c.ink,
                )
            }
        }
    }
}

@Composable
private fun MemoRow(memo:Memo,selected:Boolean,audio:AudioState,vm:MemaoViewModel,onGallery:(List<MediaDisplay>,Int)->Unit,compact:Boolean) {
    val c=LocalMemaoColors.current
    Column(Modifier.fillMaxWidth().then(if(selected)Modifier.background(c.soft,RoundedCornerShape(8.dp))else Modifier).padding(vertical=20.dp)) {
        Row(Modifier.fillMaxWidth().clickable{vm.openMemo(memo)}.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(displayTime(memo.createTime),Modifier.weight(1f),color=c.secondary,style=MaterialTheme.typography.labelSmall)
            if(memo.visibility!="PRIVATE")Text(visibilityLabel(memo.visibility),color=c.secondary,style=MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("memo:${memo.name}").clickable(role=Role.Button){vm.openMemo(memo)}.padding(vertical=4.dp)) {
            if(memo.content.isNotBlank())Markdown(memo.content,compact=compact)
            else Text(if(memo.attachments.any {it.isImage})"图片记录" else "音频记录",color=c.secondary,style=MaterialTheme.typography.bodySmall)
        }
        if(memo.attachments.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            AttachmentList(memo.attachments.map(vm::media),audio,{vm.loadMedia(it)},{vm.loadMedia(it,true)},vm.audio::seekTo,onGallery,compact)
        }
    }
    MmRule()
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
private fun EmptyFeed(title:String,message:String,icon:MmIcon=MmIcon.NOTES) {
    val c=LocalMemaoColors.current
    Column(Modifier.fillMaxWidth().padding(vertical=64.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        LineIcon(icon,Modifier.size(MmIconSize.Empty),tint=c.secondary);Spacer(Modifier.height(20.dp))
        Text(title,color=c.ink,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(10.dp));Text(message,color=c.secondary,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SkeletonRow() {
    val c=LocalMemaoColors.current
    Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth(.35f).height(8.dp).background(c.soft));Box(Modifier.fillMaxWidth().height(12.dp).background(c.soft));Box(Modifier.fillMaxWidth(.7f).height(12.dp).background(c.soft))
    }
    MmRule()
}
