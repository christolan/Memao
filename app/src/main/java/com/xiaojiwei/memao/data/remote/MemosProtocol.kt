package com.xiaojiwei.memao.data.remote

import com.xiaojiwei.memao.model.Attachment
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.MemosException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** REST/ProtoJSON rules pinned to Memos 0.31.0. No credential is stored here. */
object MemosProtocol {
    private val uidPattern = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,34}[a-zA-Z0-9])?$")

    fun serverOrigin(value: String): HttpUrl {
        val url = value.trim().toHttpUrlOrNull()
            ?: throw MemosException("请输入有效的 HTTP 或 HTTPS 服务器地址")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null
        ) throw MemosException("首版仅支持服务器根地址，不支持子路径、地址内凭据或查询参数")
        return url
    }

    fun resourceName(value: String, collection: String): String {
        val parts = value.split('/')
        if (parts.size != 2 || parts[0] != collection || !uidPattern.matches(parts[1])) {
            throw MemosException("服务器资源标识无效")
        }
        return value
    }

    fun uid(value: String): String {
        if (!uidPattern.matches(value)) throw MemosException("资源标识无效")
        return value
    }

    /** CEL double-quoted literal, then HttpUrl performs URL encoding separately. */
    fun literal(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }

    fun filter(userName: String, query: MemoQuery): String = buildString {
        resourceName(userName, "users")
        append("creator == ").append(literal(userName)).append(" && space == null")
        if (!query.archived) {
            query.tag?.takeIf { it.isNotEmpty() }?.let {
                append(" && ").append(literal(it)).append(" in tags")
            }
            if (query.keyword.isNotEmpty()) append(" && content.contains(")
                .append(literal(query.keyword)).append(')')
        }
    }

    fun attachment(value: JSONObject): Attachment {
        val metadata = value.optJSONObject("mediaMetadata")
        return Attachment(
            name = resourceName(value.getString("name"), "attachments"),
            filename = value.optString("filename"),
            type = value.optString("type", "application/octet-stream"),
            size = value.optLong("size"),
            externalLink = value.optString("externalLink"),
            width = metadata?.optInt("width") ?: 0,
            height = metadata?.optInt("height") ?: 0,
            rawJson = value.toString(),
        )
    }

    fun memo(value: JSONObject): Memo = Memo(
        name = resourceName(value.getString("name"), "memos"),
        content = value.optString("content"),
        creator = value.optString("creator"),
        createTime = value.optString("createTime"),
        updateTime = value.optString("updateTime"),
        visibility = value.optString("visibility", "PRIVATE"),
        state = value.optString("state", "NORMAL"),
        space = if (value.isNull("space")) null else value.optString("space").ifEmpty { null },
        attachments = objects(value.optJSONArray("attachments")).map(::attachment),
        tags = value.optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } }
            ?: emptyList(),
        rawJson = value.toString(),
    )

    fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else List(array.length()) { array.getJSONObject(it) }

    fun attachmentNames(attachments: List<Attachment>): JSONArray = JSONArray().also { values ->
        attachments.forEach { values.put(JSONObject().put("name", resourceName(it.name, "attachments"))) }
    }
}
