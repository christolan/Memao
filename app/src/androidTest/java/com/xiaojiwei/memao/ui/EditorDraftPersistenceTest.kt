package com.xiaojiwei.memao.ui

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaojiwei.memao.data.local.MemaoDatabase
import com.xiaojiwei.memao.data.local.MemoRecord
import com.xiaojiwei.memao.data.local.ModelCodec
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses isolated Room/file storage; every retention assertion reads persisted records. */
@RunWith(AndroidJUnit4::class)
class EditorDraftPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: MemaoDatabase
    private lateinit var repository: MemaoRepository
    private lateinit var first: Account
    private lateinit var second: Account
    private lateinit var vm: MemaoViewModel
    private lateinit var gateway: EditGateway
    private var store: ViewModelStore? = null
    private var root: File? = null
    private var preferences: String? = null
    private val sources = mutableListOf<File>()
    private val pending = mutableListOf<PendingResponse>()

    @After fun cleanFixture() {
        pending.forEach { it.result.complete(it.memo) }
        compose.runOnUiThread { store?.clear() }
        if (::repository.isInitialized) runBlocking { repository.close() }
        root?.deleteRecursively()
        sources.forEach { it.delete() }
        preferences?.let { name ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(name + "_tokens")
        }
    }

    @Test fun unchangedMemoLeavesNoDraftAfterBackPageNavigationOrAccountSwitch() {
        start()
        openMemoByLongPress()
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        assertTrue(persisted(first.id).isEmpty())

        openMemoByLongPress()
        compose.runOnIdle { vm.navigate(UiPage.ARCHIVE) }
        compose.waitUntil(10_000) { vm.state.page == UiPage.ARCHIVE && vm.state.editor == null }
        assertTrue(persisted(first.id).isEmpty())
        compose.runOnIdle { vm.navigate(UiPage.NOTES) }
        compose.waitUntil(10_000) { vm.state.page == UiPage.NOTES && vm.state.memos.isNotEmpty() }

        openMemoByLongPress()
        compose.runOnIdle { vm.selectAccount(second) }
        compose.waitUntil(10_000) { vm.state.accountId == second.id && vm.state.editor == null }
        assertTrue(persisted(first.id).isEmpty())
        assertTrue(persisted(second.id).isEmpty())

        compose.runOnIdle { vm.newDraft() }
        compose.waitUntil(10_000) { vm.state.editor?.isNew == true }
        compose.onNodeWithTag("editorBody").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithTag("sendMemo").assertTextEquals("保存").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        assertTrue("An empty new editor must still leave no draft", persisted(second.id).isEmpty())
        assertEquals(0, gateway.publishes)
    }

    @Test fun selectionPreviewAndBackgroundDoNotCreateAnUnsentChangeOnExit() {
        start(withAttachment = true)
        openMemoByLongPress()
        val originalFiles = compose.runOnIdle { vm.state.editor!!.attachments.map { File(it.localPath) } }
        compose.onNodeWithTag("editorBody").performTextInputSelection(TextRange(1, 4))
        val inTitleBar = hasAnyAncestor(hasTestTag("pageTopBar"))
        compose.onNodeWithText("编辑", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("预览") and inTitleBar, useUnmergedTree = true).performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(vm.state.preview); assertEquals(gateway.memo.content, vm.state.editor?.content) }
        compose.onNodeWithText("预览", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("编辑") and inTitleBar, useUnmergedTree = true).performClick()
        compose.onNodeWithTag("editorBody").assertTextEquals(gateway.memo.content)
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        assertTrue(persisted(first.id).isEmpty())
        originalFiles.forEach { assertArrayEquals(EditGateway.remoteBytes, it.readBytes()) }
        assertEquals(0, gateway.publishes)
    }

    @Test fun actualTextChangeAutosavesAndSurvivesExit() {
        start()
        openMemoByLongPress()
        val id = compose.runOnIdle { vm.state.editor!!.id }
        val changed = "真实修改需要保留，暂不发送"
        compose.onNodeWithTag("editorBody").performTextReplacement(changed)
        compose.waitUntil(10_000) { persisted(first.id).singleOrNull()?.content == changed }
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        val saved = persisted(first.id).single()
        assertEquals(id, saved.id)
        assertEquals(gateway.memo.name, saved.memoName)
        assertEquals(changed, saved.content)
        assertEquals(0, gateway.publishes)
    }

    @Test fun restoringOriginalTextRemovesOnlyThisSessionCopyOnExit() {
        start(withAttachment = true)
        openMemoByLongPress()
        val originals = compose.runOnIdle { vm.state.editor!!.attachments }
        compose.onNodeWithTag("editorBody").performTextReplacement("先修改并等待自动保存")
        compose.waitUntil(10_000) { persisted(first.id).singleOrNull()?.content == "先修改并等待自动保存" }
        compose.onNodeWithTag("editorBody").performTextReplacement(gateway.memo.content)
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        assertTrue(persisted(first.id).isEmpty())
        originals.forEach { assertArrayEquals(EditGateway.remoteBytes, File(it.localPath).readBytes()) }
        assertEquals(0, gateway.publishes)
    }

    @Test fun existingDraftKeepsItsIdentityAndAttachmentsEvenWhenItMatchesServer() {
        start(withAttachment = true)
        val initial = runBlocking { repository.prepareEdit(first.id, gateway.memo.name) }
        val source = importSource()
        val attached = runBlocking { repository.importAttachment(first.id, initial.id, Uri.fromFile(source)) }
        // Persist this as a pre-existing draft before opening the editor. It must never be
        // mistaken for the disposable copy created by a new editing session.
        val existing = runBlocking { repository.saveDraft(attached.copy(content = "之前未发送的修改")) }
        compose.runOnIdle { vm.editMemo(gateway.memo) }
        compose.waitUntil(10_000) { vm.state.editor?.id == existing.id }
        compose.onNodeWithTag("editorBody").performTextInputSelection(TextRange(0, 2))
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        var saved = persisted(first.id).single()
        assertEquals(existing.id, saved.id)
        assertEquals(existing.content, saved.content)
        assertEquals(existing.attachments, saved.attachments)
        assertEquals(existing.createUid, saved.createUid)
        assertEquals("Selection alone must not change the draft timestamp", existing.updatedAt, saved.updatedAt)
        assertEquals("Selection alone must not increment the content revision", existing.revision, saved.revision)
        saved.attachments.forEach { assertTrue(File(it.localPath).isFile) }
        assertArrayEquals(source.readBytes(), File(saved.attachments.last().localPath).readBytes())

        // Matching the server later still must not erase a draft owned before this session.
        compose.runOnIdle { vm.editMemo(gateway.memo) }
        compose.waitUntil(10_000) { vm.state.editor?.id == existing.id }
        compose.onNodeWithTag("editorBody").performTextReplacement(gateway.memo.content)
        compose.runOnIdle { vm.removeAttachment(existing.attachments.last().id) }
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        saved = persisted(first.id).single()
        assertEquals(existing.id, saved.id)
        assertEquals(gateway.memo.content, saved.content)
        assertEquals(initial.attachments, saved.attachments)
        assertArrayEquals(EditGateway.remoteBytes, File(saved.attachments.single().localPath).readBytes())
    }

    @Test fun pickerCancellationAndAddThenRemoveLeaveNoDraftButNewAttachmentPersists() {
        start(withAttachment = true)
        openMemoByLongPress()
        assertTrue(runBlocking(Dispatchers.Main) { vm.prepareAttachmentImport() })
        compose.runOnIdle { vm.importFiles(emptyList()) }
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        assertTrue(persisted(first.id).isEmpty())

        openMemoByLongPress()
        val original = compose.runOnIdle { vm.state.editor!!.attachments.single() }
        val added = importThroughEditor(importSource())
        compose.runOnIdle { vm.removeAttachment(added.id) }
        compose.waitUntil(10_000) { vm.state.editor?.attachments?.size == 1 && !vm.state.saving }
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        assertTrue(persisted(first.id).isEmpty())
        assertTrue(File(original.localPath).isFile)
        assertFalse("The reverted import must not remain orphaned", File(added.localPath).exists())

        openMemoByLongPress()
        val source = importSource()
        val kept = importThroughEditor(source)
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        val saved = persisted(first.id).single()
        assertEquals(gateway.memo.name, saved.memoName)
        assertEquals(gateway.memo.content, saved.content)
        assertEquals(2, saved.attachments.size)
        assertEquals(kept, saved.attachments.last())
        assertArrayEquals(source.readBytes(), File(kept.localPath).readBytes())
        assertArrayEquals(EditGateway.remoteBytes, File(saved.attachments.first().localPath).readBytes())
    }

    @Test fun cancelledPreparationCannotLeaveAnUnchangedCopyOrOpenAnEditor() {
        start()
        for (navigate in listOf(false, true)) {
            val response = PendingResponse(gateway.memo).also { pending.add(it) }
            gateway.pending = response
            val existing = compose.runOnIdle { vm.viewModelScope.coroutineContext[Job]!!.children.toSet() }
            val feed = compose.onNodeWithTag("feedList")
            val memo = compose.onNodeWithTag("memo:${gateway.memo.name}")
            val feedBounds = feed.fetchSemanticsNode().boundsInRoot
            val memoBounds = memo.fetchSemanticsNode().boundsInRoot
            memo.performTouchInput { longClick(Offset(centerX, 12f)) }
            compose.waitUntil(10_000) { response.started.isCompleted }
            val requests = compose.runOnIdle { vm.viewModelScope.coroutineContext[Job]!!.children.filter { it !in existing }.toList() }
            assertTrue(requests.isNotEmpty())
            compose.onNodeWithText("正在准备编辑…").assertDoesNotExist()
            assertEquals(feedBounds, feed.fetchSemanticsNode().boundsInRoot)
            assertEquals(memoBounds, memo.fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle {
                if (navigate) vm.navigate(UiPage.ARCHIVE) else compose.activity.onBackPressedDispatcher.onBackPressed()
            }
            response.result.complete(response.memo)
            runBlocking { withTimeout(10_000) { requests.forEach { it.join() } } }
            compose.waitForIdle()
            compose.runOnIdle { assertNull(vm.state.editor); assertNull(vm.state.preparingMemo) }
            assertTrue(persisted(first.id).isEmpty())
            gateway.pending = null
            compose.runOnIdle { vm.navigate(UiPage.NOTES) }
            compose.waitUntil(10_000) { vm.state.page == UiPage.NOTES && vm.state.memos.isNotEmpty() }
        }
    }

    @Test fun attemptedSendFailureRetainsTheDraftEvenWithoutContentChanges() {
        start()
        openMemoByLongPress()
        val id = compose.runOnIdle { vm.state.editor!!.id }
        compose.onNodeWithTag("sendMemo").performClick()
        compose.waitUntil(10_000) { !vm.state.sending && vm.state.error != null }
        compose.onNodeWithContentDescription("返回").performClick()
        awaitClosed()
        val saved = persisted(first.id).single()
        assertEquals(id, saved.id)
        assertEquals(gateway.memo.content, saved.content)
        assertTrue("The gateway must have actually received an update attempt", gateway.publishes > 0)
    }

    @Test fun existingTagsCanBeSelectedOrFilteredAndCustomTagsCanStillBeCreated() {
        start()
        compose.waitUntil(10_000) { vm.state.tags.any { it.tag == "计划" } }
        openMemoByLongPress()
        compose.onNodeWithText("标签").performClick()
        compose.onAllNodesWithText("#日常").assertCountEquals(1)
        compose.onNodeWithText("#计划").assertExists()
        compose.onNodeWithText("#工作").assertDoesNotExist()

        compose.onNodeWithTag("editorTagInput").performTextReplacement("#计")
        compose.onNodeWithText("#日常").assertDoesNotExist()
        compose.onNodeWithText("#计划").performClick()
        compose.onNodeWithText("添加标签").assertDoesNotExist()
        compose.onNodeWithTag("editorBody").assertTextEquals(gateway.memo.content + "\n#计划")

        compose.onNodeWithText("标签").performClick()
        compose.onNodeWithTag("editorTagInput").performTextReplacement("临时 标签")
        compose.onNodeWithText("已有标签").assertDoesNotExist()
        compose.onNodeWithText("添加").performScrollTo().performClick()
        compose.onNodeWithTag("editorBody").assertTextEquals(gateway.memo.content + "\n#计划\n#临时标签")
        assertEquals(0, gateway.publishes)
    }

    @Test fun offlineTagChoicesIncludeUnlistedCachedNotesOnlyFromTheCurrentAccount() {
        start()
        runBlocking {
            for ((account, tag) in listOf(first to "本机缓存标签", second to "其他账号标签")) {
                val memo = Memo("memos/cached", "#$tag", account.userName, tags = listOf(tag))
                database.dao().putMemo(MemoRecord(account.id, memo.name, ModelCodec.memo(memo), System.currentTimeMillis()))
            }
        }
        gateway.listUnavailable = true
        compose.runOnIdle { vm.setUntagged(true) }
        compose.waitUntil(10_000) { vm.state.offline && !vm.state.loading && vm.state.untagged }
        compose.onNodeWithTag("drawerToggle").performClick()
        compose.onNodeWithTag("navigationDrawer").assertIsDisplayed()
        compose.onNodeWithTag("newMemo").performClick()
        compose.onNodeWithText("标签").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("#本机缓存标签").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("#其他账号标签").assertDoesNotExist()
        compose.onNodeWithText("#本机缓存标签").performClick()
        compose.onNodeWithTag("editorBody").assertTextEquals("#本机缓存标签")
    }

    private fun persisted(accountId: String): List<Draft> = runBlocking {
        database.dao().drafts(accountId).map { ModelCodec.draft(it.payload) }
    }

    private fun openMemoByLongPress() {
        compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { longClick(Offset(centerX, 12f)) }
        compose.waitUntil(10_000) { vm.state.editor?.memoName == gateway.memo.name }
        compose.onNodeWithTag("editorBody").assertTextEquals(gateway.memo.content)
    }

    private fun awaitClosed() {
        compose.waitUntil(10_000) { vm.state.editor == null && !vm.state.saving }
        compose.waitForIdle()
    }

    private fun importThroughEditor(source: File): DraftAttachment {
        assertTrue(runBlocking(Dispatchers.Main) { vm.prepareAttachmentImport() })
        compose.runOnIdle { vm.importFiles(listOf(Uri.fromFile(source))) }
        compose.waitUntil(10_000) { !vm.state.importing && vm.state.editor?.attachments?.size == 2 }
        return compose.runOnIdle { vm.state.editor!!.attachments.last() }
    }

    private fun importSource(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "editor-import-${UUID.randomUUID()}.wav").apply {
            writeBytes(byteArrayOf(9, 8, 7, 6, 5, 4))
            sources.add(this)
        }
    }

    private fun start(withAttachment: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "editor_persistence_" + UUID.randomUUID().toString().replace("-", "")
        preferences = name
        database = Room.inMemoryDatabaseBuilder(context, MemaoDatabase::class.java).build()
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        root = directory
        gateway = EditGateway(withAttachment)
        repository = MemaoRepository(context, gateway, TokenVault(context, name + "_tokens"), database, name, directory)
        runBlocking {
            first = repository.connect("https://first.fixture.invalid", "fixture-token")
            second = repository.connect("https://second.fixture.invalid", "fixture-token")
            repository.selectAccount(first.id)
        }
        compose.runOnUiThread {
            store = ViewModelStore()
            vm = ViewModelProvider(store!!, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MemaoViewModel(repository, context, SavedStateHandle()) as T
            })[MemaoViewModel::class.java]
        }
        compose.setContent {
            Box(Modifier.requiredWidth(420.dp).requiredHeight(760.dp)) { MemaoApp(vm) { _, _ -> } }
        }
        compose.waitUntil(10_000) { vm.state.accountId == first.id && !vm.state.loading && vm.state.memos.isNotEmpty() }
    }

    private class PendingResponse(val memo: Memo) {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Memo>()
    }

    private class EditGateway(withAttachment: Boolean) : MemosGateway {
        val memo = Memo("memos/edit-persistence", "这是服务器原始笔记正文", "users/1", updateTime = "2026-11-08T00:00:00Z",
            attachments = if (withAttachment) listOf(Attachment("attachments/original", "原有音频.wav", "audio/wav", remoteBytes.size.toLong())) else emptyList())
        @Volatile var pending: PendingResponse? = null
        @Volatile var publishes = 0
        @Volatile var listUnavailable = false
        override suspend fun inspect(serverUrl: String, token: String) = ConnectionInfo("users/1", "隔离测试账号", version = "0.31.0")
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String): MemoPage {
            if (listUnavailable) throw java.io.IOException("隔离测试网关离线")
            return MemoPage(listOf(memo).filter { it.archived == query.archived })
        }
        override suspend fun tags(account: Account) = if (account.serverUrl.contains("first.fixture.invalid")) {
            listOf(TagCount("日常", 2), TagCount("计划", 1), TagCount("日常", 1))
        } else listOf(TagCount("工作", 1))
        override suspend fun get(account: Account, name: String): Memo {
            val response = pending ?: return memo
            return withContext(NonCancellable) { response.started.complete(Unit); response.result.await() }
        }
        override suspend fun create(account: Account, request: CreateMemo): Memo { publishes++; throw MemosException("隔离测试发送被拒绝", 400) }
        override suspend fun update(account: Account, request: UpdateMemo): Memo { publishes++; throw MemosException("隔离测试发送被拒绝", 400) }
        override suspend fun delete(account: Account, name: String) = error("Must not delete remote data")
        override suspend fun upload(account: Account, request: UploadFile): Attachment = error("Must not upload")
        override suspend fun download(account: Account, attachment: Attachment, destination: File) { destination.writeBytes(remoteBytes) }
        companion object { val remoteBytes = byteArrayOf(1, 2, 3, 4, 5) }
    }
}
