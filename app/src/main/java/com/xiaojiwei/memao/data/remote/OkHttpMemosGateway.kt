package com.xiaojiwei.memao.data.remote

import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.Attachment
import com.xiaojiwei.memao.model.ConnectionInfo
import com.xiaojiwei.memao.model.CreateMemo
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.MemoPage
import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.MemosException
import com.xiaojiwei.memao.model.MemosGateway
import com.xiaojiwei.memao.model.TagCount
import com.xiaojiwei.memao.model.UpdateMemo
import com.xiaojiwei.memao.model.UploadFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Memos 0.31 REST gateway. Transport retries and implicit redirects are disabled:
 * a failed write may already have committed, and credentials are origin scoped.
 */
class OkHttpMemosGateway(
    private val tokenProvider: (String) -> String?,
    client: OkHttpClient = OkHttpClient(),
) : MemosGateway {
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun inspect(serverUrl: String, token: String): ConnectionInfo = io {
        val origin = MemosProtocol.serverOrigin(serverUrl)
        requireToken(token)
        val profile = json(origin, endpoint(origin, "api/v1/instance/profile"), token)
        val version = profile.optString("version").removePrefix("v")
        if (version != "0.31.0") throw MemosException(
            "首版仅支持 Memos 0.31.0，当前服务器版本为 ${version.ifBlank { "未知" }}",
        )
        val user = json(origin, endpoint(origin, "api/v1/auth/me"), token).getJSONObject("user")
        ConnectionInfo(
            userName = MemosProtocol.resourceName(user.getString("name"), "users"),
            displayName = user.optString("displayName").ifBlank { user.optString("username") },
            username = user.optString("username"),
            version = version,
            commit = profile.optString("commit"),
        )
    }

    override suspend fun list(account: Account, query: MemoQuery, pageToken: String): MemoPage = io {
        val origin = origin(account)
        val url = endpoint(origin, "api/v1/memos").newBuilder()
            .addQueryParameter("state", if (query.archived) "ARCHIVED" else "NORMAL")
            .addQueryParameter("pageSize", query.pageSize.coerceIn(1, 1000).toString())
            .addQueryParameter("orderBy", if (query.updatedSince == null) "create_time desc" else "update_time asc, name asc")
            .addQueryParameter("filter", MemosProtocol.filter(account.userName, query))
            .apply { if (pageToken.isNotEmpty()) addQueryParameter("pageToken", pageToken) }
            .build()
        val body = json(origin, url, token(account))
        val memos = MemosProtocol.objects(body.optJSONArray("memos")).map(MemosProtocol::memo)
        if (memos.any { !isPersonal(account, it) || it.archived != query.archived }) {
            throw MemosException("服务器返回了当前账号范围之外的笔记，已停止加载")
        }
        MemoPage(memos, body.optString("nextPageToken"))
    }

    override suspend fun get(account: Account, name: String): Memo = io {
        val origin = origin(account)
        val value = MemosProtocol.memo(json(origin, resource(origin, name, "memos"), token(account)))
        ownMemo(account, value)
    }

    override suspend fun tags(account: Account): List<TagCount> = io {
        val origin = origin(account)
        MemosProtocol.resourceName(account.userName, "users")
        val url = endpoint(origin, "api/v1/${account.userName}:getStats").newBuilder()
            .addQueryParameter("filter", "space == null")
            .build()
        val values = json(origin, url, token(account)).optJSONObject("tagCount") ?: JSONObject()
        values.keys().asSequence().map { TagCount(it, values.optInt(it)) }
            .sortedBy { it.tag }.toList()
    }

    override suspend fun create(account: Account, request: CreateMemo): Memo = io {
        val origin = origin(account)
        val url = endpoint(origin, "api/v1/memos").newBuilder()
            .addQueryParameter("memoId", MemosProtocol.uid(request.uid)).build()
        val body = JSONObject()
            .put("content", request.content)
            .put("visibility", "PRIVATE")
            .put("attachments", MemosProtocol.attachmentNames(request.attachments))
        writtenMemo(account, json(origin, url, token(account), "POST", body))
    }

    override suspend fun update(account: Account, request: UpdateMemo): Memo = io {
        val origin = origin(account)
        val body = JSONObject()
        val mask = mutableListOf<String>()
        request.content?.let { body.put("content", it); mask.add("content") }
        request.attachments?.let {
            body.put("attachments", MemosProtocol.attachmentNames(it)); mask.add("attachments")
        }
        if (request.content != null || request.attachments != null || request.state != null) mask.add("update_time")
        request.state?.let {
            if (it != "NORMAL" && it != "ARCHIVED") throw MemosException("无效的笔记状态")
            body.put("state", it); mask.add("state")
        }
        if (mask.isEmpty()) throw MemosException("没有需要保存的修改")
        val url = resource(origin, request.name, "memos").newBuilder()
            .addQueryParameter("updateMask", mask.joinToString(",")).build()
        writtenMemo(account, json(origin, url, token(account), "PATCH", body))
    }

    override suspend fun delete(account: Account, name: String): Unit = io {
        val origin = origin(account)
        json(origin, resource(origin, name, "memos"), token(account), "DELETE")
        Unit
    }

    override suspend fun upload(account: Account, request: UploadFile): Attachment = io {
        if (!request.file.isFile || !request.file.canRead()) throw MemosException("草稿附件文件不可读取")
        MemosProtocol.uid(request.uid)
        val origin = origin(account)
        val token = token(account)
        val name = "attachments/${request.uid}"
        existingAttachment(origin, token, name)?.let {
            validateUploadIdentity(it, request)
            return@io it
        }
        val uploadUrl = endpoint(origin, "api/v1/attachments:upload")
        val specification = JSONObject()
            .put("attachment", JSONObject().put("filename", request.filename).put("type", request.type))
            .put("attachmentId", request.uid)
            .put("totalSize", request.file.length().toString())
        var progress = json(origin, uploadUrl, token, "POST", JSONObject()
            .put("spec", specification).put("writeOffset", "0"))
        val uploadId = progress.getString("uploadId")
        val chunkSize = progress.optInt("maxChunkSize", MAX_CHUNK_SIZE).coerceIn(1, MAX_CHUNK_SIZE)
        RandomAccessFile(request.file, "r").use { file ->
            var offset = progress.optLong("committedSize")
            var failures = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                progress.optJSONObject("attachment")?.let { return@io MemosProtocol.attachment(it) }
                if (offset < 0 || offset > file.length()) throw MemosException("服务器上传进度无效")
                file.seek(offset)
                val bytes = ByteArray(minOf(chunkSize.toLong(), file.length() - offset).toInt())
                file.readFully(bytes)
                val last = offset + bytes.size == file.length()
                val payload = JSONObject().put("uploadId", uploadId)
                    .put("writeOffset", offset.toString())
                    .put("data", Base64.getEncoder().encodeToString(bytes))
                    .put("finishWrite", last)
                try {
                    progress = json(origin, uploadUrl, token, "POST", payload)
                    val next = progress.optLong("committedSize", -1)
                    if (next != offset + bytes.size) throw MemosException("服务器上传进度与本地文件不一致")
                    offset = next
                    failures = 0
                    if (last) return@io progress.optJSONObject("attachment")?.let(MemosProtocol::attachment)
                        ?: throw MemosException("上传完成但服务器没有返回附件，请手动重试核对结果", uncertain = true)
                } catch (error: MemosException) {
                    if (!error.uncertain || failures++ >= 1) throw error
                    try {
                        existingAttachment(origin, token, name)?.let {
                            validateUploadIdentity(it, request)
                            return@io it
                        }
                        // No bytes and finishWrite=false queries committed progress without writing.
                        progress = json(origin, uploadUrl, token, "POST", JSONObject().put("uploadId", uploadId))
                        val committed = progress.optLong("committedSize", -1)
                        if (committed != offset && committed != offset + bytes.size) {
                            throw MemosException("服务器上传进度无法核对，请手动重试", uncertain = true)
                        }
                        offset = committed
                    } catch (verification: MemosException) {
                        throw MemosException("上传结果暂时无法核对，草稿已保留，请手动重试",
                            statusCode = verification.statusCode, uncertain = true, cause = verification)
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw MemosException("上传未完成")
    }

    override suspend fun download(account: Account, attachment: Attachment, destination: File): Unit = io {
        val origin = origin(account)
        val url = if (attachment.externalLink.isNotBlank()) {
            origin.resolve(attachment.externalLink)
                ?: throw MemosException("附件地址无效")
        } else {
            MemosProtocol.resourceName(attachment.name, "attachments")
            endpoint(origin, "file/attachments/${attachment.uid}")
        }
        if (url.scheme != "http" && url.scheme != "https") throw MemosException("附件地址协议不受支持")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw MemosException("附件地址不能包含凭据")
        val directory = destination.absoluteFile.parentFile ?: throw MemosException("附件保存目录无效")
        if (!directory.exists() && !directory.mkdirs()) throw MemosException("无法创建附件保存目录")
        val temporary = File.createTempFile(".import-", ".part", directory)
        try {
            downloadResponse(origin, url, token(account)).use { response ->
                if (!response.isSuccessful) throw failure(response, token(account), false)
                val body = response.body ?: throw MemosException("服务器返回了空附件")
                FileOutputStream(temporary).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.fd.sync()
                }
                val expected = body.contentLength()
                if (expected >= 0 && temporary.length() != expected) throw MemosException("附件未完整下载，请重试")
            }
            currentCoroutineContext().ensureActive()
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Same-directory rename is atomic on Android's private-file filesystem.
                if (!temporary.renameTo(destination)) throw MemosException("无法保存完整附件")
            }
        } finally {
            temporary.delete()
        }
    }

    private suspend fun existingAttachment(origin: HttpUrl, token: String, name: String): Attachment? =
        try {
            MemosProtocol.attachment(json(origin, resource(origin, name, "attachments"), token))
        } catch (error: MemosException) {
            if (error.statusCode == 404) null else throw error
        }

    private fun validateUploadIdentity(attachment: Attachment, request: UploadFile) {
        if (attachment.name != "attachments/${request.uid}" || attachment.filename != request.filename) {
            throw MemosException("固定附件标识已被其他文件占用，请保留草稿并检查")
        }
    }

    private suspend fun downloadResponse(origin: HttpUrl, initial: HttpUrl, token: String): Response {
        var target = initial
        var mayAuthenticate = sameOrigin(origin, initial)
        repeat(6) { hop ->
            val request = Request.Builder().url(target).get().apply {
                if (mayAuthenticate && sameOrigin(origin, target)) header("Authorization", "Bearer $token")
            }.build()
            val response = execute(request, false)
            if (response.code !in REDIRECT_CODES) return response
            val next = response.header("Location")?.let(target::resolve)
            response.close()
            if (hop == 5 || next == null || next.username.isNotEmpty() || next.password.isNotEmpty()) {
                throw MemosException("附件重定向地址无效或次数过多")
            }
            if (target.scheme == "https" && next.scheme == "http") {
                throw MemosException("附件重定向降低了连接安全级别，已停止下载")
            }
            // Once a chain leaves the server origin it can never regain the token.
            mayAuthenticate = mayAuthenticate && sameOrigin(origin, next)
            target = next
        }
        throw MemosException("附件重定向次数过多")
    }

    private suspend fun json(
        origin: HttpUrl,
        url: HttpUrl,
        token: String,
        method: String = "GET",
        payload: JSONObject? = null,
    ): JSONObject {
        if (!sameOrigin(origin, url)) throw MemosException("接口地址不属于当前服务器")
        val write = method != "GET"
        val body = payload?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, body).build()
        try {
            return execute(request, write).use { response ->
                if (!response.isSuccessful) throw failure(response, token, write)
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) JSONObject() else try {
                    JSONObject(text)
                } catch (error: org.json.JSONException) {
                    throw MemosException("服务器返回了无效的数据格式", response.code, write, error)
                }
            }
        } catch (error: IOException) {
            // Losing a response body is still an unknown write outcome.
            throw MemosException("网络响应未完整接收，请手动核对结果", uncertain = write, cause = error)
        }
    }

    private suspend fun execute(request: Request, write: Boolean): Response {
        val call = http.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(
                        MemosException("网络请求失败，请检查连接后手动重试", uncertain = write, cause = e),
                    )
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
    }

    private fun failure(response: Response, token: String, write: Boolean): MemosException {
        val body = response.body?.string().orEmpty()
        val fallback = when (response.code) {
            401 -> "Token 无效或已过期，请更新该账号令牌"
            403 -> "没有权限执行此操作"
            404 -> "笔记、附件或接口不存在"
            409 -> "资源已存在，请核对服务器结果"
            413 -> "附件超过服务器允许的大小，草稿已保留"
            429 -> "请求过于频繁，请稍后手动重试"
            in 300..399 -> "服务器接口发生重定向，请检查根地址"
            else -> "服务器请求失败（${response.code}）"
        }
        val detail = try {
            JSONObject(body).optString("message").replace(token, "[令牌]").take(240)
        } catch (_: org.json.JSONException) { "" }
        val message = if (detail.isBlank() || response.code == 401) fallback else "$fallback：$detail"
        return MemosException(message, response.code, write && response.code >= 500)
    }

    private fun origin(account: Account): HttpUrl = MemosProtocol.serverOrigin(account.serverUrl)
    private fun token(account: Account): String = tokenProvider(account.id)?.also(::requireToken)
        ?: throw MemosException("此账号缺少 Token，请重新连接", 401)
    private fun requireToken(token: String) {
        // Validate before OkHttp builds Authorization: its validation exception
        // can include the supplied header value, which must never expose a PAT.
        if (token.isEmpty() || token.any { it.code !in 0x21..0x7e }) {
            throw MemosException("请输入有效的 Token，不包含空格或 Bearer 前缀", 401)
        }
    }
    private fun endpoint(origin: HttpUrl, path: String): HttpUrl =
        origin.newBuilder().addPathSegments(path).build()
    private fun resource(origin: HttpUrl, name: String, collection: String): HttpUrl =
        endpoint(origin, "api/v1/${MemosProtocol.resourceName(name, collection)}")
    private fun isPersonal(account: Account, memo: Memo): Boolean =
        memo.creator == account.userName && memo.space.isNullOrBlank()
    private fun ownMemo(account: Account, memo: Memo): Memo {
        if (!isPersonal(account, memo)) throw MemosException("只能访问本人个人笔记", 403)
        return memo
    }

    private fun writtenMemo(account: Account, value: JSONObject): Memo = try {
        ownMemo(account, MemosProtocol.memo(value))
    } catch (error: org.json.JSONException) {
        throw MemosException("服务器已响应，但写入结果无法核对，请保留草稿后重试", uncertain = true, cause = error)
    } catch (error: MemosException) {
        throw MemosException("服务器已响应，但返回的笔记范围或标识无效，请核对结果", uncertain = true, cause = error)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: IOException) {
            throw MemosException("网络或文件读取失败，请保留草稿后重试", cause = error)
        } catch (error: org.json.JSONException) {
            throw MemosException("服务器返回的数据缺少必要字段", cause = error)
        }
    }

    companion object {
        const val MAX_CHUNK_SIZE = 2 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
            a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
