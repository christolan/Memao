package com.xiaojiwei.memao.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
        BackHandler(s.editor!=null||s.paneOpen||s.conflict!=null||s.page==UiPage.ACCOUNTS||(s.page==UiPage.LOGIN&&s.accountId!=null)) {
            if(imeVisible)keyboard?.hide() else vm.back()
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
            val scale=LocalDensity.current.fontScale.coerceAtLeast(1f)
            val wide=maxWidth>=840.dp&&(maxWidth-72.dp)/3>=240.dp*scale
            val audio by vm.audio.state.collectAsStateWithLifecycle()
            val gallery=s.gallery
            val showGallery:(List<MediaDisplay>,Int)->Unit=vm::showGallery
            Row(Modifier.fillMaxSize()) {
                if(wide&&s.accountId!=null&&s.page!=UiPage.LOGIN)NavigationRail(s,vm)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if(s.offline&&s.accountId!=null&&s.page!=UiPage.LOGIN)StateBanner("当前离线 · 仅浏览本机缓存，内容可能不是最新","联网后由你手动发送草稿。")
                    s.account?.takeIf {it.needsAuthentication}?.let {
                        StateBanner("访问令牌已失效","缓存和草稿仍保留，更新令牌后可发送。",action={MmButton("更新令牌",{vm.showLogin(it)})})
                    }
                    if(s.page==UiPage.LOGIN) {
                        LoginPage(s,onConnect,if(s.accountId!=null)({vm.back()})else null,Modifier.weight(1f))
                    } else if(s.page==UiPage.ACCOUNTS) {
                        AccountsPage(vm,Modifier.weight(1f))
                    } else if(s.page==UiPage.SETTINGS&&s.editor==null) {
                        SettingsPage(vm,Modifier.weight(1f))
                    } else {
                        AdaptivePanes(
                            wide=wide,showDetail=s.editor!=null||s.conflict!=null||s.paneOpen,
                            modifier=Modifier.weight(1f).fillMaxWidth(),
                            feed={FeedPage(vm,audio,showGallery,wide)},
                            detail={DetailPane(vm,audio,showGallery,!wide)},
                        )
                    }
                    if(!wide&&s.accountId!=null&&s.page!=UiPage.LOGIN&&s.editor==null&&s.conflict==null) {
                        BottomNavigation(s,vm)
                    }
                }
            }
            s.message?.let {message ->
                Row(Modifier.align(Alignment.BottomCenter).padding(horizontal=20.dp,vertical=if(wide)20.dp else 160.dp).clip(RoundedCornerShape(8.dp)).background(c.soft).padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
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
        if(wide) {
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
private fun NavigationRail(s:MemaoUiState,vm:MemaoViewModel) {
    val c=LocalMemaoColors.current
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(72.dp).fillMaxHeight().padding(horizontal=10.dp,vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text("m.",color=c.ink,fontSize=24.sp);Spacer(Modifier.height(24.dp))
            MmButton("",vm::newDraft,Modifier.size(48.dp).testTag("newMemo").semantics {contentDescription="新建笔记"},primary=true,icon=MmIcon.PLUS,enabled=!s.sending&&!s.importing)
            Spacer(Modifier.height(24.dp))
            listOf(UiPage.NOTES,UiPage.DRAFTS,UiPage.ARCHIVE).forEach {page->NavItem(page,s.page==page,true){vm.navigate(page)}}
            Spacer(Modifier.weight(1f));NavItem(UiPage.SETTINGS,s.page==UiPage.SETTINGS||s.page==UiPage.ACCOUNTS,true){vm.navigate(UiPage.SETTINGS)}
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
    }
}

@Composable
private fun BottomNavigation(s:MemaoUiState,vm:MemaoViewModel) {
    val c=LocalMemaoColors.current
    Column(Modifier.fillMaxWidth().background(c.background)) {
        if(!s.paneOpen&&s.page in listOf(UiPage.NOTES,UiPage.DRAFTS,UiPage.ARCHIVE)) {
            MmButton("记录",vm::newDraft,Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp).testTag("newMemo"),primary=true,icon=MmIcon.PLUS)
        }
        MmRule()
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=8.dp)) {
            listOf(UiPage.NOTES,UiPage.DRAFTS,UiPage.ARCHIVE,UiPage.SETTINGS).forEach {page->
                Box(Modifier.weight(1f)){NavItem(page,s.page==page||(page==UiPage.SETTINGS&&s.page==UiPage.ACCOUNTS),false){vm.navigate(page)}}
            }
        }
    }
}

@Composable
private fun NavItem(page:UiPage,selected:Boolean,rail:Boolean,onClick:()->Unit) {
    val c=LocalMemaoColors.current
    val icon=when(page){UiPage.DRAFTS->MmIcon.DRAFT;UiPage.ARCHIVE->MmIcon.ARCHIVE;UiPage.SETTINGS->MmIcon.SETTINGS;else->MmIcon.NOTES}
    val tag=when(page){UiPage.NOTES->"navNotes";UiPage.DRAFTS->"navDrafts";UiPage.ARCHIVE->"navArchive";else->"navSettings"}
    Column(Modifier.fillMaxWidth().heightIn(min=64.dp).testTag(tag).clip(RoundedCornerShape(8.dp)).then(if(rail&&selected)Modifier.background(c.soft)else Modifier)
        .clickable(role=Role.Tab,onClick=onClick).semantics {this.selected=selected}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        LineIcon(icon,tint=if(selected)c.ink else c.secondary);Spacer(Modifier.height(4.dp))
        Text(page.title,color=if(selected)c.ink else c.secondary,style=MaterialTheme.typography.labelSmall)
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
