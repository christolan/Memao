package com.xiaojiwei.memao.data.local

import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.Attachment
import com.xiaojiwei.memao.model.Draft
import com.xiaojiwei.memao.model.DraftAttachment
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.SendState
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, versioned local serialization; remote raw JSON is preserved as opaque text. */
object ModelCodec {
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    fun account(value: Account): String = JSONObject().apply {
        put("id", value.id); put("serverUrl", value.serverUrl); put("userName", value.userName)
        put("displayName", value.displayName); put("username", value.username)
        put("version", value.version); put("commit", value.commit); put("needsAuthentication", value.needsAuthentication)
    }.toString()

    fun account(json: String): Account = JSONObject(json).run {
        Account(getString("id"), getString("serverUrl"), getString("userName"), getString("displayName"),
            optString("username"), optString("version", "0.31.0"), optString("commit"), optBoolean("needsAuthentication"))
    }

    private fun attachmentJson(value: Attachment): JSONObject = JSONObject().apply {
        put("name", value.name); put("filename", value.filename); put("type", value.type)
        put("size", value.size); put("externalLink", value.externalLink); put("width", value.width)
        put("height", value.height); put("rawJson", value.rawJson)
    }

    private fun attachment(value: JSONObject): Attachment = value.run {
        Attachment(getString("name"), optString("filename"), optString("type"), optLong("size"),
            optString("externalLink"), optInt("width"), optInt("height"), optString("rawJson"))
    }

    private fun memoJson(value: Memo): JSONObject = JSONObject().apply {
        put("name", value.name); put("content", value.content); put("creator", value.creator)
        put("createTime", value.createTime); put("updateTime", value.updateTime); put("visibility", value.visibility)
        put("state", value.state); put("space", value.space ?: JSONObject.NULL)
        put("attachments", JSONArray(value.attachments.map(::attachmentJson)))
        put("tags", JSONArray(value.tags)); put("rawJson", value.rawJson)
    }

    private fun memo(value: JSONObject): Memo = value.run {
        Memo(getString("name"), optString("content"), getString("creator"), optString("createTime"),
            optString("updateTime"), optString("visibility", "PRIVATE"), optString("state", "NORMAL"),
            nullable("space"), (optJSONArray("attachments") ?: JSONArray()).objects().map(::attachment),
            (optJSONArray("tags") ?: JSONArray()).strings(), optString("rawJson"))
    }

    fun memo(value: Memo): String = memoJson(value).toString()
    fun memo(json: String): Memo = memo(JSONObject(json))

    fun draft(value: Draft): String = JSONObject().apply {
        put("id", value.id); put("accountId", value.accountId); put("memoName", value.memoName ?: JSONObject.NULL)
        put("createUid", value.createUid); put("content", value.content)
        put("baseMemo", value.baseMemo?.let(::memoJson) ?: JSONObject.NULL)
        put("revision", value.revision); put("createdAt", value.createdAt); put("updatedAt", value.updatedAt)
        put("cursorStart", value.cursorStart); put("cursorEnd", value.cursorEnd)
        put("sendState", value.sendState.name); put("error", value.error ?: JSONObject.NULL)
        put("attachments", JSONArray(value.attachments.map { item ->
            JSONObject().apply {
                put("id", item.id); put("filename", item.filename); put("type", item.type); put("size", item.size)
                put("localPath", item.localPath); put("uploadUid", item.uploadUid)
                put("original", item.original?.let(::attachmentJson) ?: JSONObject.NULL)
                put("uploaded", item.uploaded?.let(::attachmentJson) ?: JSONObject.NULL)
            }
        }))
    }.toString()

    fun draft(json: String): Draft = JSONObject(json).run {
        Draft(
            id = getString("id"), accountId = getString("accountId"), memoName = nullable("memoName"),
            createUid = getString("createUid"), content = optString("content"),
            attachments = (optJSONArray("attachments") ?: JSONArray()).objects().map { item ->
                DraftAttachment(item.getString("id"), item.getString("filename"), item.getString("type"),
                    item.getLong("size"), item.getString("localPath"),
                    item.optJSONObject("original")?.let(::attachment), item.getString("uploadUid"),
                    item.optJSONObject("uploaded")?.let(::attachment))
            },
            baseMemo = optJSONObject("baseMemo")?.let(::memo), revision = optLong("revision"),
            createdAt = getLong("createdAt"), updatedAt = getLong("updatedAt"),
            cursorStart = optInt("cursorStart"), cursorEnd = optInt("cursorEnd"),
            sendState = runCatching { SendState.valueOf(optString("sendState", "SAVED")) }.getOrDefault(SendState.SAVED),
            error = nullable("error"),
        )
    }
}
