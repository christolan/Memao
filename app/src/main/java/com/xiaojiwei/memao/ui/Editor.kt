package com.xiaojiwei.memao.ui

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun EditorPage(
    vm: MemaoViewModel,
    audio: AudioState,
    onGallery: (List<MediaDisplay>, Int) -> Unit,
) = BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    val s=vm.state;val draft=s.editor?:return@BoxWithConstraints;val c=LocalMemaoColors.current
    val input=vm.editorInput?:return@BoxWithConstraints
    val context=LocalContext.current
    val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope()
    val focus=remember {FocusRequester()}
    LaunchedEffect(input,s.preview) {
        if(!s.preview){focus.requestFocus();if(vm.consumeEditorFocus(draft.id))keyboard?.show()}
    }
    val images=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {uris->vm.importFiles(uris)}
    val audioPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->vm.importFiles(listOfNotNull(uri))}
    var addTag by rememberSaveable(draft.id) {mutableStateOf(false)}
    var moreToolsExpanded by rememberSaveable(draft.id, s.preview) { mutableStateOf(false) }
    var removing by remember {mutableStateOf<MediaDisplay?>(null)}
    val editable=!s.sending&&!s.importing
    val toolColumns = if (maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.15f) 3 else 2
    val toolPanelMaxHeight = maxHeight / 2
    BackHandler(enabled = moreToolsExpanded && !WindowInsets.isImeVisible) { moreToolsExpanded = false }

    fun format(prefix: String, suffix: String = "") {
        moreToolsExpanded = false
        vm.format(prefix, suffix)
        focus.requestFocus()
    }

    Column(Modifier.fillMaxSize()) {
        MmTopBar(if(draft.isNew)"新的记录" else "编辑记录",vm::back) {
            MmButton(
                if (s.preview) "编辑" else "预览",
                onClick = {
                    if (!s.preview) keyboard?.hide()
                    vm.togglePreview()
                },
                enabled = editable,
            )
            MmButton(if(s.sending)"保存中…" else "保存",{keyboard?.hide();vm.saveNote()},Modifier.testTag("sendMemo"),primary=true,enabled=editable&&!draft.empty)
        }
        s.error?.let {StateBanner(it,error=true,action={MmIconButton(MmIcon.CLOSE,"关闭错误提示",onClick=vm::dismissError)})}
        key(input,s.preview) {
            val scrollKey="editor:${draft.id}:${s.preview}"
            val scroll=rememberScrollState(vm.scroll(scrollKey).second)
            LaunchedEffect(scroll){snapshotFlow {scroll.value}.distinctUntilChanged().collect {vm.saveScroll(scrollKey,0,it)}}
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal=24.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                if(s.preview) {
                    if(draft.content.isNotBlank())Markdown(draft.content)
                    else if(draft.attachments.isEmpty())Text("预览会显示在这里",color=c.secondary,style=MaterialTheme.typography.bodyMedium)
                } else BasicTextField(
                    state=input,
                    modifier=Modifier.fillMaxWidth().heightIn(min=200.dp).focusRequester(focus).testTag("editorBody"),
                    enabled=editable,
                    textStyle=MaterialTheme.typography.bodyLarge.copy(color=c.ink),
                    cursorBrush=SolidColor(c.ink),
                    decorator={inner->Box {if(input.text.isEmpty())Text("记下此刻的想法…",color=c.secondary,style=MaterialTheme.typography.bodyLarge);inner()}},
                )
                AttachmentList(draft.attachments.map(vm::media),audio,{vm.loadMedia(it)},{vm.loadMedia(it,true)},vm.audio::seekTo,onGallery,
                    onRemove=if(editable)({item->removing=item})else null)
            }
        }
        s.saveError?.let {
            StateBanner(it, error = true, action = {
                MmButton("重试保存", vm::retrySave, Modifier.testTag("retryEditorSave"), enabled = editable)
            })
        }
        if (moreToolsExpanded) {
            MmRule()
            FlowRow(
                modifier = Modifier.fillMaxWidth().heightIn(max = toolPanelMaxHeight)
                    .background(c.soft).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 7.dp)
                    .testTag("editorMoreTools"),
                maxItemsInEachRow = toolColumns,
            ) {
                if (!s.preview) {
                    for (shortcut in foldedMarkdownTools) {
                        MmButton(
                            shortcut.label, { format(shortcut.prefix, shortcut.suffix) },
                            Modifier.weight(1f), enabled = editable, icon = shortcut.icon,
                        )
                    }
                }
                MmButton("音频", {
                    moreToolsExpanded = false
                    scope.launch {
                        if (vm.prepareAttachmentImport()) audioPicker.launch(arrayOf("audio/*"))
                    }
                }, Modifier.weight(1f), enabled = editable, icon = MmIcon.AUDIO)
                MmButton("粘贴图片", {
                    moreToolsExpanded = false
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val clip = clipboard.primaryClip
                    val uris = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { index ->
                        clip.getItemAt(index).uri
                    }.filter { uri ->
                        runCatching { context.contentResolver.getType(uri)?.startsWith("image/") == true }.getOrDefault(false)
                    }
                    if (uris.isEmpty()) vm.notify("剪贴板中没有可读取的图片。")
                    else scope.launch { if (vm.prepareAttachmentImport()) vm.importFiles(uris) }
                }, Modifier.weight(1f), enabled = editable, icon = MmIcon.PASTE)
                MmButton("标签", {
                    moreToolsExpanded = false
                    addTag = true
                }, Modifier.weight(1f), enabled = editable, icon = MmIcon.TAG)
            }
        }
        MmRule()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 51.dp).padding(start = 12.dp, end = 24.dp)
                .testTag("editorToolbar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!s.preview) {
                EditorSymbolButton("#", "标题", editable) { format("# ") }
                EditorSymbolButton("-", "无序列表", editable) { format("- ") }
            }
            MmIconButton(MmIcon.IMAGE, "添加图片", enabled = editable) {
                moreToolsExpanded = false
                scope.launch {
                    if (vm.prepareAttachmentImport()) {
                        images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            MmIconButton(
                if (moreToolsExpanded) MmIcon.DOWN else MmIcon.MORE,
                if (moreToolsExpanded) "收起更多工具" else "更多工具",
                modifier = if (moreToolsExpanded) Modifier.background(c.soft, RoundedCornerShape(8.dp)) else Modifier,
            ) { moreToolsExpanded = !moreToolsExpanded }
            Column(Modifier.weight(1f).padding(start = 6.dp), horizontalAlignment = Alignment.End) {
                Text(
                    when {
                        s.importing -> "正在保存附件…"
                        s.saveError != null -> "草稿保存失败"
                        s.saving -> "正在保存草稿…"
                        draft.empty -> "尚无内容"
                        s.editorUnchanged -> "未修改"
                        else -> "草稿已保存到本机"
                    },
                    modifier = Modifier.testTag("editorSaveStatus").semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (s.saveError != null) c.danger else c.secondary,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                )
            }
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
    removing?.let {item->MmDialog("移除附件？","此附件将从当前草稿中移除。",onDismiss={removing=null},
        actions={MmButton("取消",{removing=null});MmButton("移除",{vm.removeAttachment(item.key);removing=null},danger=true)})}
}

@Composable
private fun EditorSymbolButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).alpha(if (enabled) 1f else .35f).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol, Modifier.clearAndSetSemantics {}, color = LocalMemaoColors.current.ink,
            fontSize = 22.sp, lineHeight = 24.sp,
        )
    }
}

private data class MarkdownShortcut(val icon: MmIcon, val label: String, val prefix: String, val suffix: String = "")

private val foldedMarkdownTools = listOf(
    MarkdownShortcut(MmIcon.BOLD, "粗体", "**", "**"),
    MarkdownShortcut(MmIcon.ITALIC, "斜体", "*", "*"),
    MarkdownShortcut(MmIcon.ORDERED_LIST, "有序列表", "1. "),
    MarkdownShortcut(MmIcon.QUOTE, "引用", "> "),
    MarkdownShortcut(MmIcon.LINK, "链接", "[", "](https://)"),
    MarkdownShortcut(MmIcon.TASK_LIST, "待办列表", "- [ ] "),
)
