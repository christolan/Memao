package com.xiaojiwei.memao.domain

import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.Attachment
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

object DomainRules {
    fun normalizeServer(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("请输入有效的服务器地址") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "服务器地址须以 http:// 或 https:// 开头" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "请输入不含认证、查询参数和片段的服务器根地址" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "首版仅支持部署在根路径的服务器" }
        require(uri.port in -1..65535 && uri.port != 0) { "服务器端口无效" }
        val port = if ((scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)) -1 else uri.port
        return URI(scheme, null, uri.host.lowercase(Locale.ROOT), port, null, null, null).toASCIIString()
    }

    /** SQLite LIKE, as used by the target server, folds ASCII letters only. */
    fun asciiFold(value: String): String = value.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")

    fun matches(memo: Memo, query: MemoQuery): Boolean =
        memo.archived == query.archived && (query.tag == null || query.tag in memo.tags) &&
            asciiFold(memo.content).contains(asciiFold(query.keyword))

    fun sameBase(left: Memo, right: Memo): Boolean =
        left.name == right.name && left.creator == right.creator && left.space == right.space &&
            left.content == right.content && left.visibility == right.visibility && left.state == right.state &&
            left.attachments.map { listOf(it.name, it.filename, it.type, it.size.toString(), it.externalLink) } ==
            right.attachments.map { listOf(it.name, it.filename, it.type, it.size.toString(), it.externalLink) }

    fun isOwnPersonal(memo: Memo, userName: String): Boolean = memo.creator == userName && memo.space.isNullOrEmpty()

    /** A resource cannot be deleted while preserved source still points at it. */
    fun validateAttachmentRemoval(base: Memo?, content: String, retainedNames: Set<String>) {
        base?.attachments?.filter { it.name !in retainedNames }?.forEach { attachment ->
            val managed = Regex("(?:/?file/attachments/|attachments/)${Regex.escape(attachment.uid)}(?=[/?#)\\s\"'>]|$)")
            val referenced = managed.containsMatchIn(content) ||
                (attachment.externalLink.isNotEmpty() && content.contains(attachment.externalLink))
            require(!referenced) { "正文仍引用附件“${attachment.filename}”，请先在源码中删除对应引用后再移除；草稿和原附件已保留" }
        }
    }

    fun rewriteAttachmentReference(content: String, original: Attachment, replacement: Attachment): String {
        val oldPath = Regex("/file/attachments/${Regex.escape(original.uid)}(?=[/?#)\\s\"'>]|$)")
        var result = oldPath.replace(content) { "/file/attachments/${replacement.uid}" }
        if (original.externalLink.isNotBlank()) {
            val external = Regex(Regex.escape(original.externalLink) + "(?=[#\\s)\"'>]|$)")
            val filename = URLEncoder.encode(replacement.filename, Charsets.UTF_8.name()).replace("+", "%20")
            result = external.replace(result) { "/file/attachments/${replacement.uid}/$filename" }
        }
        return result
    }
}
