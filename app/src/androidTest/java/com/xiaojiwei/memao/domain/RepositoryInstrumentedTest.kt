package com.xiaojiwei.memao.domain

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiaojiwei.memao.data.local.MemaoDatabase
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.model.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests actual Android Room, Keystore and private-file persistence with an isolated fake server. */
@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var repository: MemaoRepository
    private lateinit var server: FakeServer
    private lateinit var vault: TokenVault
    private lateinit var filesRoot: File
    private val ownedAccounts = mutableListOf<Account>()

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "repository-test-${newUid()}.db"
        filesRoot = File(context.filesDir, "files-$dbName")
        vault = TokenVault(context, "credentials-$dbName")
        server = FakeServer()
        openRepository()
    }

    private fun openRepository() {
        val db = Room.databaseBuilder(context, MemaoDatabase::class.java, dbName).build()
        repository = MemaoRepository(context, server, vault, db, "preferences-$dbName", filesRoot)
    }

    private suspend fun login(token: String = "1"): Account = repository.connect("http://127.0.0.1:${port.incrementAndGet()}", token).also { ownedAccounts += it }

    @After fun teardown() {
        runBlocking {
            for (account in ownedAccounts) runCatching { repository.removeAccount(account.id) }
            repository.close()
            context.deleteDatabase(dbName)
            filesRoot.deleteRecursively()
        }
    }

    @Test fun originalAttachmentsAndDraftSurviveReopenAndOrdinaryCacheClear() = runBlocking {
        val account = login()
        val value = repository.saveDraft(repository.newDraft(account.id).copy(content = "离线草稿"))
        val source = File(context.cacheDir, "import-${newUid()}.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val attached = repository.importAttachment(account.id, value.id, Uri.fromFile(source))
        source.delete()
        val file = File(attached.attachments.single().localPath)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), file.readBytes())
        repository.clearOrdinaryCache()
        assertTrue(file.isFile)
        assertEquals(5, repository.cacheReport().protectedBytes - com.xiaojiwei.memao.data.local.ModelCodec.draft(attached).toByteArray().size)
        repository.close()
        openRepository()
        val restored = repository.getDraft(account.id, value.id)
        assertEquals("离线草稿", restored.content)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), File(restored.attachments.single().localPath).readBytes())
        assertNotNull(vault.get(account.id))
    }

    @Test fun accountSwitchPreservesDraftAndRemovalOnlyCleansThatAccount() = runBlocking {
        val first = login("1")
        val firstDraft = repository.saveDraft(repository.newDraft(first.id).copy(content = "甲账号"))
        val second = login("2")
        val secondDraft = repository.saveDraft(repository.newDraft(second.id).copy(content = "乙账号"))
        repository.selectAccount(first.id)
        assertEquals("甲账号", repository.getDraft(first.id, firstDraft.id).content)
        repository.removeAccount(first.id)
        assertNull(vault.get(first.id))
        assertFalse(File(filesRoot, first.id).exists())
        assertEquals("乙账号", repository.getDraft(second.id, secondDraft.id).content)
        assertNotNull(vault.get(second.id))
        assertEquals(0, server.deleteCalls)
    }

    @Test fun slowAttachmentDownloadDoesNotBlockSavingTypedDraft() = runBlocking {
        val account = login()
        server.downloadEntered = CompletableDeferred()
        server.downloadRelease = CompletableDeferred()
        val item = server.attachment("slow", byteArrayOf(1))
        val download = async { repository.ensureAttachment(account.id, item) }
        server.downloadEntered!!.await()
        try {
            val saved = withTimeout(3_000) { repository.saveDraft(repository.newDraft(account.id).copy(content = "下载时继续输入")) }
            assertEquals("下载时继续输入", repository.getDraft(account.id, saved.id).content)
        } finally { server.downloadRelease!!.complete(Unit) }
        assertTrue(download.await().isFile)
    }

    @Test fun accountRemovalDuringDownloadCannotRecreateAccountOrLocalData() = runBlocking {
        val account = login()
        server.downloadEntered = CompletableDeferred()
        server.downloadRelease = CompletableDeferred()
        val item = server.attachment("late", byteArrayOf(1))
        val download = async { runCatching { repository.ensureAttachment(account.id, item) } }
        server.downloadEntered!!.await()
        repository.removeAccount(account.id)
        server.downloadRelease!!.complete(Unit)
        assertTrue(download.await().isFailure)
        assertNull(vault.get(account.id))
        assertTrue(withTimeout(3_000) { repository.accounts.first { list -> list.none { it.id == account.id } } }.none { it.id == account.id })
        assertFalse(File(filesRoot, account.id).walkTopDown().any { it.isFile })
    }

    @Test fun completeEditGroupIsPinnedBeforeTheOneGigabyteQuotaCanEvictEarlierFiles() = runBlocking {
        val account = login()
        val first = server.attachment("large-one", byteArrayOf(1)).copy(size = 550L * 1024 * 1024)
        val second = server.attachment("large-two", byteArrayOf(2)).copy(size = 550L * 1024 * 1024)
        server.sparseDownloads = true
        val memo = server.memo(account, "large-note", "正文", listOf(first, second))
        repository.openMemo(account.id, memo.name)
        assertFalse(repository.isMemoComplete(account.id, memo))
        val editing = repository.prepareEdit(account.id, memo.name)
        assertEquals(2, editing.attachments.size)
        assertTrue(editing.attachments.all { File(it.localPath).length() == 550L * 1024 * 1024 })
        assertTrue(repository.cacheReport().protectedBytes > 1024L * 1024 * 1024)
        repository.clearOrdinaryCache()
        assertTrue(editing.attachments.all { File(it.localPath).isFile })
    }

    @Test fun offlineRefreshSearchesAllPreviouslyCachedMatchingNotes() = runBlocking {
        val account = login()
        val old = server.memo(account, "old", "寻找共同关键词", created = "2026-01-01T00:00:00Z")
        val recent = server.memo(account, "recent", "共同关键词 新记录", created = "2026-01-02T00:00:00Z")
        repository.openMemo(account.id, old.name)
        val query = MemoQuery(keyword = "共同关键词", pageSize = 1)
        repository.refresh(account.id, query)
        assertEquals(listOf(recent.name), repository.observeMemos(account.id, query).first().map { it.name })
        server.offline = true
        assertTrue(repository.refresh(account.id, query).offline)
        assertEquals(setOf(old.name, recent.name), repository.observeMemos(account.id, query).first().map { it.name }.toSet())
    }

    @Test fun unknownCreateOutcomeIsVerifiedBeforeRetryAndDoesNotCreateTwice() = runBlocking {
        val account = login()
        val draft = repository.saveDraft(repository.newDraft(account.id).copy(content = "固定编号"))
        server.failAfterCreate = true
        server.failVerification = true
        assertTrue(repository.send(account.id, draft.id) is SendResult.Unknown)
        assertEquals(1, server.createCalls)
        server.failAfterCreate = false
        server.failVerification = false
        assertTrue(repository.send(account.id, draft.id) is SendResult.Sent)
        assertEquals(1, server.createCalls)
        assertTrue(repository.observeDrafts(account.id).first().isEmpty())
    }

    @Test fun detectedConflictKeepServerPreservesDraftAndSaveAsNewCopiesAttachments() = runBlocking {
        val account = login()
        val resource = server.attachment("original", byteArrayOf(1, 2, 3))
        val memo = server.memo(account, "source", "原文 ![图](/file/attachments/original/a.png)", listOf(resource), visibility = "PUBLIC")
        val draft = repository.saveDraft(repository.prepareEdit(account.id, memo.name).copy(content = "改稿 ![图](/file/attachments/original/a.png)"))
        server.memos[memo.name] = memo.copy(content = "网页修改", updateTime = memo.updateTime)
        assertTrue(repository.send(account.id, draft.id) is SendResult.Conflict)
        assertTrue(repository.resolveConflict(account.id, draft.id, ConflictChoice.KEEP_SERVER) is SendResult.KeptServer)
        assertEquals(draft.content, repository.getDraft(account.id, draft.id).content)
        assertEquals(0, server.updateCalls)
        val result = repository.resolveConflict(account.id, draft.id, ConflictChoice.SAVE_AS_NEW) as SendResult.Sent
        assertEquals("PRIVATE", result.memo.visibility)
        assertNotEquals(memo.name, result.memo.name)
        assertNotEquals(resource.name, result.memo.attachments.single().name)
        assertFalse(result.memo.content.contains("/file/attachments/original/"))
        assertEquals("网页修改", server.memos[memo.name]!!.content)
        assertEquals(1, server.uploadCalls)
    }

    @Test fun editingDuringSlowSendKeepsNewerRevisionInsteadOfClearingIt() = runBlocking {
        val account = login()
        val first = repository.saveDraft(repository.newDraft(account.id).copy(content = "第一版"))
        server.createEntered = CompletableDeferred()
        server.createRelease = CompletableDeferred()
        val sending = async { repository.send(account.id, first.id) }
        server.createEntered!!.await()
        repository.saveDraft(first.copy(content = "第二版"))
        server.createRelease!!.complete(Unit)
        val result = sending.await() as SendResult.Sent
        assertTrue(result.newerDraftKept)
        val retained = repository.getDraft(account.id, first.id)
        assertEquals("第二版", retained.content)
        assertEquals(result.memo.name, retained.memoName)
        assertEquals("第一版", retained.baseMemo!!.content)
        assertEquals(1, server.createCalls)
    }

    @Test fun removingAnAttachmentStillReferencedInSourcePreservesStoredDraftAndResource() = runBlocking {
        val account = login()
        val resource = server.attachment("referenced", byteArrayOf(1, 2, 3))
        val memo = server.memo(account, "referenced-note", "原文 ![图](/file/attachments/referenced/a.png)", listOf(resource))
        val edit = repository.prepareEdit(account.id, memo.name)
        assertTrue(runCatching { repository.saveDraft(edit.copy(attachments = emptyList())) }.isFailure)
        val kept = repository.getDraft(account.id, edit.id)
        assertEquals(memo.content, kept.content)
        assertEquals(resource.name, kept.attachments.single().original!!.name)
        assertTrue(File(kept.attachments.single().localPath).isFile)
        assertEquals(0, server.updateCalls)
        val changed = repository.saveDraft(kept.copy(content = "原文", attachments = emptyList()))
        assertTrue(changed.attachments.isEmpty())
        assertTrue(repository.send(account.id, changed.id) is SendResult.Sent)
        assertTrue(server.memos[memo.name]!!.attachments.isEmpty())
    }

    @Test fun firstImageAndFirstAudioCanBeImportedIntoAnOtherwiseEmptyNewDraftAndSent() = runBlocking {
        val account = login()
        for ((extension, expectedType) in listOf("png" to "image/", "wav" to "audio/")) {
            val empty = repository.newDraft(account.id)
            repository.saveDraft(empty)
            assertTrue(repository.observeDrafts(account.id).first().none { it.id == empty.id })
            val source = File(context.cacheDir, "only-${newUid()}.$extension").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val imported = repository.importAttachment(account.id, empty.id, Uri.fromFile(source))
            source.delete()
            assertEquals(empty.id, imported.id)
            assertEquals("", imported.content)
            assertTrue(imported.attachments.single().type.startsWith(expectedType))
            assertEquals(3, File(imported.attachments.single().localPath).length())
            val sent = repository.send(account.id, imported.id) as SendResult.Sent
            assertEquals("", sent.memo.content)
            assertEquals("PRIVATE", sent.memo.visibility)
            assertEquals(1, sent.memo.attachments.size)
        }
    }

    @Test fun overwriteRestoresAnAttachmentDeletedOnTheServerFromTheProtectedDraftBytes() = runBlocking {
        val account = login()
        val resource = server.attachment("removed", byteArrayOf(1, 2, 3))
        val memo = server.memo(account, "restore", "原文 ![图](/file/attachments/removed/a.png)", listOf(resource), visibility = "PROTECTED")
        val original = repository.prepareEdit(account.id, memo.name)
        val changed = repository.saveDraft(original.copy(content = "保留图片 ![图](/file/attachments/removed/a.png)"))
        server.memos[memo.name] = memo.copy(content = "网页删除图片", attachments = emptyList())
        assertTrue(repository.send(account.id, changed.id) is SendResult.Conflict)
        val result = repository.resolveConflict(account.id, changed.id, ConflictChoice.OVERWRITE) as SendResult.Sent
        assertEquals(memo.name, result.memo.name)
        assertEquals("PROTECTED", result.memo.visibility)
        assertNotEquals(resource.name, result.memo.attachments.single().name)
        assertFalse(result.memo.content.contains("/file/attachments/removed/"))
        assertEquals(1, server.uploadCalls)
        assertEquals(1, server.updateCalls)
    }

    @Test fun failedEditPreparationReleasesGroupPinsAndReclaimsOverQuotaOrdinaryFiles() = runBlocking {
        val account = login()
        server.sparseDownloads = true
        val other = server.attachment("other-large", byteArrayOf(1)).copy(size = 600L * 1024 * 1024)
        repository.ensureAttachment(account.id, other)
        val first = server.attachment("prepare-first", byteArrayOf(1)).copy(size = 550L * 1024 * 1024)
        val second = server.attachment("prepare-failing", byteArrayOf(2)).copy(size = 550L * 1024 * 1024)
        val memo = server.memo(account, "failed-prepare", "正文", listOf(first, second))
        server.failedDownloads += second.name
        assertTrue(runCatching { repository.prepareEdit(account.id, memo.name) }.isFailure)
        val report = repository.cacheReport()
        assertTrue(report.ordinaryBytes <= 1024L * 1024 * 1024)
        assertEquals(0, report.protectedBytes)
        assertTrue(repository.observeDrafts(account.id).first().isEmpty())
    }

    private class FakeServer : MemosGateway {
        val memos = ConcurrentHashMap<String, Memo>()
        private val files = ConcurrentHashMap<String, ByteArray>()
        var offline = false
        var sparseDownloads = false
        var failAfterCreate = false
        var failVerification = false
        var createCalls = 0
        var updateCalls = 0
        var uploadCalls = 0
        var deleteCalls = 0
        var downloadEntered: CompletableDeferred<Unit>? = null
        var downloadRelease: CompletableDeferred<Unit>? = null
        var createEntered: CompletableDeferred<Unit>? = null
        var createRelease: CompletableDeferred<Unit>? = null
        val failedDownloads = mutableSetOf<String>()

        fun attachment(uid: String, bytes: ByteArray): Attachment = Attachment("attachments/$uid", "$uid.png", "image/png", bytes.size.toLong()).also { files[it.name] = bytes }

        fun memo(account: Account, uid: String, content: String, attachments: List<Attachment> = emptyList(), created: String = "2026-10-26T00:00:00Z", visibility: String = "PRIVATE"): Memo =
            Memo("memos/$uid", content, account.userName, created, created, visibility, attachments = attachments).also { memos[it.name] = it }

        private fun online() { if (offline) throw IOException("offline") }
        override suspend fun inspect(serverUrl: String, token: String): ConnectionInfo = ConnectionInfo("users/$token", "测试$token", version = "0.31.0")
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String): MemoPage {
            online()
            val items = memos.values.filter { DomainRules.isOwnPersonal(it, account.userName) && DomainRules.matches(it, query) }.sortedByDescending { it.createTime }
            val offset = pageToken.toIntOrNull() ?: 0
            val selected = items.drop(offset).take(query.pageSize)
            return MemoPage(selected, if (offset + selected.size < items.size) (offset + selected.size).toString() else "")
        }
        override suspend fun get(account: Account, name: String): Memo {
            online()
            if (failVerification && createCalls > 0) throw IOException("verification unavailable")
            return memos[name] ?: throw MemosException("not found", 404)
        }
        override suspend fun tags(account: Account): List<TagCount> { online(); return emptyList() }
        override suspend fun create(account: Account, request: CreateMemo): Memo {
            online()
            createEntered?.complete(Unit)
            createRelease?.await()
            createCalls++
            val result = memo(account, request.uid, request.content, request.attachments)
            if (failAfterCreate) throw MemosException("response lost", uncertain = true)
            return result
        }
        override suspend fun update(account: Account, request: UpdateMemo): Memo {
            online(); updateCalls++
            val old = memos[request.name] ?: throw MemosException("not found", 404)
            return old.copy(content = request.content ?: old.content, attachments = request.attachments ?: old.attachments, state = request.state ?: old.state).also { memos[it.name] = it }
        }
        override suspend fun delete(account: Account, name: String) { online(); deleteCalls++; memos.remove(name) }
        override suspend fun upload(account: Account, request: UploadFile): Attachment {
            online(); uploadCalls++
            val value = Attachment("attachments/${request.uid}", request.filename, request.type, request.file.length())
            files[value.name] = request.file.readBytes()
            return value
        }
        override suspend fun download(account: Account, attachment: Attachment, destination: File) {
            online()
            if (attachment.name in failedDownloads) throw IOException("selected fake download failure")
            downloadEntered?.complete(Unit)
            downloadRelease?.await()
            destination.parentFile!!.mkdirs()
            if (sparseDownloads) RandomAccessFile(destination, "rw").use { it.setLength(attachment.size) }
            else destination.writeBytes(files[attachment.name] ?: error("missing fake file"))
        }
    }

    companion object { private val port = AtomicInteger(24000) }
}
