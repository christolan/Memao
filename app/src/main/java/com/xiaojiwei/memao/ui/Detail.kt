package com.xiaojiwei.memao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.model.ConflictChoice
import com.xiaojiwei.memao.model.Draft
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.SendResult
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun DetailPane(vm:MemaoViewModel,audio:AudioState,onGallery:(List<MediaDisplay>,Int)->Unit,single:Boolean) {
    val s=vm.state
    when {
        s.conflict!=null -> ConflictPage(vm,s.conflict)
        s.editor!=null -> EditorPage(vm,audio,onGallery)
        s.draftDetail!=null -> key(s.draftDetail.id) {DraftDetail(vm,s.draftDetail,audio,onGallery,single)}
        s.selected!=null -> key(s.selected.name) {MemoDetail(vm,s.selected,audio,onGallery,single)}
        else -> MmEmpty("选择一条记录","在左侧打开笔记，或点击「＋」记录新的想法。")
    }
}

@Composable
private fun MemoDetail(vm:MemaoViewModel,memo:Memo,audio:AudioState,onGallery:(List<MediaDisplay>,Int)->Unit,single:Boolean) {
    val s=vm.state;val c=LocalMemaoColors.current;val clipboard=LocalClipboardManager.current
    var deleting by remember(memo.name){mutableStateOf(false)}
    Column(Modifier.fillMaxSize()) {
        MmTopBar(if(memo.archived)"归档笔记" else "笔记",if(single)vm::back else null) {
            MmIconButton(MmIcon.COPY,"复制正文") {clipboard.setText(AnnotatedString(memo.content));vm.notify("已复制正文")}
            if(memo.archived)MmIconButton(MmIcon.UNARCHIVE,"取消归档",enabled=!s.busy&&!s.offline){vm.archiveMemo(memo)}
            else MmIconButton(MmIcon.ARCHIVE,"归档笔记",enabled=!s.busy&&!s.offline){vm.archiveMemo(memo)}
            MmIconButton(MmIcon.DELETE,"删除笔记",enabled=!s.busy&&!s.offline,danger=true){deleting=true}
        }
        s.error?.let {StateBanner(it,error=true,action={MmIconButton(MmIcon.CLOSE,"关闭错误提示",onClick=vm::dismissError)})}
        val key="detail:${memo.name}";val initial=vm.scroll(key)
        val scroll=rememberScrollState(initial.second)
        LaunchedEffect(memo.name,scroll){snapshotFlow {scroll.value}.distinctUntilChanged().collect {vm.saveScroll(key,0,it)}}
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal=if(single)24.dp else 28.dp,vertical=28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(displayTime(memo.createTime),Modifier.weight(1f),color=c.secondary,style=MaterialTheme.typography.bodySmall)
                LineIcon(visibilityIcon(memo.visibility),tint=c.secondary);Spacer(Modifier.width(6.dp))
                Text(visibilityLabel(memo.visibility),color=c.secondary,style=MaterialTheme.typography.bodySmall)
            }
            if(memo.content.isNotBlank())Markdown(memo.content)
            AttachmentList(memo.attachments.map(vm::media),audio,{vm.loadMedia(it)},{vm.loadMedia(it,true)},vm.audio::seekTo,onGallery)
            if(memo.tags.isNotEmpty())Text(memo.tags.joinToString("   ") {"#$it"},color=c.secondary,style=MaterialTheme.typography.bodySmall)
            Text(if(s.complete==true)"正文和附件已完整缓存，可离线编辑。" else if(s.offline)"附件未完整缓存，联网补齐后可编辑。" else "编辑前会补齐附件并保存到本机。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
        }
        MmRule()
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
            MmButton(if(s.busy)"正在准备…" else "编辑",vm::editSelected,Modifier.testTag("editMemo"),primary=true,enabled=!s.busy&&(!s.offline||s.complete==true),icon=MmIcon.EDIT)
        }
    }
    if(deleting)MmDialog("删除这条笔记？","此操作会删除服务端的笔记及关联附件，无法在本 APP 中恢复。",onDismiss={deleting=false},
        actions={MmButton("取消",{deleting=false});MmButton("删除笔记",{deleting=false;vm.deleteMemo(memo)},danger=true)})
}

