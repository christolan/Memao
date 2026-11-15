package com.xiaojiwei.memao.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.xiaojiwei.memao.model.ThemeMode
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import com.xiaojiwei.memao.ui.theme.MemaoTheme
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MemaoApp(vm:MemaoViewModel,onConnect:(String,String)->Unit) {
    val s=vm.state
    val dark=when(s.settings.theme){ThemeMode.SYSTEM->androidx.compose.foundation.isSystemInDarkTheme();ThemeMode.DARK->true;ThemeMode.LIGHT->false}
    val view=LocalView.current
    SideEffect {
        activityOf(view.context)?.window?.let {window->
            WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    MemaoTheme(dark) {
        val c=LocalMemaoColors.current
        val owner=LocalLifecycleOwner.current
        DisposableEffect(owner,vm) {
            owner.lifecycle.addObserver(vm.audio)
            val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP)vm.flushOnStop()}
            owner.lifecycle.addObserver(observer)
            onDispose {owner.lifecycle.removeObserver(vm.audio);owner.lifecycle.removeObserver(observer)}
        }
        val keyboard=LocalSoftwareKeyboardController.current
        val imeVisible=WindowInsets.isImeVisible
        BoxWithConstraints(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
            val scale=LocalDensity.current.fontScale.coerceAtLeast(1f)
            val navigationVisible=s.accountId!=null&&s.page!=UiPage.LOGIN
            val permanentDrawer=navigationVisible&&maxWidth>=600.dp
            val drawerWidth=240.dp
            // Keep navigation available on medium windows without squeezing two content panes.
            val contentWidth=maxWidth-if(permanentDrawer)drawerWidth else 0.dp
            val wide=contentWidth/3>=240.dp*scale
            val modalAvailable=navigationVisible&&!permanentDrawer
            val modalWidth=minOf(280.dp,(maxWidth-48.dp).coerceAtLeast(0.dp))
            // A mode/account change replaces only transient drawer state, never the content tree.
            // A wide window therefore cannot retain an opening animation or a modal scrim.
            val drawerState=key(modalAvailable,s.accountId) {rememberDrawerState(DrawerValue.Closed)}
            val currentDrawerState by rememberUpdatedState(drawerState)
            val scope=rememberCoroutineScope()
            val modalWidthPx=with(LocalDensity.current){modalWidth.toPx()}
            val drawerVisible=modalAvailable&&(drawerState.isOpen||drawerState.targetValue==DrawerValue.Open||
                (!drawerState.currentOffset.isNaN()&&drawerState.currentOffset> -modalWidthPx+.5f))
            LaunchedEffect(drawerVisible) {if(drawerVisible)keyboard?.hide()}
            var navigating by remember {mutableStateOf(false)}
            val closeDrawer:()->Unit={scope.launch {drawerState.close()}}
            val openDrawer:(()->Unit)?=if(modalAvailable)({keyboard?.hide();scope.launch {drawerState.open()}})else null
            val showDetail=s.editor!=null||s.conflict!=null||s.paneOpen
            val navigateAfterClose:(()->Unit)->Unit={action->
                if(permanentDrawer)action()
                else if(!navigating) {
                    navigating=true
                    val accountId=s.accountId
                    scope.launch {
                        try {
                            drawerState.close()
                            // Ignore delayed clicks after account changes or a layout-mode switch.
                            if(vm.state.accountId==accountId&&currentDrawerState===drawerState)action()
                        } finally {navigating=false}
                    }
                }
            }
            BackHandler(!drawerVisible&&(s.editor!=null||s.paneOpen||s.conflict!=null||s.preparingMemo!=null||s.page==UiPage.ACCOUNTS||(s.page==UiPage.LOGIN&&s.accountId!=null))) {
                if(imeVisible)keyboard?.hide() else vm.back()
            }
            // The sheet handles open/predictive back; intercept back during the opening animation too.
            BackHandler(drawerVisible&&!drawerState.isOpen) {closeDrawer()}
            val audio by vm.audio.state.collectAsStateWithLifecycle()
            val gallery=s.gallery
            val showGallery:(List<MediaDisplay>,Int)->Unit=vm::showGallery
            ModalNavigationDrawer(
                modifier=Modifier.fillMaxSize().clipToBounds().testTag("navigationShell"),
                drawerState=drawerState,
                gesturesEnabled=modalAvailable&&gallery==null&&!navigating,
                scrimColor=if(modalAvailable)Color.Black.copy(alpha=.32f)else Color.Transparent,
                drawerContent={
                    // Keep the sheet composed while closed and throughout closing animations.
                    Box(Modifier.testTag(if(modalAvailable)"navigationDrawer" else "inactiveNavigationDrawer")) {
                        ModalDrawerSheet(
                            drawerState=drawerState,
                            modifier=Modifier.width(modalWidth).then(if(!drawerVisible)Modifier.clearAndSetSemantics {}else Modifier),
                            drawerShape=RectangleShape,
                            drawerContainerColor=c.background,
                            drawerContentColor=c.ink,
                            drawerTonalElevation=0.dp,
                            windowInsets=WindowInsets(0,0,0,0),
                        ) {
                            NavigationDrawer(s,vm,onNavigate=navigateAfterClose,onClose=closeDrawer)
                        }
                    }
                },
            ) {
                Row(Modifier.fillMaxSize().then(if(drawerVisible)Modifier.clearAndSetSemantics {}else Modifier)) {
                    if(permanentDrawer)NavigationDrawer(s,vm,Modifier.width(drawerWidth).testTag("navigationDrawer"),onNavigate=navigateAfterClose)
                    // This content stays at the same composition site across all window sizes.
                    CompositionLocalProvider(LocalOpenNavigationDrawer provides openDrawer) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if(s.offline&&s.accountId!=null&&s.page!=UiPage.LOGIN)StateBanner("当前离线 · 仅浏览本机缓存，内容可能不是最新","联网后由你手动发送草稿。")
                            s.account?.takeIf {it.needsAuthentication}?.let {
                                StateBanner("访问令牌已失效","缓存和草稿仍保留，更新令牌后可发送。",action={MmButton("更新令牌",{vm.showLogin(it)})})
                            }
                            if(s.preparingMemo!=null)StateBanner("正在准备编辑…","正在读取正文与附件。",action={MmButton("取消",vm::back)})
                            if(s.page==UiPage.LOGIN) {
                                LoginPage(s,onConnect,if(s.accountId!=null)({vm.back()})else null,Modifier.weight(1f))
                            } else if(s.page==UiPage.ACCOUNTS) {
                                AccountsPage(vm,Modifier.weight(1f))
                            } else if(s.page==UiPage.SETTINGS&&s.editor==null) {
                                SettingsPage(vm,Modifier.weight(1f))
                            } else {
                                AdaptivePanes(
                                    wide=wide,showDetail=showDetail,
                                    modifier=Modifier.weight(1f).fillMaxWidth(),
                                    feed={
                                        CompositionLocalProvider(LocalOpenNavigationDrawer provides if(showDetail)null else openDrawer) {
                                            when(s.page) {
                                                UiPage.DRAFTS->DraftsPage(vm,vm.drafts,wide&&showDetail)
                                                UiPage.ARCHIVE->ArchivePage(vm,vm.archive,audio,showGallery,wide&&showDetail)
                                                else->NotesPage(vm,vm.notes,audio,showGallery,wide&&showDetail)
                                            }
                                        }
                                    },
                                    detail={
                                        CompositionLocalProvider(LocalOpenNavigationDrawer provides if(showDetail)openDrawer else null) {
                                            DetailPane(vm,audio,showGallery,!wide)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            s.message?.let {message ->
                Row(Modifier.align(Alignment.BottomCenter).padding(horizontal=20.dp,vertical=20.dp).clip(RoundedCornerShape(8.dp)).background(c.soft).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(message,Modifier.weight(1f),color=c.ink,style=MaterialTheme.typography.bodySmall)
                    MmIconButton(MmIcon.CLOSE,"关闭提示",onClick=vm::dismissMessage)
                }
                LaunchedEffect(message){kotlinx.coroutines.delay(4500);vm.dismissMessage()}
            }
            if(gallery!=null) {
                val current=gallery!!
                val items=current.first.map {it.copy(file=it.file?:s.files[it.key],loading=it.key in s.loadingFiles)}
                ImageGallery(items,current.second,{vm.loadMedia(it)},vm::closeGallery)
            }
        }
    }
}

/** Stable layout nodes keep text-input focus/selection alive; adapting only changes measurement. */
@Composable
private fun AdaptivePanes(wide:Boolean,showDetail:Boolean,modifier:Modifier=Modifier,feed:@Composable ()->Unit,detail:@Composable ()->Unit) {
    val c=LocalMemaoColors.current
    Layout(
        content={
            Box(Modifier.fillMaxSize()){feed()}
            Box(Modifier.background(c.divider))
            Box(Modifier.fillMaxSize()){detail()}
        },
        modifier=modifier,
    ) {children,constraints->
        val width=constraints.maxWidth
        val height=constraints.maxHeight
        if(wide&&showDetail) {
            val divider=1.dp.roundToPx()
            val listWidth=(width-divider)/3
            val left=children[0].measure(Constraints.fixed(listWidth,height))
            val line=children[1].measure(Constraints.fixed(divider,height))
            val right=children[2].measure(Constraints.fixed(width-listWidth-divider,height))
            layout(width,height) {
                left.placeRelative(0,0)
                line.placeRelative(listWidth,0)
                right.placeRelative(listWidth+divider,0)
            }
        } else {
            val active=children[if(showDetail)2 else 0].measure(Constraints.fixed(width,height))
            layout(width,height) {active.placeRelative(0,0)}
        }
    }
}

private tailrec fun activityOf(context:Context):Activity?=when(context) {
    is Activity->context
    is ContextWrapper->activityOf(context.baseContext)
    else->null
}

@Composable
private fun NavigationDrawer(
    s:MemaoUiState,
    vm:MemaoViewModel,
    modifier:Modifier=Modifier,
    onNavigate:(()->Unit)->Unit,
    onClose:(()->Unit)?=null,
) {
    val c=LocalMemaoColors.current
    Row(modifier.fillMaxHeight().background(c.background).semantics {paneTitle="导航菜单"}) {
        Column(Modifier.weight(1f).fillMaxHeight()
            .verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                MemaoBrandTitle(
                    Modifier.weight(1f).padding(start = 12.dp),
                    style = LocalTextStyle.current.copy(fontSize = 24.sp),
                    iconSize = 28.dp,
                )
                onClose?.let {close->MmIconButton(MmIcon.CLOSE,"关闭导航菜单",modifier=Modifier.testTag("drawerClose"),onClick=close)}
            }
            Spacer(Modifier.height(20.dp))
            MmButton("记录",{onNavigate(vm::newDraft)},Modifier.fillMaxWidth().testTag("newMemo"),primary=true,icon=MmIcon.PLUS,enabled=!s.sending&&!s.importing)
            Spacer(Modifier.height(20.dp))
            listOf(UiPage.NOTES,UiPage.DRAFTS,UiPage.ARCHIVE).forEach {page->
                DrawerItem(page,s.page==page){onNavigate {vm.navigate(page)}}
            }
            if(s.page==UiPage.NOTES&&s.editor==null) {
                Spacer(Modifier.height(12.dp));MmRule();Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().testTag("drawerTagFilters")) {
                    Text("标签",Modifier.padding(horizontal=12.dp,vertical=8.dp),color=c.secondary,style=MaterialTheme.typography.labelSmall)
                    DrawerTagFilter(null,s.tag==null&&!s.untagged){onNavigate {vm.setTag(null)}}
                    DrawerTagFilter(null,s.untagged,untagged=true){onNavigate {vm.setUntagged(!s.untagged)}}
                    val tags=(s.tags.map {it.tag}+s.memos.flatMap {it.tags}+listOfNotNull(s.tag)).distinct()
                    tags.forEach {tag->
                        DrawerTagFilter(tag,s.tag==tag&&!s.untagged){onNavigate {vm.setTag(if(s.tag==tag)null else tag)}}
                    }
                }
            }
            Spacer(Modifier.height(12.dp));MmRule();Spacer(Modifier.height(12.dp))
            DrawerItem(UiPage.SETTINGS,s.page==UiPage.SETTINGS||s.page==UiPage.ACCOUNTS){onNavigate {vm.navigate(UiPage.SETTINGS)}}
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clip(RoundedCornerShape(8.dp)).testTag("drawerAccounts")
                .clickable(role=Role.Button){onNavigate {vm.navigate(UiPage.ACCOUNTS)}}.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("服务器账号",color=c.secondary,style=MaterialTheme.typography.labelSmall)
                    Text(s.account?.displayName?:"账号",color=c.ink,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp));LineIcon(MmIcon.NEXT,tint=c.secondary)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
    }
}

@Composable
private fun DrawerTagFilter(tag:String?,selected:Boolean,untagged:Boolean=false,onClick:()->Unit) {
    val c=LocalMemaoColors.current
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(if(untagged)"filter:untagged" else if(tag==null)"tag:all" else "tag:$tag")
        .clip(RoundedCornerShape(8.dp)).then(if(selected)Modifier.background(c.soft)else Modifier)
        .clickable(role=Role.Tab,onClick=onClick).semantics {this.selected=selected}
        .padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(if(untagged)"无标签" else if(tag==null)"全部" else "#$tag",Modifier.weight(1f),color=if(selected)c.ink else c.secondary,style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DrawerItem(page:UiPage,selected:Boolean,onClick:()->Unit) {
    val c=LocalMemaoColors.current
    val icon=when(page){UiPage.DRAFTS->MmIcon.DRAFT;UiPage.ARCHIVE->MmIcon.ARCHIVE;UiPage.SETTINGS->MmIcon.SETTINGS;else->MmIcon.NOTES}
    val tag=when(page){UiPage.NOTES->"navNotes";UiPage.DRAFTS->"navDrafts";UiPage.ARCHIVE->"navArchive";else->"navSettings"}
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag).clip(RoundedCornerShape(8.dp))
        .then(if(selected)Modifier.background(c.soft)else Modifier)
        .clickable(role=Role.Tab,onClick=onClick).semantics {this.selected=selected}.padding(horizontal=12.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
        LineIcon(icon,Modifier.size(MmIconSize.Navigation),tint=if(selected)c.ink else c.secondary)
        Spacer(Modifier.width(16.dp))
        Text(page.title,color=if(selected)c.ink else c.secondary,style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StateBanner(title:String,message:String?=null,error:Boolean=false,action:(@Composable ()->Unit)?=null) {
    val c=LocalMemaoColors.current
    Row(Modifier.fillMaxWidth().background(c.soft).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,color=if(error)c.danger else c.ink,style=MaterialTheme.typography.bodySmall)
            message?.let {Text(it,color=c.secondary,style=MaterialTheme.typography.labelSmall)}
        }
        action?.invoke()
    }
}
