package com.xiaojiwei.memao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.model.ConflictChoice
import com.xiaojiwei.memao.model.Draft
import com.xiaojiwei.memao.model.SendResult
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun DetailPane(vm:MemaoViewModel,audio:AudioState,onGallery:(List<MediaDisplay>,Int)->Unit,single:Boolean) {
    val s=vm.state
    when {
        s.editor!=null -> EditorPage(vm,audio,onGallery)
        s.draftDetail!=null -> key(s.draftDetail.id) {DraftDetail(vm,s.draftDetail,audio,onGallery,single)}
        else -> Unit
    }
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
        }
        MmRule()
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.End) {MmButton("继续编辑",{vm.openDraft(draft)},primary=true,icon=MmIcon.EDIT)}
    }
    if(deleting)MmDialog("删除这份草稿？","这份草稿及其未被其他内容使用的附件将被删除。",onDismiss={deleting=false},
        actions={MmButton("取消",{deleting=false});MmButton("删除草稿",{deleting=false;vm.deleteDraft(draft)},danger=true)})
}

@Composable
internal fun VersionComparison(label:String,time:String?,content:String,count:Int) {
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
