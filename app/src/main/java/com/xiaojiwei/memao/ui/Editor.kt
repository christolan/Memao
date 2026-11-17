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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
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
    var addTag by rememberSaveable(draft.id) {mutableStateOf(false)}
    var removing by remember {mutableStateOf<MediaDisplay?>(null)}
    val editable=!s.sending&&!s.importing
    Column(Modifier.fillMaxSize().imePadding()) {
        MmTopBar(if(draft.isNew)"新的记录" else "编辑记录",vm::back) {
            MmButton(
                if (s.preview) "编辑" else "预览",
                onClick = {
                    if (!s.preview) keyboard?.hide()
                    vm.togglePreview()
                },
                enabled = editable,
            )
            MmButton(if(s.sending)"保存中…" else "保存",{keyboard?.hide();vm.send()},Modifier.testTag("sendMemo"),primary=true,enabled=editable&&!draft.empty&&!s.offline)
        }
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
        MmRule()
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp)) {
            FlowRow(
                modifier=Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.SpaceBetween,
                verticalArrangement=Arrangement.spacedBy(4.dp),
                itemVerticalAlignment=Alignment.CenterVertically,
            ) {
                Row(Modifier.padding(end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    LineIcon(visibilityIcon(if(draft.isNew)"PRIVATE" else draft.baseMemo?.visibility?:"PRIVATE"),tint=c.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text(if(draft.isNew)"私密笔记" else visibilityLabel(draft.baseMemo?.visibility?:"PRIVATE")+" · 保持原可见性",color=c.secondary,style=MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment=Alignment.End) {
                    Text(
                        when {
                            s.importing -> "正在保存附件…"
                            s.saveError != null -> "草稿保存失败"
                            s.saving -> "正在保存草稿…"
                            draft.empty -> "尚无内容"
                            s.editorUnchanged -> "未修改"
                            else -> "草稿已保存到本机"
                        },
                        modifier=Modifier.testTag("editorSaveStatus").semantics {liveRegion=LiveRegionMode.Polite},
                        color = if (s.saveError != null) c.danger else c.secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (s.offline) Text("离线草稿", color = c.secondary, style = MaterialTheme.typography.labelSmall)
                }
                if(s.saveError!=null)MmButton("重试保存",vm::retrySave,Modifier.testTag("retryEditorSave"),enabled=editable)
            }
            s.saveError?.let {Text(it,color=c.danger,style=MaterialTheme.typography.labelSmall)}
        }
        if(!s.preview) {
            MmRule()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)) {
                listOf(
                    MarkdownShortcut(MmIcon.HEADING,"标题","# "),
                    MarkdownShortcut(MmIcon.BOLD,"粗体","**","**"),
                    MarkdownShortcut(MmIcon.ITALIC,"斜体","*","*"),
                    MarkdownShortcut(MmIcon.LIST,"无序列表","- "),
                    MarkdownShortcut(MmIcon.ORDERED_LIST,"有序列表","1. "),
                    MarkdownShortcut(MmIcon.QUOTE,"引用","> "),
                    MarkdownShortcut(MmIcon.LINK,"链接","[","](https://)"),
                    MarkdownShortcut(MmIcon.TASK_LIST,"待办列表","- [ ] "),
                ).forEach { shortcut ->
                    MmIconButton(shortcut.icon,shortcut.label,enabled=editable) {
                        vm.format(shortcut.prefix,shortcut.suffix)
                        focus.requestFocus()
                    }
                }
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
            MmButton("标签",{addTag=true},enabled=editable,icon=MmIcon.TAG)
        }
    }
    if (addTag) {
        val offline = s.offline || vm.notes.state.offline
        val cachedTags by produceState(emptyList<String>(), draft.accountId, offline) {
            value = if (offline) vm.cachedEditorTags(draft.accountId) else emptyList()
        }
        val tags = s.tags.map { it.tag } + cachedTags + vm.notes.state.memos.flatMap { it.tags } + draft.baseMemo?.tags.orEmpty()
        EditorTagDialog(tags, onDismiss = { addTag = false }, onAdd = { tag ->
            vm.addTag(tag)
            addTag = false
        })
    }
    removing?.let {item->MmDialog("移除附件？","发送前只修改本地草稿，服务端原笔记暂不改变。",onDismiss={removing=null},
        actions={MmButton("取消",{removing=null});MmButton("移除",{vm.removeAttachment(item.key);removing=null},danger=true)})}
}

private data class MarkdownShortcut(val icon:MmIcon,val label:String,val prefix:String,val suffix:String="")