@Composable
private fun DraftDetail(vm:MemaoViewModel,draft:Draft,audio:AudioState,onGallery:(List<MediaDisplay>,Int)->Unit,single:Boolean) {
    val c=LocalMemaoColors.current
    var deleting by remember(draft.id){mutableStateOf(false)}
    Column(Modifier.fillMaxSize()) {
        MmTopBar(if(draft.isNew)"新建草稿" else "修改草稿",if(single)vm::back else null) {
            MmIconButton(MmIcon.DELETE,"删除草稿",danger=true){deleting=true}
        }
        val scrollKey="draftDetail:${draft.id}"
        val scroll=rememberScrollState(vm.scroll(scrollKey).second)
        LaunchedEffect(scroll,draft.id) {snapshotFlow {scroll.value}.distinctUntilChanged().collect {vm.saveScroll(scrollKey,0,it)}}
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text("已保存在本机 · "+displayTime(draft.updatedAt),color=c.secondary,style=MaterialTheme.typography.bodySmall)
            if(draft.content.isNotBlank())Markdown(draft.content)
            AttachmentList(draft.attachments.map(vm::media),audio,{vm.loadMedia(it)},{vm.loadMedia(it,true)},vm.audio::seekTo,onGallery)
            draft.error?.let {Text(it,color=c.danger,style=MaterialTheme.typography.bodySmall)}
            Text("恢复网络后，由你手动发送。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
        }
        MmRule()
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.End) {MmButton("继续编辑",{vm.openDraft(draft)},primary=true,icon=MmIcon.EDIT)}
    }
    if(deleting)MmDialog("删除这份草稿？","将清理这份未发送草稿的正文及不再被其他内容使用的本地附件，不影响服务端原笔记。",onDismiss={deleting=false},
        actions={MmButton("取消",{deleting=false});MmButton("删除草稿",{deleting=false;vm.deleteDraft(draft)},danger=true)})
}

@Composable
private fun ConflictPage(vm:MemaoViewModel,conflict:SendResult.Conflict) {
    val s=vm.state;val c=LocalMemaoColors.current
    var selected by remember(conflict.draft.id,conflict.server?.updateTime){mutableStateOf<ConflictChoice?>(null)}
    Column(Modifier.fillMaxSize()) {
        MmTopBar("版本冲突",vm::cancelConflict) {MmButton("稍后处理",vm::cancelConflict,enabled=!s.sending)}
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("这条笔记有新版本",color=c.ink,style=MaterialTheme.typography.headlineSmall)
            Text("服务端内容在你编辑期间发生变化，请选择要保留的内容。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
            VersionComparison("服务端版本",conflict.server?.updateTime,conflict.server?.content?:"服务端原笔记已删除或不可访问。",conflict.server?.attachments?.size?:0)
            VersionComparison("你的修改草稿",displayTime(conflict.draft.updatedAt),conflict.draft.content,conflict.draft.attachments.size)
            listOf(
                Triple(ConflictChoice.KEEP_SERVER,"保留服务端版本","服务端内容不变，这份修改草稿继续保留。"),
                Triple(ConflictChoice.OVERWRITE,"用草稿覆盖","将原笔记更新为草稿中的正文与附件。"),
                Triple(ConflictChoice.SAVE_AS_NEW,"另存为新笔记","保留服务端原笔记，另建一条私密笔记。"),
            ).forEach { (choice,title,description) ->
                MmRule()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).selectable(selected=selected==choice,enabled=!s.sending,role=Role.RadioButton){selected=choice}.padding(vertical=16.dp),verticalAlignment=Alignment.Top) {
                    Box(Modifier.size(24.dp).background(if(selected==choice)c.strong else c.soft,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                        if(selected==choice)LineIcon(MmIcon.CHECK,Modifier.size(16.dp),c.onStrong)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {Text(title,color=c.ink,style=MaterialTheme.typography.titleMedium);Text(description,color=c.secondary,style=MaterialTheme.typography.bodySmall)}
                }
            }
            s.error?.let {Text(it,color=c.danger,style=MaterialTheme.typography.bodySmall)}
        }
        MmRule()
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("选择前不会更改任何内容",Modifier.weight(1f),color=c.secondary,style=MaterialTheme.typography.labelSmall)
            MmButton(if(s.sending)"正在处理…" else "继续",{selected?.let {vm.send(it)}},primary=true,enabled=selected!=null&&!s.sending)
        }
    }
}

@Composable
private fun VersionComparison(label:String,time:String?,content:String,count:Int) {
    val c=LocalMemaoColors.current
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
        Box(Modifier.width(2.dp).heightIn(min=72.dp).background(c.divider));Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(label+(time?.let {" · "+displayTime(it)}?:""),color=c.secondary,style=MaterialTheme.typography.bodySmall)
            if(content.isNotBlank())Markdown(content,compact=true)
            Text("$count 个附件",color=c.secondary,style=MaterialTheme.typography.labelSmall)
        }
    }
}
