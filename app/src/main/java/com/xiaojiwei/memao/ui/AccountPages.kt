package com.xiaojiwei.memao.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.ThemeMode
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import kotlinx.coroutines.flow.first
import java.net.URI

@Composable
fun LoginPage(s:MemaoUiState,onConnect:(String,String)->Unit,onBack:(()->Unit)?,modifier:Modifier=Modifier) {
    val c=LocalMemaoColors.current
    var server by remember(s.pendingAuthentication?.id){mutableStateOf(s.pendingAuthentication?.serverUrl?:"")}
    // Credentials intentionally do not use rememberSaveable/SavedStateHandle.
    var token by remember(s.pendingAuthentication?.id){mutableStateOf("")}
    var visible by remember {mutableStateOf(false)}
    var serverError by remember {mutableStateOf<String?>(null)}
    var tokenError by remember {mutableStateOf<String?>(null)}
    Column(modifier.fillMaxWidth()) {
        if(onBack!=null)MmTopBar(if(s.pendingAuthentication!=null)"更新访问令牌" else "连接服务器",onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=28.dp,vertical=32.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.TopCenter) {
                Column(Modifier.widthIn(max=460.dp).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    Text("m.",color=c.ink,fontSize=34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(if(s.pendingAuthentication!=null)"重新连接你的记录" else "连接你的 Memos",color=c.ink,style=MaterialTheme.typography.headlineLarge)
                    Text("输入服务器地址与访问令牌，开始记录生活里的小事。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
                    MmField(server,{server=it;serverError=null},"服务器地址",placeholder="https://memos.example.com",error=serverError,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),inputTag="server",enabled=!s.busy)
                    MmField(token,{token=it;tokenError=null},"访问令牌 Token",placeholder="粘贴 Memos 访问令牌",error=tokenError,
                        visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),inputTag="token",enabled=!s.busy,
                        trailing={MmIconButton(if(visible)MmIcon.EYE_OFF else MmIcon.EYE,if(visible)"隐藏访问令牌" else "显示访问令牌"){visible=!visible}})
                    s.loginError?.let {Text(it,color=c.danger,style=MaterialTheme.typography.bodySmall)}
                    MmButton(if(s.busy)"正在验证连接…" else "连接服务器",{
                        val address=server.trim()
                        val parsed=runCatching {URI(address)}.getOrNull()
                        serverError=when {
                            parsed==null||parsed.scheme !in listOf("http","https")||parsed.host.isNullOrBlank()->"请输入有效的 HTTP 或 HTTPS 服务器地址。"
                            parsed.userInfo!=null||parsed.query!=null||parsed.fragment!=null->"服务器地址不应包含账号、查询参数或片段。"
                            !parsed.path.isNullOrBlank()&&parsed.path!="/"->"首版支持部署在域名根路径的 Memos。"
                            else->null
                        }
                        tokenError=if(token.isBlank())"请填写访问令牌。" else null
                        if(serverError==null&&tokenError==null)onConnect(address,token.trim())
                    },Modifier.fillMaxWidth().testTag("connect"),primary=true,enabled=!s.busy)
                    Text("仅兼容 Memos 0.31.0 · 支持 HTTP 和 HTTPS",color=c.secondary,style=MaterialTheme.typography.bodySmall)
                    if(s.pendingAuthentication!=null)Text("若令牌属于其他用户，将新增账号；原账号缓存和草稿保留。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(24.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            LineIcon(MmIcon.LOCK,tint=c.secondary);Spacer(Modifier.width(8.dp));Text("账号和草稿保存在这台设备",color=c.secondary,style=MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun AccountsPage(vm:MemaoViewModel,modifier:Modifier=Modifier) {
    val s=vm.state;val c=LocalMemaoColors.current
    var deleting by remember {mutableStateOf<Account?>(null)}
    var deleteDraftCount by remember {mutableStateOf<Int?>(null)}
    LaunchedEffect(deleting?.id) {deleting?.let {deleteDraftCount=vm.repository.observeDrafts(it.id).first().size}}
    Column(modifier.fillMaxWidth()) {
        MmTopBar("服务器账号",vm::back) {MmIconButton(MmIcon.PLUS,"添加服务器账号"){vm.showLogin()}}
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("切换账号保留缓存和草稿，各账号数据独立。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            s.accounts.forEach {account ->
                Column(Modifier.fillMaxWidth().padding(vertical=20.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(c.soft),contentAlignment=Alignment.Center) {Text(account.displayName.take(1),color=c.ink)}
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.displayName,color=c.ink,style=MaterialTheme.typography.titleMedium)
                            Text(account.serverUrl,color=c.secondary,style=MaterialTheme.typography.bodySmall)
                        }
                        if(account.id==s.accountId)Text("当前",color=c.secondary,style=MaterialTheme.typography.bodySmall)
                    }
                    if(account.needsAuthentication)Text("访问令牌失效，缓存和草稿仍保留",Modifier.padding(top=10.dp),color=c.danger,style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        if(account.id!=s.accountId)MmButton("切换",{vm.selectAccount(account)},Modifier.testTag("account:${account.id}"))
                        MmButton("更新令牌",{vm.showLogin(account)})
                        MmButton("删除",{deleteDraftCount=null;deleting=account},danger=true)
                    }
                }
                MmRule()
            }
            Spacer(Modifier.height(16.dp));MmButton("添加服务器账号",{vm.showLogin()},icon=MmIcon.PLUS)
        }
    }
    deleting?.let {account ->
        MmDialog("删除本地账号？","将清理这个账号在本机的数据，包括访问令牌、缓存、${deleteDraftCount?.let {it.toString()+" 份"}?:"所有"}未发送草稿及其图片、音频附件。不影响服务端账号、笔记或其他账号的数据。",
            onDismiss={deleting=null},
            actions={MmButton("取消",{deleting=null});MmButton("删除账号",{deleting=null;vm.removeAccount(account)},danger=true,enabled=deleteDraftCount!=null)},
            body={Spacer(Modifier.height(16.dp));Text(account.displayName,color=c.ink,style=MaterialTheme.typography.titleMedium);Text(account.serverUrl,color=c.secondary,style=MaterialTheme.typography.bodySmall)})
    }
}

