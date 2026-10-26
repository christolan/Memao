package com.xiaojiwei.memao.ui

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun EditorPage(vm:MemaoViewModel,audio:AudioState,onGallery:(List<MediaDisplay>,Int)->Unit) {
    val s=vm.state;val draft=s.editor?:return;val c=LocalMemaoColors.current
    val context=LocalContext.current
    val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope()
    val focus=remember {FocusRequester()}
    var field by rememberSaveable(draft.id,stateSaver=TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(draft.content,TextRange(draft.cursorStart.coerceIn(0,draft.content.length),draft.cursorEnd.coerceIn(0,draft.content.length))))
    }
    LaunchedEffect(draft.content,draft.cursorStart,draft.cursorEnd) {
        if(field.text!=draft.content||field.selection.start!=draft.cursorStart||field.selection.end!=draft.cursorEnd) {
            field=TextFieldValue(draft.content,TextRange(draft.cursorStart.coerceIn(0,draft.content.length),draft.cursorEnd.coerceIn(0,draft.content.length)))
        }
    }
    LaunchedEffect(draft.id,s.preview) {
        if(!s.preview){focus.requestFocus();if(vm.consumeEditorFocus(draft.id))keyboard?.show()}
    }
    val images=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {uris->vm.importFiles(uris)}
    val audioPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->vm.importFiles(listOfNotNull(uri))}
    var addTag by remember {mutableStateOf(false)}
    var tagValue by remember {mutableStateOf("")}
    var removing by remember {mutableStateOf<MediaDisplay?>(null)}
    val editable=!s.sending&&!s.importing
    Column(Modifier.fillMaxSize().imePadding()) {
        MmTopBar(if(draft.isNew)"新的记录" else "编辑记录",vm::back) {
            MmButton(if(s.sending)"发送中…" else "发送",{keyboard?.hide();vm.send()},Modifier.testTag("sendMemo"),primary=true,enabled=editable&&!draft.empty&&!s.offline)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            MmButton("编辑",{if(s.preview)vm.togglePreview()},enabled=editable)
            MmButton("预览",{if(!s.preview){keyboard?.hide();vm.togglePreview()}},enabled=editable)
            Spacer(Modifier.weight(1f))
            Text(when {s.importing->"正在保存附件…";s.saving->"正在保存…";s.saveError!=null->"保存失败";draft.empty->"尚无内容";else->"已保存在本机"},color=if(s.saveError!=null)c.danger else c.secondary,style=MaterialTheme.typography.labelSmall)
        }
        s.saveError?.let {StateBanner(it,error=true,action={MmButton("重试保存",vm::retrySave)})}
        s.error?.let {StateBanner(it,error=true,action={MmIconButton(MmIcon.CLOSE,"关闭错误提示",onClick=vm::dismissError)})}
        key(draft.id,s.preview) {
            val scrollKey="editor:${draft.id}:${s.preview}"
            val scroll=rememberScrollState(vm.scroll(scrollKey).second)
            LaunchedEffect(scroll){snapshotFlow {scroll.value}.distinctUntilChanged().collect {vm.saveScroll(scrollKey,0,it)}}
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal=24.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                if(s.preview) {
                    if(draft.content.isNotBlank())Markdown(draft.content)
                    else if(draft.attachments.isEmpty())Text("预览会显示在这里",color=c.secondary,style=MaterialTheme.typography.bodyMedium)
                } else BasicTextField(
                    value=field,
                    onValueChange={
                        // Disposing the source field for preview causes Foundation to deselect it.
                        // Read-only preview must not overwrite the user's source selection.
                        if(!vm.state.preview||it.text!=field.text){field=it;vm.updateEditor(it)}
                    },
                    modifier=Modifier.fillMaxWidth().heightIn(min=200.dp).focusRequester(focus).testTag("editorBody"),
                    enabled=editable,
                    textStyle=MaterialTheme.typography.bodyLarge.copy(color=c.ink),
                    cursorBrush=SolidColor(c.ink),
                    decorationBox={inner->Box {if(field.text.isEmpty())Text("记下此刻的想法…",color=c.secondary,style=MaterialTheme.typography.bodyLarge);inner()}},
                )
                AttachmentList(draft.attachments.map(vm::media),audio,{vm.loadMedia(it)},{vm.loadMedia(it,true)},vm.audio::seekTo,onGallery,
                    onRemove=if(editable)({item->removing=item})else null)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            LineIcon(MmIcon.LOCK,tint=c.secondary);Spacer(Modifier.width(6.dp))
            Text(if(draft.isNew)"私密笔记" else visibilityLabel(draft.baseMemo?.visibility?:"PRIVATE")+" · 保持原可见性",Modifier.weight(1f),color=c.secondary,style=MaterialTheme.typography.labelSmall)
            if(s.offline)Text("离线草稿",color=c.secondary,style=MaterialTheme.typography.labelSmall)
        }
        if(!s.preview) {
            MmRule()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)) {
                listOf(
                    Triple("H","# ",""),Triple("B","**","**"),Triple("I","*","*"),
                    Triple("列表","- ",""),Triple("1.","1. ",""),Triple("引用","> ",""),
                    Triple("链接","[","](https://)"),Triple("待办","- [ ] ",""),
                ).forEach { (label,prefix,suffix)->MmButton(label,{vm.format(prefix,suffix);focus.requestFocus()},enabled=editable)}
            }
        }
        MmRule()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            MmButton("图片",{scope.launch {if(vm.prepareAttachmentImport())images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}},enabled=editable,icon=MmIcon.IMAGE)
            MmButton("音频",{scope.launch {if(vm.prepareAttachmentImport())audioPicker.launch(arrayOf("audio/*"))}},enabled=editable,icon=MmIcon.AUDIO)
            MmButton("粘贴图片",{
                val clipboard=context.getSystemService(ClipboardManager::class.java)
                val clip=clipboard.primaryClip
                val uris=if(clip==null)emptyList() else (0 until clip.itemCount).mapNotNull {clip.getItemAt(it).uri}.filter {uri->
                    runCatching {context.contentResolver.getType(uri)?.startsWith("image/")==true}.getOrDefault(false)
                }
                if(uris.isEmpty())vm.notify("剪贴板中没有可读取的图片。")
                else scope.launch {if(vm.prepareAttachmentImport())vm.importFiles(uris)}
            },enabled=editable,icon=MmIcon.PASTE)
            MmButton("标签",{tagValue="";addTag=true},enabled=editable,icon=MmIcon.TAG)
        }
    }
    if(addTag)MmDialog("添加标签","标签写入正文，和服务端保持一致。也可直接在源码中添加或移除 #标签。",onDismiss={addTag=false},
        actions={MmButton("取消",{addTag=false});MmButton("添加",{vm.addTag(tagValue);addTag=false},primary=true,enabled=tagValue.trim().removePrefix("#").isNotBlank())},
        body={Spacer(Modifier.height(16.dp));MmField(tagValue,{tagValue=it},"标签",placeholder="例如：日常")})
    removing?.let {item->MmDialog("移除附件？","发送前只修改本地草稿，服务端原笔记暂不改变。",onDismiss={removing=null},
        actions={MmButton("取消",{removing=null});MmButton("移除",{vm.removeAttachment(item.key);removing=null},danger=true)})}
}
