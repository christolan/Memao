package com.xiaojiwei.memao.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.xiaojiwei.memao.R
import com.xiaojiwei.memao.model.ThemeMode
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import com.xiaojiwei.memao.update.UpdateState

@Composable
fun SettingsPage(vm:MemaoViewModel,modifier:Modifier=Modifier) {
    LaunchedEffect(Unit) {vm.loadCache()}
    val updater = vm.appUpdate
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updater.onPermissionResult()
    }
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val pendingConfirmation = state as? UpdateState.Confirm
    LaunchedEffect(pendingConfirmation, lifecycle) {
        if (pendingConfirmation != null && lifecycle == Lifecycle.State.RESUMED) {
            updater.confirm(confirmation::launch)
        }
    }
    SettingsContent(vm.state,vm::setTheme,{vm.navigate(UiPage.ACCOUNTS)},vm::loadCache,vm::clearCache,modifier,sync={SyncSettingsEntry(vm)}) {
        SettingsUpdate(
            installedVersion = updater.installedVersionName,
            state = state,
            onCheck = updater::check,
            onDownload = updater::download,
            onCancelDownload = updater::cancelDownload,
            onInstall = {
                updater.install {
                    permission.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)),
                    )
                }
            },
            onConfirm = { updater.confirm(confirmation::launch) },
        )
    }
}

/** The settings surface is independent of I/O so loading, failures and layout can be verified. */
@Composable
fun SettingsContent(
    s:MemaoUiState,
    onTheme:(ThemeMode)->Unit,
    onAccounts:()->Unit,
    onLoadCache:()->Unit,
    onClearCache:()->Unit,
    modifier:Modifier=Modifier,
    sync:@Composable ()->Unit={},
    appUpdate:@Composable ()->Unit={},
) {
    val c=LocalMemaoColors.current
    Column(modifier.fillMaxWidth()) {
        MmTopBar("设置")
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=640.dp).fillMaxWidth().testTag("settingsBody").padding(24.dp)) {
                SettingsHeading("外观")
                Spacer(Modifier.height(14.dp))
                SettingsThemeOptions(s.settings.theme,onTheme)

                SettingsSectionBreak()
                SettingsHeading("服务器")
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min=78.dp).testTag("settingsAccounts")
                        .clip(RoundedCornerShape(8.dp)).background(c.soft)
                        .border(1.dp,c.divider,RoundedCornerShape(8.dp))
                        .clickable(role=Role.Button,onClick=onAccounts).padding(16.dp),
                    verticalAlignment=Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("管理账号",color=c.ink,fontSize=15.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium)
                        SettingsSupportingText(s.account?.displayName?.let {"当前：$it"}?:"查看已保存的服务器账号")
                    }
                    Spacer(Modifier.width(12.dp))
                    LineIcon(MmIcon.NEXT,tint=c.secondary)
                }
                sync()

                SettingsSectionBreak()
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    SettingsHeading("本机存储",Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    SettingsSupportingText("所有账号")
                }
                Spacer(Modifier.height(14.dp))
                val missingValue=if(s.cacheReadError!=null)"—" else "读取中…"
                SettingsStorageValue("普通缓存",s.cache?.ordinaryBytes?.let(::formatSettingsBytes)?:missingValue,"settingsOrdinaryValue")
                Spacer(Modifier.height(3.dp))
                SettingsSupportingText("按需缓存，默认上限 1 GB。")
                Spacer(Modifier.height(20.dp))
                SettingsStorageValue("受保护的草稿及附件",s.cache?.protectedBytes?.let(::formatSettingsBytes)?:missingValue,"settingsProtectedValue",MmIcon.DRAFT_PROTECTED)
                Spacer(Modifier.height(3.dp))
                SettingsSupportingText("不参与自动清理，总占用可超过上限。")

                if(s.cacheLoading&&s.cache!=null) {
                    Spacer(Modifier.height(12.dp))
                    SettingsSupportingText("正在更新缓存占用…",Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                }
                s.cacheReadError?.let {error ->
                    Spacer(Modifier.height(14.dp))
                    SettingsError(error,"settingsReadError")
                    Spacer(Modifier.height(8.dp))
                    SettingsOutlineButton("重试读取",onLoadCache,Modifier.testTag("cacheReadRetry"),enabled=!s.cacheLoading&&!s.cacheClearing,icon=MmIcon.REFRESH)
                }
                Spacer(Modifier.height(22.dp))
                val hasCache=(s.cache?.ordinaryBytes?:0)>0
                val clearLabel=when {
                    s.cacheClearing->"正在清理…"
                    s.cacheClearError!=null->"重试清理"
                    s.cache!=null&&!hasCache->"暂无可清理缓存"
                    else->"清理普通缓存"
                }
                SettingsOutlineButton(
                    clearLabel,onClearCache,Modifier.testTag("clearCache"),
                    enabled=!s.cacheLoading&&!s.cacheClearing&&s.cacheReadError==null&&hasCache,
                    icon=MmIcon.CLEAR_CACHE,
                )
                Spacer(Modifier.height(10.dp))
                SettingsSupportingText("保留所有未发送草稿及其图片、音频。")
                s.cacheClearError?.let {error ->
                    Spacer(Modifier.height(12.dp))
                    SettingsError(error,"settingsClearError")
                }

                SettingsSectionBreak()
                MemaoBrandTitle(style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                SettingsSupportingText("记录生活里的小事")
                SettingsSupportingText("兼容 Memos 0.31.0 · 简体中文")
                appUpdate()
            }
        }
    }
}