@Composable
fun SettingsPage(vm:MemaoViewModel,modifier:Modifier=Modifier) {
    val s=vm.state;val c=LocalMemaoColors.current
    LaunchedEffect(Unit){vm.loadCache()}
    Column(modifier.fillMaxWidth()) {
        MmTopBar("设置")
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("外观",color=c.secondary,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            listOf(Triple(ThemeMode.SYSTEM,"跟随系统","themeSystem"),Triple(ThemeMode.LIGHT,"浅色","themeLight"),Triple(ThemeMode.DARK,"深色","themeDark")).forEach { (mode,label,tag) ->
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag).selectable(selected=s.settings.theme==mode,role=Role.RadioButton){vm.setTheme(mode)}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(label,Modifier.weight(1f),color=c.ink,style=MaterialTheme.typography.bodyMedium)
                    if(s.settings.theme==mode)LineIcon(MmIcon.CHECK)
                }
            }
            MmRule();Spacer(Modifier.height(28.dp))
            Text("服务器",color=c.secondary,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().heightIn(min=76.dp).clickable {vm.navigate(UiPage.ACCOUNTS)},verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {Text("管理账号",color=c.ink);Text(s.account?.displayName.orEmpty(),color=c.secondary,style=MaterialTheme.typography.bodySmall)}
                LineIcon(MmIcon.NEXT,tint=c.secondary)
            }
            MmRule();Spacer(Modifier.height(28.dp))
            Text("本机存储",color=c.secondary,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Text("普通缓存 · "+formatBytes(s.cache?.ordinaryBytes?:0),color=c.ink)
            Text("所有账号合计默认上限 1 GB，按需缓存。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Text("受保护的草稿附件 · "+formatBytes(s.cache?.protectedBytes?:0),color=c.ink,style=MaterialTheme.typography.bodyMedium)
            Text("不计入可自动清理额度，实际占用可能超过 1 GB。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp));MmButton("清理普通缓存",vm::clearCache,Modifier.testTag("clearCache"))
            Text("始终保留未发送草稿及其图片、音频。删除草稿请前往草稿页。",color=c.secondary,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp));MmRule();Spacer(Modifier.height(32.dp))
            Text("Memao",color=c.ink,style=MaterialTheme.typography.titleMedium)
            Text("记录生活里的小事\n兼容 Memos 0.31.0 · 简体中文",color=c.secondary,style=MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBytes(bytes:Long):String=when {
    bytes>=1024L*1024*1024->"%.2f GB".format(bytes.toDouble()/(1024*1024*1024))
    bytes>=1024L*1024->"%.1f MB".format(bytes.toDouble()/(1024*1024))
    bytes>=1024L->"%.1f KB".format(bytes.toDouble()/1024)
    else->"$bytes B"
}
