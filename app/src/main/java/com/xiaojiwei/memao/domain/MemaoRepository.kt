package com.xiaojiwei.memao.domain

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.room.Room
import androidx.room.withTransaction
import com.xiaojiwei.memao.data.local.AccountRecord
import com.xiaojiwei.memao.data.local.AttemptRecord
import com.xiaojiwei.memao.data.local.BlobRecord
import com.xiaojiwei.memao.data.local.DraftRecord
import com.xiaojiwei.memao.data.local.MemaoDatabase
import com.xiaojiwei.memao.data.local.MemoRecord
import com.xiaojiwei.memao.data.local.ModelCodec
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.model.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

private const val RECENT_SEARCH_LIMIT = 20

/** Owns all account-scoped persistence and every explicit server write. No background publishing. */
class MemaoRepository(
    context: Context,
    private val gateway: MemosGateway,
    private val tokenVault: TokenVault = TokenVault(context),
    private val database: MemaoDatabase = Room.databaseBuilder(context.applicationContext, MemaoDatabase::class.java, "memao.db").build(),
    preferencesName: String = "memao_preferences",
    filesRoot: File? = null,
) {
    private val app = context.applicationContext
    private val dao = database.dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = app.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val searchHistoryLock = Any()
    private val removedSearchAccounts = mutableSetOf<String>()
    private val root = (filesRoot ?: File(app.filesDir, "accounts")).apply { mkdirs() }
    private val localLock = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val blobLocks = mutableMapOf<String, Mutex>()
    /** Leases cover group edit preparation and imports before their DB references exist. */
    private val temporaryPins = mutableMapOf<String, Set<String>>()
    private val deletedDraftIds = mutableSetOf<String>()
    private val sendLocks = mutableMapOf<String, Mutex>()
    private val activeSends = mutableMapOf<String, Job>()
    private val mutableActive = MutableStateFlow(preferences.getString("active_account", null))
    private val mutableSettings = MutableStateFlow(AppSettings(runCatching { ThemeMode.valueOf(preferences.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)))
    private data class QueryKey(val accountId: String, val query: MemoQuery)
    private val membership = MutableStateFlow<Map<QueryKey, Set<String>>>(emptyMap())
    private val nextPages = ConcurrentHashMap<QueryKey, String>()
    private val queryLocks = mutableMapOf<QueryKey, Mutex>()

    val accounts: StateFlow<List<Account>> = dao.observeAccounts().map { list -> list.map { ModelCodec.account(it.payload) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val activeAccountId: StateFlow<String?> = mutableActive.asStateFlow()
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    init {
        scope.launch {
            try {
                val stored = dao.accounts()
                if (stored.none { it.id == mutableActive.value }) {
                    val selected = stored.firstOrNull()?.id
                    check(preferences.edit().putString("active_account", selected).commit())
                    mutableActive.value = selected
                }
                recoverLocalFiles()
                tokenVault.retainAccounts(stored.map { it.id }.toSet())
                initialized.complete(Unit)
            } catch (error: Exception) { initialized.completeExceptionally(error) }
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun now(): Long = System.currentTimeMillis()
    private fun accountDirectory(id: String): File = File(root, id).apply { mkdirs() }
    private fun blobFile(accountId: String, id: String): File = File(accountDirectory(accountId), "blobs/$id").also { it.parentFile!!.mkdirs() }
    private suspend fun account(id: String): Account = dao.account(id)?.let { ModelCodec.account(it.payload) } ?: throw IllegalStateException("该账号已从本机移除")
    private suspend fun draft(accountId: String, id: String): Draft = dao.draft(accountId, id)?.let { ModelCodec.draft(it.payload) } ?: throw IllegalStateException("草稿不存在")
    private fun draftRecord(value: Draft): DraftRecord = DraftRecord(value.id, value.accountId, value.revision, value.updatedAt, ModelCodec.draft(value))
    private fun checkOwner(value: Memo, account: Account): Memo {
        require(DomainRules.isOwnPersonal(value, account.userName)) { "此笔记不属于当前账号的个人笔记" }
        return value
    }

    suspend fun connect(server: String, token: String): Account = withContext(Dispatchers.IO) {
        initialized.await()
        val origin = DomainRules.normalizeServer(server)
        require(token.isNotBlank()) { "请填写 Memos Token" }
        val info = gateway.inspect(origin, token.trim())
        require(info.version.removePrefix("v") == "0.31.0") { "首版只支持 Memos 0.31.0；当前实例为 ${info.version}" }
        require(info.userName.startsWith("users/")) { "服务器未返回有效的账号身份" }
        val id = hash("$origin\n${info.userName}")
        val value = Account(id, origin, info.userName, info.displayName.ifBlank { info.username.ifBlank { info.userName } }, info.username, info.version, info.commit)
        localLock.withLock {
            val previous = tokenVault.get(id)
            tokenVault.put(id, token.trim())
            try { dao.putAccount(AccountRecord(id, ModelCodec.account(value))) }
            catch (error: Exception) {
                if (previous == null) tokenVault.remove(id) else tokenVault.put(id, previous)
                throw error
            }
            synchronized(searchHistoryLock) { removedSearchAccounts.remove(id) }
        }
        selectAccount(id)
        value
    }

    suspend fun selectAccount(id: String?) = withContext(Dispatchers.IO) {
        initialized.await()
        if (id != null) account(id)
        if (mutableActive.value != id) synchronized(activeSends) { activeSends.values.toList() }.forEach { it.cancel() }
        check(preferences.edit().putString("active_account", id).commit()) { "无法保存当前账号" }
        mutableActive.value = id
    }

    suspend fun removeAccount(id: String) = withContext(Dispatchers.IO) {
        initialized.await()
        synchronized(activeSends) { activeSends.filterKeys { it.startsWith("$id:") }.values.toList() }.forEach { it.cancel() }
        localLock.withLock {
            database.withTransaction {
                dao.deleteAttempts(id); dao.deleteDrafts(id); dao.deleteMemos(id); dao.deleteBlobs(id); dao.deleteAccount(id)
            }
            synchronized(searchHistoryLock) {
                // A search from a still-visible screen must not restore history after removal.
                removedSearchAccounts.add(id)
                preferences.edit().remove("recent_searches_$id").apply()
            }
            tokenVault.remove(id)
            File(root, id).deleteRecursively()
            preferences.edit().remove("viewed_$id").apply()
            membership.value = membership.value.filterKeys { it.accountId != id }
        }
        if (mutableActive.value == id) selectAccount(dao.accounts().firstOrNull()?.id)
    }

    fun setTheme(theme: ThemeMode) {
        check(preferences.edit().putString("theme", theme.name).commit()) { "无法保存外观设置" }
        mutableSettings.value = AppSettings(theme)
    }

    fun lastViewed(accountId: String): String? = preferences.getString("viewed_$accountId", null)
    fun rememberViewed(accountId: String, name: String) { preferences.edit().putString("viewed_$accountId", name).apply() }

    fun recentSearches(accountId: String): List<String> {
        val stored = preferences.getString("recent_searches_$accountId", null) ?: return emptyList()
        return try {
            val entries = JSONArray(stored)
            (0 until minOf(entries.length(), RECENT_SEARCH_LIMIT)).mapNotNull { entries.opt(it) as? String }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    fun rememberSearch(accountId: String, value: String): List<String> = synchronized(searchHistoryLock) {
        if (accountId in removedSearchAccounts) return@synchronized emptyList()
        val previous = recentSearches(accountId)
        if (value.isBlank()) return@synchronized previous
        val foldedKeyword = DomainRules.asciiFold(value)
        val updated = (listOf(value) + previous.filter { DomainRules.asciiFold(it) != foldedKeyword }).take(RECENT_SEARCH_LIMIT)
        preferences.edit().putString("recent_searches_$accountId", JSONArray(updated).toString()).apply()
        updated
    }

    fun observeMemos(accountId: String, query: MemoQuery = MemoQuery()): Flow<List<Memo>> =
        combine(dao.observeMemos(accountId), membership) { rows, visible ->
            val names = visible[QueryKey(accountId, query)]
            val remoteOrder = names?.withIndex()?.associate { it.value to it.index }.orEmpty()
            rows.map { ModelCodec.memo(it.payload) }.filter { DomainRules.matches(it, query) && (names == null || it.name in names) }
                .sortedWith(compareByDescending<Memo> { runCatching { Instant.parse(it.createTime).toEpochMilli() }.getOrDefault(0) }
                    .thenBy { remoteOrder[it.name] ?: Int.MAX_VALUE }.thenByDescending { it.name })
        }

    fun observeDrafts(accountId: String): Flow<List<Draft>> = dao.observeDrafts(accountId).map { list -> list.map { ModelCodec.draft(it.payload) } }

    suspend fun getDraft(accountId: String, id: String): Draft = withContext(Dispatchers.IO) { initialized.await(); draft(accountId, id) }

    private suspend fun fetchPage(accountId: String, query: MemoQuery, more: Boolean): PageResult = withContext(Dispatchers.IO) {
        initialized.await()
        val key = QueryKey(accountId, query)
        val lock = synchronized(queryLocks) { queryLocks.getOrPut(key) { Mutex() } }
        lock.withLock {
            val owner = account(accountId)
            val pageToken = if (more) nextPages[key] ?: "" else ""
            if (more && pageToken.isEmpty()) return@withLock PageResult(false)
            try {
                val page = gateway.list(owner, query, pageToken)
                page.memos.forEach { checkOwner(it, owner) }
                localLock.withLock {
                    account(accountId)
                    dao.putMemos(page.memos.map { MemoRecord(accountId, it.name, ModelCodec.memo(it), now()) })
                    val old = if (more) membership.value[key].orEmpty() else emptySet()
                    membership.value = membership.value + (key to (old + page.memos.map { it.name }))
                    nextPages[key] = page.nextPageToken
                    trimCacheLocked()
                }
                PageResult(page.nextPageToken.isNotEmpty())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                markAuthentication(owner, error)
                if (error is MemosException && error.statusCode in listOf(400, 401, 403)) throw error
                localLock.withLock { membership.value = membership.value - key }
                PageResult(nextPages[key]?.isNotEmpty() == true, offline = true)
            }
        }
    }

    suspend fun refresh(accountId: String, query: MemoQuery = MemoQuery()): PageResult = fetchPage(accountId, query, false)
    suspend fun loadMore(accountId: String, query: MemoQuery = MemoQuery()): PageResult = fetchPage(accountId, query, true)

    suspend fun tags(accountId: String, cachedOnly: Boolean = false): List<TagCount> = withContext(Dispatchers.IO) {
        initialized.await()
        val owner = account(accountId)
        if (!cachedOnly) {
            try { return@withContext gateway.tags(owner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { markAuthentication(owner, error) }
        }
        dao.memos(accountId).map { ModelCodec.memo(it.payload) }.filter { !it.archived }
            .flatMap { it.tags }.groupingBy { it }.eachCount().map { TagCount(it.key, it.value) }.sortedBy { it.tag }
    }

    suspend fun openMemo(accountId: String, name: String): Memo = withContext(Dispatchers.IO) {
        initialized.await()
        val owner = account(accountId)
        try {
            val value = checkOwner(gateway.get(owner, name), owner)
            localLock.withLock { account(accountId); cacheMemo(owner, value) }
            rememberViewed(accountId, name)
            value
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            markAuthentication(owner, error)
            if (error is MemosException && error.statusCode in listOf(403, 404)) {
                localLock.withLock {
                    dao.deleteMemo(accountId, name)
                    forgetMemoMembership(accountId, name)
                }
                throw error
            }
            dao.memo(accountId, name)?.let { ModelCodec.memo(it.payload) } ?: throw error
        }
    }

    private fun attachmentKey(accountId: String, attachment: Attachment): String = hash("$accountId|${attachment.name}|${attachment.size}|${attachment.type}|${attachment.externalLink}")

    suspend fun localAttachment(accountId: String, attachment: Attachment): File? = withContext(Dispatchers.IO) {
        initialized.await()
        val record = dao.blob(accountId, attachment.name) ?: return@withContext null
        if (record.id != attachmentKey(accountId, attachment)) return@withContext null
        val file = File(record.path)
        file.takeIf { it.isFile && it.canRead() && it.length() == record.size }
    }

    suspend fun ensureAttachment(accountId: String, attachment: Attachment): File = withContext(Dispatchers.IO) {
        initialized.await()
        val id = attachmentKey(accountId, attachment)
        val lock = synchronized(blobLocks) { blobLocks.getOrPut(id) { Mutex() } }
        lock.withLock {
            val owner = account(accountId)
            val cachedFile = localLock.withLock {
                account(accountId)
                val cached = dao.blob(accountId, attachment.name)
                if (cached?.id == id) {
                    val file = File(cached.path)
                    if (file.isFile && file.canRead() && file.length() == cached.size) {
                        dao.putBlob(cached.copy(lastUsed = now()))
                        return@withLock file
                    }
                }
                null
            }
            if (cachedFile != null) return@withLock cachedFile
            val destination = blobFile(accountId, id)
            val lease = "download:${newUid()}"
            localLock.withLock {
                account(accountId)
                temporaryPins[lease] = setOf(destination.absolutePath, destination.absolutePath + ".part")
            }
            try {
                // The network runs outside the global transaction lock so typing keeps saving.
                gateway.download(owner, attachment, destination)
                require(destination.isFile && destination.canRead()) { "附件尚未完整保存" }
                localLock.withLock {
                    account(accountId)
                    dao.putBlob(BlobRecord(id, accountId, attachment.name, destination.absolutePath, destination.length(), now()))
                    trimCacheLocked(setOf(destination.absolutePath))
                }
                destination
            } catch (error: Exception) {
                destination.delete()
                markAuthentication(owner, error)
                throw error
            } finally {
                withContext(NonCancellable) {
                    localLock.withLock {
                        temporaryPins.remove(lease)
                        // Count the newly released file against ordinary cache while retaining
                        // the file being returned to its immediate consumer.
                        trimCacheLocked(setOf(destination.absolutePath))
                    }
                }
            }
        }
    }

    suspend fun isMemoComplete(accountId: String, memo: Memo): Boolean = withContext(Dispatchers.IO) {
        initialized.await()
        dao.memo(accountId, memo.name) != null && memo.attachments.all { localAttachment(accountId, it) != null }
    }

    fun newDraft(accountId: String): Draft = Draft(accountId = accountId)

    data class PreparedEdit(val draft: Draft, val created: Boolean)

    suspend fun prepareEdit(accountId: String, name: String): Draft = prepareEditSession(accountId, name).draft

    suspend fun prepareEditSession(accountId: String, name: String): PreparedEdit {
        var prepared: PreparedEdit? = null
        try {
            return withContext(Dispatchers.IO) {
                initialized.await()
                dao.drafts(accountId).map { ModelCodec.draft(it.payload) }.firstOrNull { it.memoName == name }?.let { return@withContext PreparedEdit(it, created = false) }
                val memo = openMemo(accountId, name)
                val lease = "edit:${newUid()}"
                localLock.withLock {
                    account(accountId)
                    temporaryPins[lease] = memo.attachments.map { attachment ->
                        val cached = dao.blob(accountId, attachment.name)
                        if (cached?.id == attachmentKey(accountId, attachment)) cached.path
                        else blobFile(accountId, attachmentKey(accountId, attachment)).absolutePath
                    }.toSet()
                }
                try {
                    for (attachment in memo.attachments) {
                        if (localAttachment(accountId, attachment) == null) {
                            try { ensureAttachment(accountId, attachment) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { throw IOException("此笔记的附件尚未完整缓存，请联网补齐后编辑", error) }
                        }
                    }
                    localLock.withLock {
                        account(accountId)
                        // Another preparation may have finished while this request cached its attachments.
                        dao.drafts(accountId).map { ModelCodec.draft(it.payload) }.firstOrNull { it.memoName == name }
                            ?.let { return@withLock PreparedEdit(it, created = false) }
                        val items = memo.attachments.map { attachment ->
                            val file = localAttachment(accountId, attachment) ?: throw IOException("附件缓存已被清理，请联网补齐后编辑")
                            DraftAttachment(filename = attachment.filename, type = attachment.type, size = file.length(), localPath = file.absolutePath, original = attachment)
                        }
                        val result = Draft(accountId = accountId, memoName = name, content = memo.content, attachments = items, baseMemo = memo)
                        // Register before the suspending write: cancellation can arrive after Room
                        // committed the row but before putDraft resumes this coroutine.
                        prepared = PreparedEdit(result, created = true)
                        dao.putDraft(draftRecord(result))
                        // Local DB now owns the references before the temporary lease is released.
                        temporaryPins.remove(lease)
                        prepared!!
                    }
                } finally {
                    withContext(NonCancellable) {
                        localLock.withLock {
                            temporaryPins.remove(lease)
                            // Failed preparation must not leave its formerly pinned files over quota.
                            trimCacheLocked()
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Also catches prompt cancellation after IO produced a draft but before its caller
            // received it. The protected copy must not become an orphaned, untouched draft.
            withContext(NonCancellable) {
                prepared?.takeIf { it.created }?.let { discardUnchangedEdit(it.draft, it.draft) }
            }
            throw cancelled
        }
    }

    /** Only a caller-owned new editing copy can be passed here; existing drafts stay untouched. */
    suspend fun discardUnchangedEdit(baseline: Draft, expected: Draft): Boolean = withContext(Dispatchers.IO) {
        initialized.await()
        if (baseline.id != expected.id || baseline.accountId != expected.accountId ||
            !expected.hasSameContentAs(baseline)) return@withContext false
        localLock.withLock {
            if (dao.account(baseline.accountId) == null) return@withLock false
            val current = dao.draft(baseline.accountId, baseline.id)?.let { ModelCodec.draft(it.payload) }
                ?: return@withLock false
            val active = synchronized(activeSends) { activeSends["${baseline.accountId}:${baseline.id}"] }
            if (active != null || dao.attempt(baseline.accountId, baseline.id) != null ||
                current.sendState != SendState.SAVED || current.revision != expected.revision ||
                !current.hasSameContentAs(baseline)) return@withLock false
            dao.deleteDraft(baseline.accountId, baseline.id)
            // Original note attachments remain ordinary cache; unused imported additions can go.
            // Do not tombstone this id: a newer in-memory edit may still need to save safely.
            removeUnreferencedImportsLocked()
            true
        }
    }

    suspend fun saveDraft(value: Draft): Draft = withContext(Dispatchers.IO) {
        initialized.await()
        localLock.withLock {
            account(value.accountId)
            val previous = dao.draft(value.accountId, value.id)?.let { ModelCodec.draft(it.payload) }
            require(previous == null || previous.memoName == value.memoName) { "草稿来源已变化，请重新打开" }
            DomainRules.validateAttachmentRemoval(previous?.baseMemo ?: value.baseMemo, value.content,
                value.attachments.mapNotNull { it.original?.name }.toSet())
            value.attachments.forEach { item ->
                val file = File(item.localPath)
                require(file.canonicalPath.startsWith(accountDirectory(value.accountId).canonicalPath + File.separator) && file.isFile && file.canRead()) { "草稿附件未完整保存，请重新添加" }
            }
            val previousItems = previous?.attachments.orEmpty().associateBy { it.id }
            val items = value.attachments.map { it.copy(uploaded = it.uploaded ?: previousItems[it.id]?.uploaded) }
            val changed = previous == null || previous.content != value.content || previous.attachments != items
            val result = value.copy(
                createUid = previous?.createUid ?: value.createUid,
                baseMemo = previous?.baseMemo ?: value.baseMemo,
                attachments = items,
                revision = if (changed) (previous?.revision ?: value.revision) + 1 else previous?.revision ?: value.revision,
                updatedAt = if (changed) now() else previous?.updatedAt ?: value.updatedAt,
                sendState = if (changed) SendState.SAVED else previous?.sendState ?: value.sendState,
                error = if (changed) null else previous?.error,
            )
            if (result.isNew && result.empty && dao.attempt(result.accountId, result.id) == null) dao.deleteDraft(result.accountId, result.id)
            else dao.putDraft(draftRecord(result))
            result
        }
    }

    suspend fun importAttachment(accountId: String, draftId: String, uri: Uri): Draft = withContext(Dispatchers.IO) {
        initialized.await()
        val previous = localLock.withLock {
            account(accountId)
            require("$accountId:$draftId" !in deletedDraftIds) { "草稿已删除" }
            dao.draft(accountId, draftId)?.let { ModelCodec.draft(it.payload) }
                ?: Draft(id = draftId, accountId = accountId)
        }
        var filename = "附件"
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) filename = cursor.getString(0) ?: filename
        }
        if (filename == "附件") filename = uri.lastPathSegment ?: filename
        val type = app.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
            ?: ""
        require(type.startsWith("image/") || type.startsWith("audio/")) { "首版只支持添加图片和音频" }
        val id = newUid()
        val file = blobFile(accountId, id)
        val partial = File(file.absolutePath + ".part")
        val lease = "import:$id"
        localLock.withLock { account(accountId); temporaryPins[lease] = setOf(file.absolutePath, partial.absolutePath) }
        try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
                } ?: throw IOException("无法读取所选附件")
                check(partial.renameTo(file)) { "无法完成附件保存" }
                val item = DraftAttachment(id, filename, type, file.length(), file.absolutePath)
                localLock.withLock {
                    account(accountId)
                    require("$accountId:$draftId" !in deletedDraftIds) { "草稿已删除" }
                    val latest = dao.draft(accountId, draftId)?.let { ModelCodec.draft(it.payload) } ?: previous
                    val result = latest.copy(attachments = latest.attachments + item, revision = latest.revision + 1, updatedAt = now(), sendState = SendState.SAVED, error = null)
                    database.withTransaction {
                        dao.putBlob(BlobRecord(id, accountId, null, file.absolutePath, file.length(), now()))
                        dao.putDraft(draftRecord(result))
                    }
                    result
                }
        } catch (error: Exception) { partial.delete(); file.delete(); throw error }
        finally {
            withContext(NonCancellable) { localLock.withLock { temporaryPins.remove(lease) } }
        }
    }

    suspend fun deleteDraft(accountId: String, id: String) = withContext(Dispatchers.IO) {
        initialized.await()
        synchronized(activeSends) { activeSends["$accountId:$id"] }?.cancel()
        localLock.withLock {
            deletedDraftIds += "$accountId:$id"
            database.withTransaction { dao.deleteDraft(accountId, id); dao.deleteAttempt(accountId, id) }
            removeUnreferencedImportsLocked()
        }
    }

    suspend fun send(accountId: String, draftId: String): SendResult = sendInternal(accountId, draftId, null)
    suspend fun resolveConflict(accountId: String, draftId: String, choice: ConflictChoice): SendResult = sendInternal(accountId, draftId, choice)

    private suspend fun sendInternal(accountId: String, draftId: String, choice: ConflictChoice?): SendResult = withContext(Dispatchers.IO) {
        initialized.await()
        val key = "$accountId:$draftId"
        val lock = synchronized(sendLocks) { sendLocks.getOrPut(key) { Mutex() } }
        lock.withLock {
            if (activeAccountId.value != accountId) return@withLock SendResult.Failed("请切换到草稿所属账号后发送")
            val job = currentCoroutineContext()[Job]
            if (job != null) synchronized(activeSends) { activeSends[key] = job }
            var snapshot: Draft? = null
            try {
                val owner = account(accountId)
                var value = draft(accountId, draftId)
                var checkedServer: Memo? = null
                require(!value.empty) { "请先添加文字、图片或音频" }
                DomainRules.validateAttachmentRemoval(value.baseMemo, value.content,
                    value.attachments.mapNotNull { it.original?.name }.toSet())
                val previousAttempt = dao.attempt(accountId, draftId)?.let { ModelCodec.draft(it.payload) }
                val copyAttempt = previousAttempt?.takeIf { !value.isNew && it.isNew }
                val unconfirmedAttempt = previousAttempt?.takeIf {
                    it.sendState in setOf(SendState.SUBMITTING, SendState.VERIFYING, SendState.UNKNOWN)
                }
                if (unconfirmedAttempt != null) {
                    val checked = readOrMissing(owner, unconfirmedAttempt.memoName ?: "memos/${unconfirmedAttempt.createUid}")
                    if (checked != null && submittedMatches(checked, unconfirmedAttempt)) {
                        return@withLock acknowledge(owner, unconfirmedAttempt, checked)
                    }
                    if (unconfirmedAttempt.isNew && checked != null) {
                        return@withLock SendResult.Unknown("相同编号的笔记已存在但内容不一致，请核对服务端；草稿已保留")
                    }
                }
                val pendingCopy = copyAttempt?.takeIf { it === unconfirmedAttempt }
                if (pendingCopy != null && choice == ConflictChoice.OVERWRITE) {
                    return@withLock SendResult.Unknown("上次另存的结果尚未确认，请先重试另存")
                }
                val retryCopy = pendingCopy?.takeIf { choice == ConflictChoice.SAVE_AS_NEW }
                val originalName = value.memoName
                if (retryCopy != null) {
                    value = retryCopy
                } else if (originalName != null) {
                    val server = readOrMissing(owner, originalName)
                    checkedServer = server
                    if (choice == ConflictChoice.KEEP_SERVER) {
                        updateDraftState(value, SendState.SAVED, null)
                        localLock.withLock {
                            account(accountId)
                            if (server != null) cacheMemo(owner, server)
                            else {
                                dao.deleteMemo(accountId, originalName)
                                forgetMemoMembership(accountId, originalName)
                            }
                        }
                        return@withLock SendResult.KeptServer(server, value.copy(sendState = SendState.SAVED, error = null))
                    }
                    val changed = server == null || value.baseMemo == null || !DomainRules.sameBase(value.baseMemo, server)
                    if ((changed || copyAttempt != null) && choice == null) {
                        updateDraftState(value, SendState.CONFLICT, "服务端内容已变化")
                        return@withLock SendResult.Conflict(server, value.copy(sendState = SendState.CONFLICT))
                    }
                    if (choice == ConflictChoice.SAVE_AS_NEW) {
                        val previousUploads = copyAttempt?.attachments.orEmpty().associateBy { it.id }
                        value = value.copy(
                            memoName = null, createUid = copyAttempt?.createUid ?: newUid(), baseMemo = null,
                            attachments = value.attachments.map { item ->
                                val previous = previousUploads[item.id]
                                item.copy(uploadUid = previous?.uploadUid ?: newUid(), uploaded = previous?.uploaded)
                            },
                        )
                    } else if (server == null) {
                        return@withLock SendResult.Failed("原笔记已被删除，请选择另存为新笔记")
                    }
                }
                snapshot = value
                if (retryCopy == null) {
                    saveAttempt(value, SendState.CHECKING)
                    value.attachments.forEach { item -> require(File(item.localPath).isFile && File(item.localPath).canRead()) { "草稿附件缺失，请重新添加后发送" } }
                    var content = value.content
                    val uploaded = mutableListOf<DraftAttachment>()
                    for (item in value.attachments) {
                        val existing = if (!value.isNew) item.original?.takeIf { original -> checkedServer?.attachments?.any { it.name == original.name } == true } else null
                        val remote = existing ?: item.uploaded ?: gateway.upload(owner, UploadFile(File(item.localPath), item.filename, item.type, item.uploadUid))
                        if (item.original != null && item.original.name != remote.name) content = DomainRules.rewriteAttachmentReference(content, item.original, remote)
                        uploaded += item.copy(uploaded = if (existing == null) remote else null)
                        value = value.copy(content = content, attachments = uploaded + value.attachments.drop(uploaded.size))
                        snapshot = value
                        saveAttempt(value, SendState.UPLOADING)
                        if (originalName == value.memoName) mergeUploaded(value)
                    }
                }
                val resources = finalAttachments(value)
                // With already-bound attachments the server may return 400 before checking a
                // duplicate memo UID. Always read our stable UID before a new-create attempt.
                if (value.isNew) {
                    val existing = readOrMissing(owner, "memos/${value.createUid}")
                    if (existing != null) {
                        if (submittedMatches(existing, value)) return@withLock acknowledge(owner, value, existing)
                        saveAttempt(value, SendState.UNKNOWN)
                        return@withLock SendResult.Unknown("相同编号的笔记已存在但内容不一致，请核对服务端；草稿已保留")
                    }
                }
                saveAttempt(value, SendState.SUBMITTING)
                val result = try {
                    if (value.isNew) gateway.create(owner, CreateMemo(value.createUid, value.content, resources))
                    else gateway.update(owner, UpdateMemo(value.memoName!!, value.content, resources))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (error is MemosException && error.statusCode in listOf(400, 401, 403, 413) && !error.uncertain) {
                        // Bound-resource validation may precede duplicate-UID validation (400).
                        // The previous create can also finish between our preflight GET and POST.
                        if (value.isNew && error.statusCode == 400) {
                            val prior = try { readOrMissing(owner, "memos/${value.createUid}") }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            if (prior != null && submittedMatches(prior, value)) return@withLock acknowledge(owner, value, prior)
                        }
                        if (retryCopy == null && originalName != null && value.isNew) saveAttempt(value, SendState.FAILED)
                        throw error
                    }
                    saveAttempt(value, SendState.UNKNOWN)
                    val checked = try { readOrMissing(owner, value.memoName ?: "memos/${value.createUid}") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (checked != null && submittedMatches(checked, value)) return@withLock acknowledge(owner, value, checked)
                    updateDraftState(value, SendState.UNKNOWN, "发送结果尚未确认，草稿已保留；再次发送前会先核对服务端")
                    return@withLock SendResult.Unknown("发送结果尚未确认，草稿已保留；再次发送前会先核对服务端")
                }
                checkOwner(result, owner)
                if (!submittedMatches(result, value)) {
                    saveAttempt(value, SendState.UNKNOWN)
                    return@withLock SendResult.Unknown("服务端返回内容与本次发送不一致，请核对；草稿已保留")
                }
                acknowledge(owner, value, result)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    snapshot?.let { value ->
                        if (dao.account(accountId) != null) {
                            val attempt = dao.attempt(accountId, draftId)?.let { ModelCodec.draft(it.payload) }
                            val state = if (attempt?.sendState in setOf(SendState.SUBMITTING, SendState.VERIFYING, SendState.UNKNOWN)) SendState.UNKNOWN else SendState.SAVED
                            if (state == SendState.UNKNOWN) saveAttempt(value, state)
                            updateDraftState(value, state, if (state == SendState.UNKNOWN) "发送中断，需核对服务端结果" else null)
                        }
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                dao.account(accountId)?.let { markAuthentication(ModelCodec.account(it.payload), error) }
                snapshot?.let { updateDraftState(it, SendState.FAILED, friendly(error)) }
                SendResult.Failed(friendly(error))
            } finally {
                synchronized(activeSends) { if (activeSends[key] == job) activeSends.remove(key) }
            }
        }
    }

    private suspend fun readOrMissing(owner: Account, name: String): Memo? = try { checkOwner(gateway.get(owner, name), owner) }
        catch (error: MemosException) { if (error.statusCode == 404) null else throw error }

    private fun finalAttachments(value: Draft): List<Attachment> = value.attachments.map {
        it.uploaded ?: (if (!value.isNew) it.original else null) ?: throw IllegalStateException("附件尚未完成上传")
    }

    private fun submittedMatches(memo: Memo, value: Draft): Boolean =
        memo.content == value.content && memo.attachments.map { it.name } == finalAttachments(value).map { it.name } &&
            (!value.isNew || memo.visibility == "PRIVATE")

    private suspend fun saveAttempt(value: Draft, state: SendState) {
        localLock.withLock {
            account(value.accountId)
            require(dao.draft(value.accountId, value.id) != null) { "草稿已删除" }
            val snapshot = value.copy(sendState = state)
            dao.putAttempt(AttemptRecord(value.id, value.accountId, value.revision, ModelCodec.draft(snapshot)))
            val current = dao.draft(value.accountId, value.id)?.let { ModelCodec.draft(it.payload) }
            if (current?.revision == value.revision) dao.putDraft(draftRecord(current.copy(sendState = state, error = null)))
        }
    }

    private suspend fun updateDraftState(value: Draft, state: SendState, error: String?) = localLock.withLock {
        val current = dao.draft(value.accountId, value.id)?.let { ModelCodec.draft(it.payload) }
        if (current?.revision == value.revision) dao.putDraft(draftRecord(current.copy(sendState = state, error = error)))
    }

    private suspend fun mergeUploaded(snapshot: Draft) = localLock.withLock {
        val current = dao.draft(snapshot.accountId, snapshot.id)?.let { ModelCodec.draft(it.payload) } ?: return@withLock
        val uploaded = snapshot.attachments.associateBy { it.id }
        dao.putDraft(draftRecord(current.copy(attachments = current.attachments.map { item -> item.copy(uploaded = uploaded[item.id]?.uploaded ?: item.uploaded) })))
    }

    private suspend fun acknowledge(owner: Account, snapshot: Draft, memo: Memo): SendResult = localLock.withLock {
        account(owner.id)
        cacheMemo(owner, memo)
        val current = dao.draft(owner.id, snapshot.id)?.let { ModelCodec.draft(it.payload) }
        val keepNewer = current != null && current.revision != snapshot.revision
        database.withTransaction {
            if (keepNewer) {
                val committedItems = snapshot.attachments.associateBy { it.id }
                var rebasedContent = current!!.content
                val mapped = current!!.attachments.map { item ->
                    val sent = committedItems[item.id]
                    val remote = sent?.let { it.uploaded ?: (if (!snapshot.isNew) it.original else null) }
                    if (sent?.original != null && remote != null && sent.original.name != remote.name) {
                        rebasedContent = DomainRules.rewriteAttachmentReference(rebasedContent, sent.original, remote)
                    }
                    item.copy(original = remote ?: item.original, uploaded = null)
                }
                dao.putDraft(draftRecord(current.copy(memoName = memo.name, baseMemo = memo, content = rebasedContent, attachments = mapped, sendState = SendState.SAVED, error = null)))
            } else dao.deleteDraft(owner.id, snapshot.id)
            dao.deleteAttempt(owner.id, snapshot.id)
            // A successfully published original can become ordinary cache. If the server
            // changed byte length (e.g. EXIF removal), download its exact version on demand.
            for (item in snapshot.attachments) {
                val remote = memo.attachments.firstOrNull { candidate ->
                    candidate.name == (item.uploaded ?: (if (!snapshot.isNew) item.original else null))?.name
                } ?: continue
                val file = File(item.localPath)
                if (file.isFile && (remote.size == 0L || remote.size == file.length())) {
                    // Retain existing remote aliases when save-as-new reuses the same local
                    // immutable bytes. Only the temporary imported-file entry is replaced.
                    dao.blobs(owner.id).filter { it.path == file.absolutePath && it.remoteName == null }.forEach { dao.deleteBlob(it.id) }
                    dao.putBlob(BlobRecord(attachmentKey(owner.id, remote), owner.id, remote.name, file.absolutePath, file.length(), now()))
                }
            }
        }
        rememberViewed(owner.id, memo.name)
        removeUnreferencedImportsLocked()
        SendResult.Sent(memo, keepNewer)
    }

    private suspend fun cacheMemo(owner: Account, memo: Memo) {
        checkOwner(memo, owner)
        dao.putMemo(MemoRecord(owner.id, memo.name, ModelCodec.memo(memo), now()))
        // Single-record reads/writes must also reach pages that have already loaded.
        // Keep all other loaded members and pagination tokens intact.
        membership.value = membership.value.mapValues { (key, names) ->
            if (key.accountId != owner.id) names
            else if (DomainRules.matches(memo, key.query)) names + memo.name
            else names - memo.name
        }
    }

    private fun forgetMemoMembership(accountId: String, name: String) {
        membership.value = membership.value.mapValues { (key, names) ->
            if (key.accountId == accountId) names - name else names
        }
    }

    suspend fun setArchived(accountId: String, name: String, archived: Boolean) = withContext(Dispatchers.IO) {
        initialized.await()
        val owner = account(accountId)
        checkOwner(gateway.get(owner, name), owner)
        val changed = checkOwner(gateway.update(owner, UpdateMemo(name, state = if (archived) "ARCHIVED" else "NORMAL")), owner)
        localLock.withLock {
            account(accountId); cacheMemo(owner, changed)
        }
    }

    suspend fun deleteMemo(accountId: String, name: String) = withContext(Dispatchers.IO) {
        initialized.await()
        val owner = account(accountId)
        checkOwner(gateway.get(owner, name), owner)
        gateway.delete(owner, name)
        localLock.withLock {
            dao.deleteMemo(accountId, name)
            forgetMemoMembership(accountId, name)
        }
    }

    private suspend fun markAuthentication(owner: Account, error: Exception) {
        if (error is MemosException && error.statusCode == 401) localLock.withLock {
            dao.account(owner.id)?.let { record ->
                val current = ModelCodec.account(record.payload)
                dao.putAccount(AccountRecord(owner.id, ModelCodec.account(current.copy(needsAuthentication = true))))
            }
        }
    }

    private fun friendly(error: Exception): String = when (error) {
        is MemosException -> error.message ?: "服务器请求失败，草稿已保留"
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "无法完成操作，草稿已保留"
        is IOException -> "网络或文件操作失败，草稿已保留，请重试"
        else -> "操作未完成，草稿已保留，请重试"
    }

    private suspend fun protectedPaths(): Set<String> =
        (dao.allDrafts().map { ModelCodec.draft(it.payload) } + dao.attempts().map { ModelCodec.draft(it.payload) })
            .flatMap { it.attachments.map { attachment -> attachment.localPath } }.toSet() + temporaryPins.values.flatten()

    suspend fun cacheReport(): CacheReport = withContext(Dispatchers.IO) {
        initialized.await()
        localLock.withLock { cacheReportLocked() }
    }

    private suspend fun cacheReportLocked(): CacheReport {
        val protected = protectedPaths()
        val files = dao.blobs().distinctBy { it.path }.filter { File(it.path).isFile }
        val ordinary = files.filter { it.path !in protected }.sumOf { File(it.path).length() } + dao.allMemos().sumOf { it.payload.toByteArray().size.toLong() }
        val protectedSize = files.filter { it.path in protected }.sumOf { File(it.path).length() } + dao.allDrafts().sumOf { it.payload.toByteArray().size.toLong() }
        return CacheReport(ordinary, protectedSize)
    }

    suspend fun clearOrdinaryCache(): CacheReport = withContext(Dispatchers.IO) {
        initialized.await()
        localLock.withLock {
            val before = cacheReportLocked().ordinaryBytes
            val protected = protectedPaths()
            for (blob in dao.blobs()) if (blob.path !in protected) {
                if (!File(blob.path).exists() || File(blob.path).delete()) dao.deleteBlob(blob.id)
            }
            dao.clearMemos()
            membership.value = emptyMap()
            synchronized(nextPages) { nextPages.clear() }
            cacheReportLocked().copy(removedBytes = (before - cacheReportLocked().ordinaryBytes).coerceAtLeast(0))
        }
    }

    private suspend fun trimCacheLocked(excluded: Set<String> = emptySet()) {
        val limit = 1024L * 1024L * 1024L
        var total = cacheReportLocked().ordinaryBytes
        if (total <= limit) return
        val protected = protectedPaths() + excluded
        val candidates = dao.blobs().filter { it.path !in protected }.map { Triple(it.lastUsed, it, null as MemoRecord?) } +
            dao.allMemos().map { Triple(it.lastUsed, null as BlobRecord?, it) }
        for ((_, blob, memo) in candidates.sortedBy { it.first }) {
            if (total <= limit) break
            if (blob != null) {
                val file = File(blob.path)
                val size = if (file.exists()) file.length() else 0
                if (!file.exists() || file.delete()) { dao.deleteBlob(blob.id); total -= size }
            } else if (memo != null) {
                dao.deleteMemo(memo.accountId, memo.name)
                total -= memo.payload.toByteArray().size
            }
        }
    }

    private suspend fun removeUnreferencedImportsLocked() {
        val protected = protectedPaths()
        for (blob in dao.blobs()) if (blob.remoteName == null && blob.path !in protected) {
            if (!File(blob.path).exists() || File(blob.path).delete()) dao.deleteBlob(blob.id)
        }
    }

    private suspend fun recoverLocalFiles() = localLock.withLock {
        val protected = protectedPaths()
        val tracked = dao.blobs().map { it.path }.toSet() + protected
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.name.endsWith(".part") || file.absolutePath !in tracked) file.delete()
        }
        for (blob in dao.blobs()) if (!File(blob.path).isFile && blob.path !in protected) dao.deleteBlob(blob.id)
        trimCacheLocked()
    }

    /** Used by an application owner or isolated instrumentation tests, never by a screen. */
    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
    }
}
