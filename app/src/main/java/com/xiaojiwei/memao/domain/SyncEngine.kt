package com.xiaojiwei.memao.domain

import com.xiaojiwei.memao.data.local.ModelCodec
import com.xiaojiwei.memao.data.local.SyncOperation
import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.Attachment
import com.xiaojiwei.memao.model.CreateMemo
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.MemosException
import com.xiaojiwei.memao.model.MemosGateway
import com.xiaojiwei.memao.model.UpdateMemo
import com.xiaojiwei.memao.model.UploadFile
import java.io.File

internal const val SAVE = "SAVE"
internal const val ARCHIVE = "ARCHIVE"
internal const val DELETE = "DELETE"
internal const val QUEUED = "QUEUED"
internal const val SENDING = "SENDING"
internal const val FAILED = "FAILED"
internal const val RETRYABLE = "RETRYABLE"
internal const val CONFLICT = "CONFLICT"

internal sealed interface SyncResult {
    data class Written(val memo: Memo?) : SyncResult
    data class Conflict(val memo: Memo?) : SyncResult
    data object Cancelled : SyncResult
}

internal class SyncEngine(private val gateway: MemosGateway) {
    suspend fun push(account: Account, operation: SyncOperation, beforeWrite: suspend (Memo) -> Boolean): SyncResult {
        val proposed = ModelCodec.memo(operation.proposed)
        val base = operation.base?.let(ModelCodec::memo)
        val server = read(account, operation.memoName)
        if (operation.kind == DELETE) {
            if (server == null) return SyncResult.Written(null)
            if (base == null || !DomainRules.sameBase(base, server) || base.updateTime != server.updateTime) return SyncResult.Conflict(server)
            if (!beforeWrite(proposed)) return SyncResult.Cancelled
            try {
                gateway.delete(account, operation.memoName)
            } catch (error: MemosException) {
                if (error.statusCode != 404) throw error
            }
            return SyncResult.Written(null)
        }
        if (operation.kind == ARCHIVE) {
            if (server == null) return SyncResult.Conflict(null)
            if (server.state == proposed.state) return SyncResult.Written(server)
            if (base == null || base.state != server.state) return SyncResult.Conflict(server)
            return SyncResult.Written(gateway.update(account, UpdateMemo(server.name, state = proposed.state)))
        }

        val draft = ModelCodec.draft(checkNotNull(operation.draft))
        if (server != null) {
            val submitted = operation.submitted?.let(ModelCodec::memo) ?: proposed
            if (matchesSubmission(server, submitted)) return SyncResult.Written(server)
        }

        if (base == null && server != null || base != null && server == null) return SyncResult.Conflict(server)
        var content = if (base != null && server != null) {
            mergeText(base.content, draft.content, server.content) ?: return SyncResult.Conflict(server)
        } else draft.content
        val localNames = proposed.attachments.map { it.name }
        val baseNames = base?.attachments?.map { it.name }.orEmpty()
        val serverNames = server?.attachments?.map { it.name }.orEmpty()
        val localChanged = localNames != baseNames
        val serverChanged = serverNames != baseNames
        if (base != null && localChanged && serverChanged && localNames != serverNames) return SyncResult.Conflict(server)
        val attachments = if (base != null && !localChanged && serverChanged) {
            server?.attachments.orEmpty()
        } else draft.attachments.map { item ->
            val existing = server?.attachments?.firstOrNull { it.name == item.original?.name || it.name == "attachments/${item.uploadUid}" }
            existing ?: (if (base == null) item.uploaded else null) ?: gateway.upload(
                account, UploadFile(File(item.localPath), item.filename, item.type, item.uploadUid),
            ).also { replacement ->
                item.original?.let { content = DomainRules.rewriteAttachmentReference(content, it, replacement) }
            }
        }
        val retained = attachments.map { it.name }.toSet()
        try {
            DomainRules.validateAttachmentRemoval(server, content, retained)
            DomainRules.validateAttachmentRemoval(base, content, retained)
        } catch (_: IllegalArgumentException) {
            return SyncResult.Conflict(server)
        }
        val submitted = proposed.copy(content = content, attachments = attachments)
        if (!beforeWrite(submitted)) return SyncResult.Cancelled
        val result = if (server == null) {
            gateway.create(account, CreateMemo(proposed.uid, content, attachments))
        } else {
            gateway.update(account, UpdateMemo(server.name, content, attachments))
        }
        if (!matchesSubmission(result, submitted)) throw MemosException("服务器返回的内容与提交版本不一致", uncertain = true)
        return SyncResult.Written(result)
    }

    private fun matchesSubmission(server: Memo, submitted: Memo): Boolean =
        server.name == submitted.name && server.content == submitted.content &&
            server.attachments.map { it.name }.toSet() == submitted.attachments.map { it.name }.toSet()

    private suspend fun read(account: Account, name: String): Memo? = try {
        gateway.get(account, name)
    } catch (error: MemosException) {
        if (error.statusCode == 404) null else throw error
    }

    private data class Change(val start: Int, val end: Int, val replacement: String)

    private fun change(base: String, value: String): Change {
        val start = base.commonPrefixWith(value).length
        val suffix = base.substring(start).commonSuffixWith(value.substring(start)).length
        return Change(start, base.length - suffix, value.substring(start, value.length - suffix))
    }

    private fun mergeText(base: String, local: String, server: String): String? {
        if (local == server || server == base) return local
        if (local == base) return server
        val (first, second) = listOf(change(base, local), change(base, server)).sortedBy { it.start }
        if (first.end > second.start || first.start == second.start) return null
        return base.replaceRange(second.start, second.end, second.replacement)
            .replaceRange(first.start, first.end, first.replacement)
    }
}
