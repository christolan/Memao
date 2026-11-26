package com.xiaojiwei.memao.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaojiwei.memao.data.local.MemaoDatabase
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.model.*
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated state/navigation fixtures; no access to the user's configured accounts. */
@RunWith(AndroidJUnit4::class)
class IndependentPagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val width = mutableStateOf(420.dp)
    private var store: ViewModelStore? = null
    private var repository: MemaoRepository? = null
    private var fixtureFiles: File? = null
    private var preferences: String? = null
    private val pending = mutableListOf<PendingList>()

    @After fun cleanFixture() {
        pending.forEach { it.release.complete(Unit) }
        compose.runOnUiThread { store?.clear() }
        runBlocking { repository?.close() }
        fixtureFiles?.deleteRecursively()
        preferences?.let { name ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(name + "_tokens")
        }
    }

    @Test fun drawerSwitchesKeepThreeListsTheirOffsetsAndPaginationWithoutRequests() {
        val gateway = PagesGateway(pageSize = 16)
        val vm = start(gateway, draftCount = 24)
        val notesVm = vm.notes
        val archiveVm = vm.archive
        val draftsVm = vm.drafts
        val notesList = vm.notes.listState
        awaitNotes(vm, 16)
        compose.runOnIdle { assertTrue(vm.state.hasMore); vm.loadMore() }
        awaitNotes(vm, 32)
        assertEquals(2, gateway.callsFor(archived = false))
        val notesNames = compose.runOnIdle { vm.state.memos.map { it.name } }
        val notesPosition = scrollTo(11)

        navigate(vm, UiPage.DRAFTS)
        compose.waitUntil(10_000) { vm.state.drafts.size == 24 }
        val draftIds = compose.runOnIdle { vm.state.drafts.map { it.id } }
        val draftsPosition = scrollTo(7)
        val draftsList = vm.drafts.listState
        navigate(vm, UiPage.ARCHIVE)
        compose.waitUntil(10_000) { !vm.state.loading && vm.state.memos.size == 16 }
        assertEquals(1, gateway.callsFor(archived = true))
        compose.runOnIdle { assertTrue(vm.state.hasMore); vm.loadMore() }
        compose.waitUntil(10_000) { !vm.state.loading && vm.state.memos.size == 32 }
        val archiveNames = compose.runOnIdle { vm.state.memos.map { it.name } }
        val archiveList = vm.archive.listState
        val archivePosition = scrollTo(14)
        val requestCount = gateway.callCount()

        repeat(2) {
            navigate(vm, UiPage.NOTES)
            compose.runOnIdle {
                assertSame(notesVm, vm.notes)
                assertSame(notesList, vm.notes.listState)
                assertEquals(notesNames, vm.state.memos.map { it.name })
                assertEquals(notesPosition, position(vm.notes.listState))
                assertFalse(vm.state.loading)
                assertFalse(vm.state.hasMore)
            }
            saveScreenshot("independent-notes-retained.png")
            navigate(vm, UiPage.DRAFTS)
            compose.runOnIdle {
                assertSame(draftsVm, vm.drafts)
                assertSame(draftsList, vm.drafts.listState)
                assertEquals(draftIds, vm.state.drafts.map { it.id })
                assertEquals(draftsPosition, position(vm.drafts.listState))
            }
            saveScreenshot("independent-drafts-retained.png")
            navigate(vm, UiPage.ARCHIVE)
            compose.runOnIdle {
                assertSame(archiveVm, vm.archive)
                assertSame(archiveList, vm.archive.listState)
                assertEquals(archiveNames, vm.state.memos.map { it.name })
                assertEquals(archivePosition, position(vm.archive.listState))
                assertFalse(vm.state.hasMore)
            }
            saveScreenshot("independent-archive-retained.png")
            assertEquals("Switching warm pages must not list again", requestCount, gateway.callCount())
        }

        compose.runOnIdle { width.value = 1000.dp }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(archivePosition, position(vm.archive.listState)) }
        navigate(vm, UiPage.NOTES)
        compose.runOnIdle { assertEquals(notesPosition, position(vm.notes.listState)); width.value = 420.dp }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(notesPosition, position(vm.notes.listState)) }
        assertEquals(requestCount, gateway.callCount())
    }

    @Test fun localDraftAndMemoMutationsUpdateWarmPagesWithoutNavigationReloads() {
        val gateway = PagesGateway(pageSize = 100)
        val vm = start(gateway)
        awaitNotes(vm, 32)
        val memo = compose.runOnIdle { vm.state.memos.first() }
        navigate(vm, UiPage.ARCHIVE)
        compose.waitUntil(10_000) { !vm.state.loading && vm.state.memos.size == 32 }
        navigate(vm, UiPage.NOTES)
        compose.runOnIdle { vm.archiveMemo(memo) }
        compose.waitUntil(10_000) { !vm.state.busy && vm.notes.state.memos.none { it.name == memo.name } && vm.archive.state.memos.any { it.name == memo.name } }
        var before = gateway.callCount()
        navigate(vm, UiPage.ARCHIVE)
        compose.runOnIdle { assertTrue(vm.state.memos.any { it.name == memo.name }) }
        assertEquals(before, gateway.callCount())
        val archived = compose.runOnIdle { vm.state.memos.first { it.name == memo.name } }
        compose.runOnIdle { vm.archiveMemo(archived) }
        compose.waitUntil(10_000) { !vm.state.busy && vm.notes.state.memos.any { it.name == memo.name } && vm.archive.state.memos.none { it.name == memo.name } }
        before = gateway.callCount()
        navigate(vm, UiPage.NOTES)
        assertEquals(before, gateway.callCount())
        compose.runOnIdle { vm.deleteMemo(vm.state.memos.first { it.name == memo.name }) }
        compose.waitUntil(10_000) { !vm.state.busy && vm.notes.state.memos.none { it.name == memo.name } }
        before = gateway.callCount()
        navigate(vm, UiPage.ARCHIVE)
        navigate(vm, UiPage.NOTES)
        compose.runOnIdle { assertTrue(vm.state.memos.none { it.name == memo.name }) }
        assertEquals(before, gateway.callCount())

        navigate(vm, UiPage.DRAFTS)
        val accountId = compose.runOnIdle { vm.state.accountId!! }
        val draft = runBlocking { vm.repository.saveDraft(vm.repository.newDraft(accountId).copy(content = "新增的本地草稿")) }
        compose.waitUntil(10_000) { vm.state.drafts.any { it.id == draft.id } }
        compose.onNodeWithTag("draft:${draft.id}").assertIsDisplayed().assertTextContains("新增的本地草稿")
        runBlocking { vm.repository.saveDraft(draft.copy(content = "本地修改会立即更新")) }
        compose.waitUntil(10_000) { vm.state.drafts.any { it.id == draft.id && it.content == "本地修改会立即更新" } }
        compose.onNodeWithTag("draft:${draft.id}").assertTextContains("本地修改会立即更新")
        compose.runOnIdle { vm.deleteDraft(vm.state.drafts.first { it.id == draft.id }) }
        compose.waitUntil(10_000) { vm.state.drafts.none { it.id == draft.id } }
        compose.onNodeWithTag("draft:${draft.id}").assertDoesNotExist()
        assertEquals("Draft changes never list server memos", before, gateway.callCount())
    }

    @Test fun pendingRefreshStaysWithOriginPageAndSearchDoesNotResetArchive() {
        val gateway = PagesGateway(pageSize = 100)
        val vm = start(gateway)
        awaitNotes(vm, 32)
        navigate(vm, UiPage.ARCHIVE)
        compose.waitUntil(10_000) { !vm.state.loading && vm.state.memos.size == 32 }
        val archiveNames = compose.runOnIdle { vm.state.memos.map { it.name } }
        val archivePosition = scrollTo(8)
        navigate(vm, UiPage.NOTES)
        val response = PendingList().also { pending.add(it); gateway.pending = it }
        val before = gateway.callCount()
        compose.runOnIdle { vm.pullRefresh() }
        compose.waitUntil(10_000) { response.started.isCompleted }
        navigate(vm, UiPage.ARCHIVE)
        compose.runOnIdle {
            assertTrue(vm.notes.state.refreshing)
            assertFalse(vm.archive.state.refreshing)
            assertFalse(vm.state.loading)
            assertEquals(archiveNames, vm.state.memos.map { it.name })
            assertEquals(archivePosition, position(vm.archive.listState))
        }
        assertEquals(before + 1, gateway.callCount())
        response.release.complete(Unit)
        compose.waitUntil(10_000) { !vm.notes.state.loading }
        compose.runOnIdle { assertEquals(archiveNames, vm.state.memos.map { it.name }); assertFalse(vm.state.refreshing) }
        navigate(vm, UiPage.NOTES)
        assertEquals(before + 1, gateway.callCount())
        compose.runOnIdle { vm.setTag("日常") }
        compose.waitUntil(10_000) { !vm.state.loading && vm.state.memos.size == 32 && vm.state.tag == "日常" }
        val filteredNames = compose.runOnIdle { vm.state.memos.map { it.name } }
        val filteredPosition = scrollTo(5)
        compose.onNodeWithTag("openSearch").performClick()
        submitKeyword("偶数")
        compose.waitUntil(10_000) {
            val search = vm.search.state
            !search.loading && search.memos.size == 16 && search.keyword == "偶数" && search.tag == "日常"
        }
        val filteredCalls = gateway.callCount()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(filteredNames, vm.notes.state.memos.map { it.name })
            assertEquals(filteredPosition, position(vm.notes.listState))
        }
        navigate(vm, UiPage.ARCHIVE)
        compose.runOnIdle {
            assertEquals(archiveNames, vm.state.memos.map { it.name })
            assertEquals(archivePosition, position(vm.archive.listState))
        }
        navigate(vm, UiPage.DRAFTS)
        navigate(vm, UiPage.NOTES)
        compose.runOnIdle {
            assertEquals(filteredNames, vm.state.memos.map { it.name })
            assertEquals(filteredPosition, position(vm.notes.listState))
            assertEquals("日常", vm.state.tag)
        }
        assertEquals(filteredCalls, gateway.callCount())
    }

    @Test fun cancellingAnInFlightSearchCannotReplaceTheNextSessionsResults() {
        val gateway = PagesGateway(pageSize = 100)
        val vm = start(gateway)
        awaitNotes(vm, 32)
        val response = PendingList().also { pending.add(it); gateway.pending = it }
        compose.onNodeWithTag("openSearch").performClick()
        submitKeyword("偶数")
        compose.waitUntil(10_000) { response.started.isCompleted }
        compose.onNodeWithText("取消").performClick()
        awaitNotes(vm, 32)

        compose.onNodeWithTag("openSearch").performClick()
        submitKeyword("奇数")
        compose.waitUntil(10_000) { !vm.search.state.loading && vm.search.state.memos.size == 16 }
        response.release.complete(Unit)
        compose.waitUntil(10_000) { response.finished.isCompleted }
        compose.runOnIdle {
            assertEquals("奇数", vm.search.state.keyword)
            assertTrue(vm.search.state.memos.all { it.content.contains("奇数") })
            assertEquals(32, vm.notes.state.memos.size)
        }
    }

    private fun submitKeyword(keyword: String) {
        val search = compose.onNodeWithTag("search")
        search.performTextReplacement(keyword)
        search.performImeAction()
    }

    @Test fun sendingFromDraftsUpdatesNotesWithoutDiscardingLoadedPages() {
        val gateway = PagesGateway(pageSize = 16)
        val vm = start(gateway)
        awaitNotes(vm, 16)
        compose.runOnIdle { vm.loadMore() }
        awaitNotes(vm, 32)
        scrollTo(10)
        val originalNames = compose.runOnIdle { vm.notes.state.memos.map { it.name }.toSet() }
        val list = vm.notes.listState
        val accountId = compose.runOnIdle { vm.state.accountId!! }
        val draft = runBlocking { vm.repository.saveDraft(vm.repository.newDraft(accountId).copy(content = "第一账号 从草稿页手动发送")) }
        navigate(vm, UiPage.DRAFTS)
        compose.waitUntil(10_000) { vm.state.drafts.any { it.id == draft.id } }
        compose.onNodeWithTag("draft:${draft.id}").performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("editorBody").assertTextEquals(draft.content)
        val before = gateway.callCount()
        compose.onNodeWithTag("sendMemo").performClick()
        val sentName = "memos/${draft.createUid}"
        compose.waitUntil(10_000) {
            !vm.state.sending && vm.state.page == UiPage.NOTES &&
                vm.notes.state.memos.any { it.name == sentName } && vm.state.drafts.none { it.id == draft.id }
        }
        compose.onNodeWithTag("memo:$sentName").assertIsDisplayed()
        compose.onNodeWithText("已保存").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(vm.notes.state.memos.map { it.name }.containsAll(originalNames))
            assertEquals(33, vm.notes.state.memos.size)
            assertFalse(vm.notes.state.hasMore)
            assertSame(list, vm.notes.listState)
            assertEquals(sentName, list.layoutInfo.visibleItemsInfo.first().key)
        }
        assertEquals("Saving locally keeps the loaded list", before, gateway.callCount())
        assertEquals(0, gateway.publishes)
        runBlocking { vm.repository.synchronize(retry = true) }
        assertEquals(1, gateway.publishes)
    }

    @Test fun publishingRevealsTheNewMemoEvenWithAnExcludingFilter() {
        val gateway = PagesGateway(pageSize = 100)
        val vm = start(gateway)
        awaitNotes(vm, 32)
        compose.runOnIdle { vm.setTag("日常") }
        awaitNotes(vm, 32)
        scrollTo(10)
        compose.onNodeWithTag("clearTagFilter").assertIsDisplayed()

        compose.onNodeWithTag("drawerToggle").performClick()
        compose.onNodeWithTag("newMemo").performClick()
        val content = "第一账号 一条未加标签的新笔记"
        compose.onNodeWithTag("editorBody").performTextReplacement(content)
        val sentName = compose.runOnIdle { "memos/${vm.state.editor!!.createUid}" }
        compose.onNodeWithTag("sendMemo").performClick()

        compose.waitUntil(10_000) {
            !vm.state.sending && vm.state.editor == null && vm.state.page == UiPage.NOTES &&
                vm.notes.state.memos.any { it.name == sentName }
        }
        compose.onNodeWithTag("clearTagFilter").assertDoesNotExist()
        compose.onNodeWithTag("memo:$sentName").assertIsDisplayed()
        compose.onNodeWithText("已保存").assertIsDisplayed()
        assertEquals(0, gateway.publishes)
        runBlocking { vm.repository.synchronize(retry = true) }
        assertEquals(1, gateway.publishes)
    }

    @Test fun changingAccountsDuringRequestCannotLeakListsDraftsOrScroll() {
        val gateway = PagesGateway(pageSize = 100)
        val vm = start(gateway, draftCount = 4)
        awaitNotes(vm, 32)
        scrollTo(9)
        val oldAccountId = compose.runOnIdle { vm.state.accountId!! }
        val response = PendingList().also { pending.add(it); gateway.pending = it }
        compose.runOnIdle { vm.pullRefresh() }
        compose.waitUntil(10_000) { response.started.isCompleted }
        val second = runBlocking { vm.repository.connect("https://second.fixture.invalid", "fixture-only-token") }
        compose.waitUntil(10_000) { vm.state.accountId == second.id && !vm.state.loading && vm.state.memos.size == 32 }
        compose.runOnIdle {
            assertNotEquals(oldAccountId, vm.state.accountId)
            assertEquals(UiPage.NOTES, vm.state.page)
            assertTrue(vm.state.memos.all { it.content.startsWith("第二账号") })
            assertTrue(vm.state.drafts.isEmpty())
            assertEquals(0 to 0, position(vm.notes.listState))
        }
        response.release.complete(Unit)
        compose.waitUntil(10_000) { response.finished.isCompleted }
        compose.waitForIdle()
        navigate(vm, UiPage.DRAFTS)
        compose.runOnIdle { assertTrue(vm.state.drafts.isEmpty()) }
        navigate(vm, UiPage.ARCHIVE)
        compose.waitUntil(10_000) { !vm.state.loading && vm.state.memos.size == 32 }
        compose.runOnIdle { assertTrue(vm.state.memos.all { it.content.startsWith("第二账号") && it.archived }) }
        navigate(vm, UiPage.NOTES)
        compose.runOnIdle { assertTrue(vm.state.memos.all { it.content.startsWith("第二账号") && !it.archived }) }
        assertEquals(1, gateway.callsFor(archived = false, second = true))
        assertEquals(1, gateway.callsFor(archived = true, second = true))
    }

    @Test fun settingsConflictCanRestoreServerVersionToWarmArchive() {
        val gateway = PagesGateway(pageSize = 100)
        val vm = start(gateway)
        awaitNotes(vm, 32)
        val original = compose.runOnIdle { vm.state.memos.first() }
        val accountId = compose.runOnIdle { vm.state.accountId!! }
        navigate(vm, UiPage.ARCHIVE)
        compose.waitUntil(10_000) { !vm.state.loading && vm.state.memos.size == 32 }
        navigate(vm, UiPage.NOTES)
        val draft = runBlocking { vm.repository.prepareEdit(accountId, original.name) }
        compose.runOnIdle { vm.openDraft(draft) }
        compose.onNodeWithTag("editorBody").performTextReplacement("尚未同步的本地修改")
        gateway.archiveRemotely(original.name, "第一账号 服务端改写的记录")
        compose.onNodeWithTag("sendMemo").performClick()
        compose.waitUntil(10_000) { !vm.state.sending && vm.state.editor == null }
        runBlocking { vm.repository.synchronize(retry = true) }
        compose.onNodeWithText("版本冲突").assertDoesNotExist()
        compose.runOnIdle { vm.navigate(UiPage.SETTINGS) }
        compose.onNodeWithTag("settingsSync").performScrollTo().performClick()
        compose.onNodeWithText("查看版本").performClick()
        compose.onNodeWithText("采用服务器版本").performClick()
        compose.onNodeWithText("继续", substring = false).assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            vm.notes.state.memos.none { it.name == original.name } &&
                vm.archive.state.memos.any { it.name == original.name && it.archived }
        }
        assertEquals("尚未同步的本地修改", runBlocking { vm.repository.getDraft(accountId, draft.id) }.content)
        assertEquals(0, gateway.publishes)
    }

    private fun navigate(vm: MemaoViewModel, page: UiPage) {
        if (width.value < 600.dp) compose.onNodeWithTag("drawerToggle").performClick()
        val tag = when (page) { UiPage.NOTES -> "navNotes"; UiPage.DRAFTS -> "navDrafts"; UiPage.ARCHIVE -> "navArchive"; else -> error("Not a list page") }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.page == page }
        compose.waitForIdle()
    }

    private fun scrollTo(index: Int): Pair<Int, Int> {
        compose.onNodeWithTag("feedList").performScrollToIndex(index)
        compose.onNodeWithTag("feedList").performTouchInput { swipeUp(startY = height * .6f, endY = height * .5f, durationMillis = 350) }
        compose.waitForIdle()
        val vm = activeVm!!
        return compose.runOnIdle {
            val list = when (vm.state.page) { UiPage.NOTES -> vm.notes.listState; UiPage.ARCHIVE -> vm.archive.listState; else -> vm.drafts.listState }
            position(list).also { assertTrue("The test must really scroll the page", it.first > 0) }
        }
    }

    private fun position(state: LazyListState) = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
    private fun awaitNotes(vm: MemaoViewModel, count: Int) = compose.waitUntil(10_000) { !vm.state.loading && vm.state.memos.size == count }
    private var activeVm: MemaoViewModel? = null

    private fun start(gateway: PagesGateway, draftCount: Int = 0): MemaoViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "independent_pages_" + UUID.randomUUID().toString().replace("-", "")
        preferences = name
        val database = Room.inMemoryDatabaseBuilder(context, MemaoDatabase::class.java).build()
        val root = File(context.cacheDir, name).apply { mkdirs() }
        fixtureFiles = root
        val repo = MemaoRepository(context, gateway, TokenVault(context, name + "_tokens"), database, name, root)
        repository = repo
        val account = runBlocking { repo.connect("https://fixture.invalid", "fixture-only-token") }
        runBlocking { repeat(draftCount) { repo.saveDraft(repo.newDraft(account.id).copy(content = "保留位置的草稿 $it\n第二行内容")) } }
        lateinit var vm: MemaoViewModel
        compose.runOnUiThread {
            store = ViewModelStore()
            vm = ViewModelProvider(store!!, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MemaoViewModel(repo, context, SavedStateHandle()) as T
            })[MemaoViewModel::class.java]
        }
        activeVm = vm
        compose.setContent {
            Box(Modifier.requiredWidth(width.value).requiredHeight(760.dp).testTag("independent-pages-fixture")) {
                MemaoApp(vm) { _, _ -> }
            }
        }
        compose.waitUntil(10_000) { vm.state.accountId == account.id && !vm.state.loading && vm.state.memos.isNotEmpty() }
        return vm
    }

    private fun saveScreenshot(name: String) {
        val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        val image = compose.onNodeWithTag("independent-pages-fixture").captureToImage()
        File(directory, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class PendingList {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
    }

    private data class ListCall(val second: Boolean, val query: MemoQuery, val token: String)

    private class PagesGateway(private val pageSize: Int) : MemosGateway {
        private val calls = Collections.synchronizedList(mutableListOf<ListCall>())
        private val values = (listOf(false, true).flatMap { second ->
            listOf(false, true).flatMap { archived -> (0 until 32).map { i ->
                Memo(
                    name = "memos/${if (second) "second" else "first"}-${if (archived) "archive" else "note"}-$i",
                    content = "${if (second) "第二账号" else "第一账号"} ${if (archived) "归档" else "笔记"} $i ${if (i % 2 == 0) "偶数" else "奇数"}\n保留每个页面的列表和位置 #日常",
                    creator = "users/1", createTime = "2026-11-08T00:00:${i.toString().padStart(2, '0')}Z",
                    state = if (archived) "ARCHIVED" else "NORMAL", tags = listOf("日常"),
                )
            } }
        }).associateBy { it.name }.toMutableMap()
        @Volatile var pending: PendingList? = null
        var publishes = 0
        fun archiveRemotely(name: String, content: String) = synchronized(values) {
            values[name] = values.getValue(name).copy(content = content, state = "ARCHIVED", updateTime = "2026-11-08T02:00:00Z")
        }
        fun callCount(): Int = calls.size
        fun callsFor(archived: Boolean, second: Boolean = false) = synchronized(calls) { calls.count { it.query.archived == archived && it.second == second } }
        override suspend fun inspect(serverUrl: String, token: String) = ConnectionInfo("users/1", "隔离分页账号", version = "0.31.0")
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String): MemoPage {
            val second = account.serverUrl.contains("second.")
            calls.add(ListCall(second, query, pageToken))
            val response = if (!query.archived && !second) synchronized(this) { pending.also { pending = null } } else null
            response?.let {
                // Model an already-started network response arriving despite cancellation.
                try { withContext(NonCancellable) { it.started.complete(Unit); it.release.await() } }
                finally { it.finished.complete(Unit) }
            }
            val matches = synchronized(values) { values.values.filter {
                it.content.startsWith(if (second) "第二账号" else "第一账号") && it.archived == query.archived &&
                    it.content.contains(query.keyword, ignoreCase = true) && (query.tag == null || query.tag in it.tags)
            }.sortedByDescending { it.createTime } }
            val start = pageToken.toIntOrNull() ?: 0
            val page = matches.drop(start).take(pageSize)
            return MemoPage(page, if (start + page.size < matches.size) (start + page.size).toString() else "")
        }
        override suspend fun tags(account: Account) = listOf(TagCount("日常", 32))
        override suspend fun get(account: Account, name: String) = synchronized(values) { values[name] ?: throw MemosException("Missing fixture memo", statusCode = 404) }
        override suspend fun update(account: Account, request: UpdateMemo): Memo = synchronized(values) {
            val updated = values.getValue(request.name).let { it.copy(state = request.state ?: it.state, content = request.content ?: it.content) }
            values[request.name] = updated
            updated
        }
        override suspend fun delete(account: Account, name: String) { synchronized(values) { values.remove(name) } }
        override suspend fun create(account: Account, request: CreateMemo): Memo = synchronized(values) {
            publishes++
            Memo("memos/${request.uid}", request.content, account.userName, createTime = "2026-11-08T01:00:00Z", attachments = request.attachments)
                .also { values[it.name] = it }
        }
        override suspend fun upload(account: Account, request: UploadFile): Attachment = error("Must not upload")
        override suspend fun download(account: Account, attachment: Attachment, destination: File) = error("Must not download")
    }
}