@Composable
internal fun SettingsUpdate(
    installedVersion: String,
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalMemaoColors.current
    val status = when (state) {
        UpdateState.Unchecked -> null
        UpdateState.Checking -> stringResource(R.string.update_checking)
        UpdateState.Current -> stringResource(R.string.update_current)
        UpdateState.CheckFailed -> stringResource(R.string.update_check_failed)
        is UpdateState.Available -> state.error?.let { stringResource(it) }
            ?: stringResource(R.string.update_available, state.release.versionName)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.percent)
        is UpdateState.Ready -> state.message?.let { stringResource(it) }
            ?: stringResource(R.string.update_ready, state.release.versionName)
        is UpdateState.PreparingInstall -> stringResource(R.string.update_preparing_install)
        is UpdateState.Confirm -> stringResource(R.string.update_confirmation)
        is UpdateState.Installing -> stringResource(R.string.update_installing)
        UpdateState.Installed -> stringResource(R.string.update_installed)
    }
    val showError = when (state) {
        UpdateState.CheckFailed -> true
        is UpdateState.Available -> state.error != null
        is UpdateState.Ready -> state.message == R.string.memao_update_install_failed
        else -> false
    }
    val action = when (state) {
        is UpdateState.Available -> if (state.error == null) R.string.update_download else R.string.update_retry_download
        is UpdateState.Downloading -> R.string.update_cancel_download
        is UpdateState.Ready -> if (state.message == R.string.memao_update_install_permission) {
            R.string.update_open_permissions
        } else {
            R.string.update_install
        }
        is UpdateState.Confirm -> R.string.update_confirm
        is UpdateState.PreparingInstall, is UpdateState.Installing, UpdateState.Installed -> R.string.update_install
        UpdateState.CheckFailed -> R.string.update_retry_check
        else -> R.string.update_check
    }
    val onAction = when (state) {
        is UpdateState.Available -> onDownload
        is UpdateState.Downloading -> onCancelDownload
        is UpdateState.Ready -> onInstall
        is UpdateState.Confirm -> onConfirm
        else -> onCheck
    }
    val enabled = when (state) {
        UpdateState.Checking, is UpdateState.PreparingInstall, is UpdateState.Installing, UpdateState.Installed -> false
        else -> true
    }

    Spacer(Modifier.height(12.dp))
    SettingsSupportingText(stringResource(R.string.update_installed_version, installedVersion))
    status?.let {
        Spacer(Modifier.height(8.dp))
        if (showError) {
            SettingsError(it, "appUpdateStatus")
        } else {
            SettingsSupportingText(
                it,
                Modifier.testTag("appUpdateStatus").semantics {
                    if (state !is UpdateState.Downloading) liveRegion = LiveRegionMode.Polite
                },
            )
        }
    }
    if (state is UpdateState.Downloading) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = colors.strong,
            trackColor = colors.divider,
        )
    }
    Spacer(Modifier.height(12.dp))
    SettingsOutlineButton(
        stringResource(action),
        onAction,
        Modifier.testTag("appUpdateAction"),
        enabled = enabled,
    )
    if (state is UpdateState.Available && state.error != null) {
        Spacer(Modifier.height(8.dp))
        SettingsOutlineButton(
            stringResource(R.string.update_retry_check),
            onCheck,
            Modifier.testTag("appUpdateCheckAgain"),
        )
    }
}

