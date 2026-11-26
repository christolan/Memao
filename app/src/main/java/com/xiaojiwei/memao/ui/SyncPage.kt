package com.xiaojiwei.memao.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaojiwei.memao.data.local.ModelCodec
import com.xiaojiwei.memao.data.local.SyncOperation
import com.xiaojiwei.memao.domain.ARCHIVE
import com.xiaojiwei.memao.domain.CONFLICT
import com.xiaojiwei.memao.domain.DELETE
import com.xiaojiwei.memao.domain.FAILED
import com.xiaojiwei.memao.domain.QUEUED
import com.xiaojiwei.memao.domain.RETRYABLE
import com.xiaojiwei.memao.domain.SENDING
import com.xiaojiwei.memao.model.ConflictChoice
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SyncSettingsEntry(vm: MemaoViewModel) {
    val id = vm.state.accountId ?: return
    val operations by remember(id) { vm.repository.syncOperations(id) }.collectAsStateWithLifecycle(emptyList())
    val progress by remember(id) { vm.repository.syncProgress(id) }.collectAsStateWithLifecycle(null)
    val issueCount = operations.count { it.status == CONFLICT || it.status == FAILED || it.status == RETRYABLE } + if (progress?.error != null) 1 else 0
    val completedAt = progress?.completedAt ?: 0
    val colors = LocalMemaoColors.current
    val summary = when {
        vm.state.account?.needsAuthentication == true -> "需要更新访问令牌"
        issueCount > 0 -> "${issueCount} 项需要处理"
        operations.any { it.status == SENDING } -> "正在同步"
        operations.isNotEmpty() -> "${operations.size} 项待同步"
        completedAt > 0 -> "上次同步 " + displayTime(completedAt)
        else -> "等待首次同步"
    }
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 78.dp).testTag("settingsSync")
            .clip(RoundedCornerShape(8.dp)).background(colors.soft).border(1.dp, colors.divider, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { vm.navigate(UiPage.SYNC) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("同步管理", color = colors.ink, style = MaterialTheme.typography.titleSmall)
            Text(summary, color = if (issueCount > 0) colors.danger else colors.secondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        LineIcon(MmIcon.NEXT, tint = colors.secondary)
    }
}

@Composable
internal fun SyncPage(vm: MemaoViewModel, modifier: Modifier = Modifier) {
    val account = vm.state.account ?: return
    val operations by remember(account.id) { vm.repository.syncOperations(account.id) }.collectAsStateWithLifecycle(emptyList())
    val progress by remember(account.id) { vm.repository.syncProgress(account.id) }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var selected by remember(account.id) { mutableStateOf<Long?>(null) }
    var error by remember(account.id) { mutableStateOf<String?>(null) }
    var acting by remember(account.id) { mutableStateOf(false) }
    val colors = LocalMemaoColors.current

    fun act(operation: suspend () -> Unit) {
        if (acting) return
        acting = true
        error = null
        scope.launch {
            try {
                operation()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "操作未完成"
            } finally {
                acting = false
            }
        }
    }

    val conflict = operations.firstOrNull { it.id == selected && it.status == CONFLICT }
    BackHandler(conflict != null) { if (!acting) selected = null }
    if (conflict != null) {
        SyncConflictPage(conflict, acting, error, { selected = null; error = null }, modifier) { choice ->
            act {
                vm.repository.resolveSync(account.id, conflict.id, choice)
                selected = null
            }
        }
        return
    }

    Column(modifier.fillMaxWidth()) {
        MmTopBar("同步管理", vm::back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (account.needsAuthentication) {
                Text("访问令牌已失效", color = colors.danger)
                MmButton("更新令牌", { vm.showLogin(account) })
            }
            error?.let { Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall) }
            progress?.error?.takeUnless { account.needsAuthentication }?.let {
                Text("读取服务器变更失败 · $it", color = colors.danger, style = MaterialTheme.typography.bodySmall)
                MmButton("重试读取", vm.repository::requestSync)
            }
            val groups = listOf(
                "需要处理" to operations.filter { it.status == FAILED || it.status == RETRYABLE || it.status == CONFLICT },
                "正在同步" to operations.filter { it.status == SENDING },
                "待同步" to operations.filter { it.status == QUEUED },
            )
            if (operations.isEmpty()) Text("暂无待同步任务", color = colors.ink, style = MaterialTheme.typography.bodyMedium)
            for ((label, items) in groups) {
                if (items.isEmpty()) continue
                Column {
                    Text(label, color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                    items.forEach { operation ->
                        val status = when (operation.status) {
                            FAILED, RETRYABLE -> "同步失败 · ${operation.error ?: "请求失败"}"
                            CONFLICT -> if (operation.kind == DELETE) "有冲突 · 删除已暂停" else "有冲突 · 已保留版本"
                            SENDING -> "同步中"
                            else -> when (operation.kind) {
                                DELETE -> "待同步 · 已删除"
                                ARCHIVE -> if (ModelCodec.memo(operation.proposed).archived) "待同步 · 已归档" else "待同步 · 已恢复"
                                else -> "待同步"
                            }
                        }
                        val name = ModelCodec.memo(operation.proposed).content.lineSequence().firstOrNull { it.isNotBlank() } ?: "附件笔记"
                        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                                Text(name, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(status, color = if (operation.status == FAILED || operation.status == RETRYABLE || operation.status == CONFLICT) colors.danger else colors.secondary,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            when {
                                operation.status == FAILED || operation.status == RETRYABLE -> MmButton("重试", { act { vm.repository.retryOperation(account.id, operation.id) } }, enabled = !acting)
                                operation.status == CONFLICT -> MmButton("查看版本", { error = null; selected = operation.id }, enabled = !acting)
                            }
                            if (operation.kind == DELETE && !operation.started && operation.status != CONFLICT) {
                                MmButton("撤销删除", { act { vm.repository.undoDelete(account.id, operation.id) } }, enabled = !acting)
                            }
                        }
                        MmRule()
                    }
                }
            }
            progress?.takeIf { it.completedAt > 0 }?.let {
                Text("上次同步完成于 " + displayTime(it.completedAt), color = colors.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SyncConflictPage(operation: SyncOperation, acting: Boolean, error: String?, back: () -> Unit, modifier: Modifier, apply: (ConflictChoice) -> Unit) {
    val colors = LocalMemaoColors.current
    val local = remember(operation.proposed) { ModelCodec.memo(operation.proposed) }
    val server = remember(operation.remote) { operation.remote?.let(ModelCodec::memo) }
    val deleting = operation.kind == DELETE
    val editing = operation.draft != null
    var choice by remember(operation.id) { mutableStateOf<ConflictChoice?>(null) }
    val options = buildList {
        add(if (deleting) Triple(ConflictChoice.KEEP_SERVER, "取消删除", "恢复笔记并显示服务器版本。")
            else Triple(ConflictChoice.KEEP_SERVER, "采用服务器版本", if (editing) "本机版本留在草稿箱。" else "恢复服务器上的笔记状态。"))
        if (server != null) {
            add(if (deleting) Triple(ConflictChoice.OVERWRITE, "继续删除", "删除上方显示的服务器版本。")
                else Triple(ConflictChoice.OVERWRITE, "采用本机版本", "将本机版本同步到原笔记。"))
        }
        if (editing) add(Triple(ConflictChoice.SAVE_AS_NEW, "另存为新笔记", "保留原笔记的服务器版本，并将本机版本另存为私密笔记。"))
    }

    Column(modifier.fillMaxWidth()) {
        MmTopBar(if (deleting) "删除冲突" else "版本冲突", { if (!acting) back() }) {
            MmButton("稍后处理", back, enabled = !acting)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (deleting) "服务器上的笔记已更新" else "这条笔记有新版本", color = colors.ink, style = MaterialTheme.typography.headlineSmall)
            VersionComparison("服务器版本", server?.updateTime, server?.content ?: "服务器上的笔记已删除。", server?.attachments?.size ?: 0)
            VersionComparison(if (deleting) "本机删除前的版本" else "本机版本", local.updateTime, local.content, local.attachments.size)
            for ((option, label, description) in options) {
                MmRule()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .selectable(selected = choice == option, enabled = !acting, role = Role.RadioButton) { choice = option }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp).background(if (choice == option) colors.strong else colors.soft, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        if (choice == option) LineIcon(MmIcon.CHECK, Modifier.size(16.dp), colors.onStrong)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                        Text(description, color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall) }
        }
        MmRule()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            MmButton("继续", { choice?.let(apply) }, primary = true, enabled = choice != null && !acting)
        }
    }
}