@Composable
private fun SettingsThemeOptions(selected:ThemeMode,onTheme:(ThemeMode)->Unit) {
    val c=LocalMemaoColors.current
    val options=listOf(Triple(ThemeMode.SYSTEM,"跟随系统","themeSystem"),Triple(ThemeMode.LIGHT,"浅色","themeLight"),Triple(ThemeMode.DARK,"深色","themeDark"))
    val style=MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium)
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    // Measure the selected shape for every option; switching modes cannot change the layout.
    val longestLabel=options.maxOf {measurer.measure(AnnotatedString(it.second),style=style,softWrap=false,maxLines=1).size.width}
    val minimumOptionWidth=with(density){longestLabel.toDp()}+16.dp+6.dp+16.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal=maxWidth>=minimumOptionWidth*3+16.dp
        val container=Modifier.fillMaxWidth().testTag(if(horizontal)"settingsThemeHorizontal" else "settingsThemeVertical")
            .selectableGroup().clip(RoundedCornerShape(10.dp)).background(c.soft)
            .border(1.dp,c.secondary,RoundedCornerShape(10.dp)).padding(4.dp)
        if(horizontal) {
            Row(container,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                options.forEach { (mode,label,tag) ->
                    SettingsThemeOption(label,tag,selected==mode,true,style,{onTheme(mode)},Modifier.weight(1f))
                }
            }
        } else {
            Column(container,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                options.forEach { (mode,label,tag) ->
                    SettingsThemeOption(label,tag,selected==mode,false,style,{onTheme(mode)},Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SettingsThemeOption(label:String,tag:String,selected:Boolean,horizontal:Boolean,style:TextStyle,onClick:()->Unit,modifier:Modifier) {
    val c=LocalMemaoColors.current
    val foreground=if(selected)c.onStrong else c.ink
    Row(
        modifier.heightIn(min=48.dp).testTag(tag).clip(RoundedCornerShape(6.dp))
            .background(if(selected)c.strong else Color.Transparent)
            .selectable(selected=selected,role=Role.RadioButton,onClick=onClick).padding(horizontal=8.dp,vertical=10.dp),
        horizontalArrangement=if(horizontal)Arrangement.Center else Arrangement.Start,
        verticalAlignment=Alignment.CenterVertically,
    ) {
        if(selected&&horizontal) {LineIcon(MmIcon.CHECK,Modifier.size(16.dp),foreground);Spacer(Modifier.width(6.dp))}
        Text(label,modifier=if(horizontal)Modifier else Modifier.weight(1f),color=foreground,style=style.copy(fontWeight=if(selected)FontWeight.Medium else FontWeight.Normal))
        if(selected&&!horizontal) {Spacer(Modifier.width(8.dp));LineIcon(MmIcon.CHECK,Modifier.size(16.dp),foreground)}
    }
}

@Composable
private fun SettingsStorageValue(label:String,value:String,tag:String,icon:MmIcon?=null) {
    val c=LocalMemaoColors.current
    val style=MaterialTheme.typography.bodyMedium.copy(fontSize=15.sp,lineHeight=24.sp)
    val valueStyle=style.copy(fontWeight=FontWeight.Medium)
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val labelWidth=with(density){measurer.measure(AnnotatedString(label),style=style,softWrap=false,maxLines=1).size.width.toDp()}+if(icon!=null)22.dp else 0.dp
    val valueWidth=with(density){measurer.measure(AnnotatedString(value),style=valueStyle,softWrap=false,maxLines=1).size.width.toDp()}
    val name:@Composable ()->Unit={
        Row(verticalAlignment=Alignment.CenterVertically) {
            if(icon!=null) {LineIcon(icon,Modifier.size(16.dp),c.secondary);Spacer(Modifier.width(6.dp))}
            Text(label,color=c.ink,style=style)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if(maxWidth>=labelWidth+valueWidth+16.dp) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                name()
                Text(value,Modifier.testTag(tag),color=c.ink,style=valueStyle)
            }
        } else {
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                name()
                Text(value,Modifier.align(Alignment.End).testTag(tag),color=c.ink,style=valueStyle)
            }
        }
    }
}

@Composable
private fun SettingsOutlineButton(label:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,icon:MmIcon?=null) {
    val c=LocalMemaoColors.current
    Row(
        modifier.heightIn(min=48.dp).clip(RoundedCornerShape(8.dp))
            .background(if(enabled)c.background else c.soft)
            .border(1.dp,if(enabled)c.secondary else c.divider,RoundedCornerShape(8.dp))
            .clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=16.dp,vertical=11.dp),
        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically,
    ) {
        val foreground=if(enabled)c.ink else c.secondary
        if(icon!=null) {LineIcon(icon,Modifier.size(18.dp),foreground);Spacer(Modifier.width(8.dp))}
        Text(label,color=foreground,fontSize=14.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium)
    }
}

@Composable
private fun SettingsHeading(label:String,modifier:Modifier=Modifier) {
    Text(label,modifier,color=LocalMemaoColors.current.secondary,fontSize=13.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium)
}

@Composable
private fun SettingsSupportingText(text:String,modifier:Modifier=Modifier) {
    Text(text,modifier,color=LocalMemaoColors.current.secondary,fontSize=13.sp,lineHeight=21.sp)
}

@Composable
private fun SettingsError(text:String,tag:String) {
    Text(text,Modifier.testTag(tag).semantics {liveRegion=LiveRegionMode.Polite},color=LocalMemaoColors.current.danger,fontSize=13.sp,lineHeight=21.sp)
}

@Composable
private fun SettingsSectionBreak() {
    Spacer(Modifier.height(24.dp));MmRule();Spacer(Modifier.height(24.dp))
}

private fun formatSettingsBytes(bytes:Long):String=when {
    bytes>=1024L*1024*1024->"%.2f GB".format(bytes.toDouble()/(1024*1024*1024))
    bytes>=1024L*1024->"%.1f MB".format(bytes.toDouble()/(1024*1024))
    bytes>=1024L->"%.1f KB".format(bytes.toDouble()/1024)
    else->"$bytes B"
}
