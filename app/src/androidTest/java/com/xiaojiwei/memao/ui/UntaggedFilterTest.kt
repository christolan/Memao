package com.xiaojiwei.memao.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.key
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
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real UI, page state and isolated Room/cache; never reads the user's accounts. */
@RunWith(AndroidJUnit4::class)
class UntaggedFilterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val width = mutableStateOf(420.dp)
    private val displayed = mutableStateOf<MemaoViewModel?>(null)
    private val saved = SavedStateHandle()
    private val stores = mutableListOf<ViewModelStore>()
    private var repository: MemaoRepository? = null
    private var fixtureGateway: FilterGateway? = null
    private var fixtureFiles: File? = null
    private var preferences: String? = null

    @After fun cleanFixture() {
        compose.runOnUiThread { displayed.value = null; stores.forEach { it.clear() }; stores.clear() }
        runBlocking { repository?.close() }
        fixtureFiles?.deleteRecursively()
        preferences?.let { name ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase(name + ".db")
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(name + "_tokens")
        }
    }

    @Test fun searchSubmitsFromKeyboardReusesHistoryAndKeepsUntaggedDistinctFromNamedTag() {
        val gateway = FilterGateway()
        val vm = start(gateway)
        awaitCount(vm, 5)
        openDrawer()
        compose.onNodeWithTag("filter:untagged").assertTextEquals("无标签").assertIsNotSelected()
        compose.onNodeWithTag("tag:无标签").assertTextEquals("#无标签")
        compose.onNodeWithTag("filter:untagged").performScrollTo().performClick()
        awaitCount(vm, 3)
        compose.onNodeWithTag("pageTitle").assertTextEquals("笔记 无标签")
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        saveScreenshot("untagged-compact-feed.png")
        openDrawer()
        compose.onNodeWithTag("filter:untagged").assertIsSelected()
        compose.onNodeWithTag("tag:all").assertIsNotSelected()
        compose.onNodeWithTag("tag:无标签").assertIsNotSelected()
        saveScreenshot("untagged-compact-drawer.png")
        compose.onNodeWithTag("drawerClose").performClick()
        val beforeSearch = gateway.callCount()
        openSearch()
        compose.onNodeWithText("暂无搜索记录").assertIsDisplayed()
        compose.onNodeWithTag("search").performTextReplacement("hello")
        compose.onNodeWithTag("memo:memos/plain3").assertDoesNotExist()
        assertEquals(beforeSearch, gateway.callCount())
        compose.onNodeWithTag("search").performImeAction()
        awaitSearchNames(vm, setOf("memos/plain1", "memos/plain3"))
        compose.onNodeWithTag("memo:memos/plain3").assertIsDisplayed()
        saveScreenshot("search-compact-results.png")
        assertEquals("hello", gateway.lastCall().query.keyword)
        assertTrue(gateway.lastCall().query.untagged)
        closeSearch()
        awaitCount(vm, 3)
        assertEquals(beforeSearch + 1, gateway.callCount())
        compose.onNodeWithTag("pageTitle").assertTextEquals("笔记 无标签")

        selectFilter("tag:无标签")
        awaitNames(vm, setOf("memos/named"))
        compose.onNodeWithTag("pageTitle").assertTextEquals("笔记 #无标签")
        openSearch()
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
        saveScreenshot("search-compact-history.png")
        compose.onNodeWithText("hello").assertHasClickAction().performClick()
        awaitSearchNames(vm, setOf("memos/named"))
        assertEquals("无标签", gateway.lastCall().query.tag)
        assertFalse(gateway.lastCall().query.untagged)

        compose.onNodeWithTag("search").performTextReplacement("未提交")
        compose.onNodeWithContentDescription("清除关键词").performClick()
        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithText("未提交").assertDoesNotExist()
        closeSearch()
        selectFilter("tag:无标签")
        awaitCount(vm, 5)
    }

    @Test fun searchPaginationDoesNotChangeNotesPagination() {
        val gateway = FilterGateway(pageSize = 2)
        val vm = start(gateway)
        awaitNames(vm, setOf("memos/plain3", "memos/plain2"))
        val originalList = compose.runOnIdle { vm.notes.listState }
        openSearch()
        submitKeyword("hello")
        awaitSearchNames(vm, setOf("memos/plain3", "memos/plain1"))
        compose.onNodeWithTag("searchResults").performScrollToNode(hasText("加载更多"))
        compose.onNodeWithText("加载更多").performClick()
        awaitSearchNames(vm, setOf("memos/plain3", "memos/plain1", "memos/named", "memos/daily"))
        assertEquals("2", gateway.lastCall().token)
        assertEquals("hello", gateway.lastCall().query.keyword)
        closeSearch()
        awaitNames(vm, setOf("memos/plain3", "memos/plain2"))
        compose.runOnIdle {
            assertSame(originalList, vm.notes.listState)
            assertTrue(vm.notes.state.hasMore)
        }
    }

    @Test fun paginationRefreshNavigationAndAccountRestorationKeepUntaggedIndependent() {
        val gateway = FilterGateway(pageSize = 2)
        var vm = start(gateway)
        awaitCount(vm, 2)
        selectFilter("filter:untagged")
        compose.waitUntil(10_000) { !vm.state.loading && vm.state.untagged && vm.state.memos.size == 2 }
        compose.runOnIdle { assertTrue(vm.state.hasMore); vm.loadMore() }
        awaitCount(vm, 3)
        assertEquals("2", gateway.lastCall().token)
        assertTrue(gateway.lastCall().query.untagged)
        val retainedList = compose.runOnIdle { vm.notes.listState }
        compose.runOnIdle { vm.navigate(UiPage.ARCHIVE) }
        awaitCount(vm, 1)
        compose.runOnIdle { assertFalse(vm.archive.query.untagged); assertNull(vm.archive.query.tag) }
        val calls = gateway.callCount()
        compose.runOnIdle { vm.navigate(UiPage.DRAFTS); vm.navigate(UiPage.NOTES) }
        awaitCount(vm, 3)
        compose.runOnIdle { assertSame(retainedList, vm.notes.listState); assertTrue(vm.state.untagged) }
        assertEquals(calls, gateway.callCount())
        compose.runOnIdle { vm.pullRefresh() }
        awaitCount(vm, 2)
        assertEquals("", gateway.lastCall().token)
        assertTrue(gateway.lastCall().query.untagged)
        openSearch()
        submitKeyword("hello")
        awaitSearchNames(vm, setOf("memos/plain1", "memos/plain3"))
        submitKeyword("Another")
        awaitSearchNames(vm, setOf("memos/plain2"))
        submitKeyword("HELLO")
        awaitSearchNames(vm, setOf("memos/plain1", "memos/plain3"))
        val firstAccount = compose.runOnIdle { vm.state.account!! }
        val secondAccount = runBlocking { repository!!.connect("https://second.fixture.invalid", "fixture-only-token") }
        compose.waitUntil(10_000) { vm.state.accountId == secondAccount.id && !vm.state.loading && vm.state.memos.size == 2 }
        compose.runOnIdle { assertFalse(vm.state.untagged); vm.setTag("日常") }
        awaitNames(vm, setOf("memos/daily"))
        openSearch()
        compose.onNodeWithText("暂无搜索记录").assertIsDisplayed()
        closeSearch()
        compose.runOnIdle { vm.selectAccount(firstAccount) }
        compose.waitUntil(10_000) { vm.state.accountId == firstAccount.id && vm.state.untagged && !vm.state.loading && vm.state.memos.size == 2 }
        compose.runOnIdle { assertNull(vm.state.tag) }
        vm = replaceViewModel()
        awaitNames(vm, setOf("memos/plain2", "memos/plain3"))
        compose.runOnIdle { assertTrue(vm.state.untagged); assertNull(vm.state.tag) }
        compose.onNodeWithTag("pageTitle").assertTextEquals("笔记 无标签")
        openSearch()
        compose.onNodeWithText("HELLO").assertIsDisplayed()
        compose.onNodeWithText("Another").assertIsDisplayed()
        compose.onNodeWithText("hello").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("HELLO", "Another"), vm.search.recent) }
    }

    @Test fun offlineSearchUsesCachedTagsAndRecentKeywordWhenReopenedWithoutTag() {
        val gateway = FilterGateway()
        val vm = start(gateway)
        awaitCount(vm, 5)
        gateway.offline = true
        selectFilter("filter:untagged")
        compose.waitUntil(10_000) { vm.state.offline && !vm.state.loading && vm.state.memos.size == 3 }
        openSearch()
        submitKeyword("hello")
        awaitSearchNames(vm, setOf("memos/plain1", "memos/plain3"))
        compose.onNodeWithText("当前只搜索已缓存的内容，联网后可以查找更多。").assertIsDisplayed()
        saveScreenshot("search-offline.png")
        closeSearch()
        selectFilter("tag:all")
        awaitCount(vm, 5)
        compose.runOnIdle { assertTrue(vm.state.offline); assertFalse(vm.state.untagged) }
        openSearch()
        compose.onNodeWithText("hello").performClick()
        awaitSearchNames(vm, setOf("memos/plain1", "memos/plain3", "memos/named", "memos/daily"))
    }

    @Test fun optionRemainsAvailableWithoutAnyTagsAndUsesFilteredEmptyState() {
        width.value = 1000.dp
        val gateway = FilterGateway(values = listOf(Memo("memos/plain", "一条记录", "users/1")))
        val vm = start(gateway)
        awaitCount(vm, 1)
        compose.onNodeWithTag("filter:untagged").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { vm.state.untagged && !vm.state.loading && vm.state.memos.size == 1 }
        compose.runOnIdle { vm.setTheme(ThemeMode.DARK) }
        compose.waitUntil(10_000) { vm.state.settings.theme == ThemeMode.DARK }
        openSearch()
        submitKeyword("不存在")
        awaitSearchNames(vm, emptySet())
        compose.onNodeWithText("没有找到记录").assertIsDisplayed()
        compose.onNodeWithText("写下此刻的想法").assertDoesNotExist()
        saveScreenshot("search-wide-dark-empty.png")
        closeSearch()
        compose.onNodeWithTag("filter:untagged").assertIsSelected()
        compose.onNodeWithTag("pageTitle").assertTextEquals("笔记 无标签")
    }

    private fun openSearch() {
        compose.onNodeWithTag("search").assertDoesNotExist()
        compose.onNodeWithTag("openSearch").assertIsDisplayed().performClick()
        compose.onNodeWithTag("search").assertIsDisplayed()
    }

    private fun closeSearch() {
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        compose.onNodeWithTag("search").assertDoesNotExist()
    }

    private fun submitKeyword(keyword: String) {
        val search = compose.onNodeWithTag("search")
        search.performTextReplacement(keyword)
        search.performImeAction()
    }

    private fun openDrawer() {
        if (width.value < 600.dp) compose.onNodeWithTag("drawerToggle").performClick()
    }

    private fun selectFilter(tag: String) {
        openDrawer()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun awaitCount(vm: MemaoViewModel, count: Int) = compose.waitUntil(10_000) {
        !vm.state.loading && vm.notes.state.cacheReady && vm.state.memos.size == count
    }

    private fun awaitNames(vm: MemaoViewModel, names: Set<String>) = compose.waitUntil(10_000) {
        !vm.state.loading && vm.notes.state.cacheReady && vm.state.memos.map { it.name }.toSet() == names
    }

    private fun awaitSearchNames(vm: MemaoViewModel, names: Set<String>) = compose.waitUntil(10_000) {
        val search = vm.search.state
        search.keyword.isNotEmpty() && !search.loading && search.cacheReady && search.memos.map { it.name }.toSet() == names
    }

    private fun start(gateway: FilterGateway): MemaoViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "untagged_filter_" + UUID.randomUUID().toString().replace("-", "")
        preferences = name
        val root = File(context.cacheDir, name).apply { mkdirs() }
        fixtureFiles = root
        fixtureGateway = gateway
        val repo = openRepository()
        runBlocking { repo.connect("https://fixture.invalid", "fixture-only-token") }
        val vm = replaceViewModel()
        compose.setContent {
            Box(Modifier.requiredWidth(width.value).requiredHeight(760.dp).testTag("untagged-fixture")) {
                displayed.value?.let { current -> key(current) { MemaoApp(current) { _, _ -> } } }
            }
        }
        return vm
    }

    private fun replaceViewModel(): MemaoViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (stores.isNotEmpty()) {
            // The shell owns its repository and closes it in onCleared. A recreated shell
            // must reopen the isolated on-disk store, just as a fresh app owner would.
            compose.runOnIdle { displayed.value = null }
            compose.waitForIdle()
            val previous = repository!!
            compose.runOnUiThread { stores.forEach { it.clear() }; stores.clear() }
            runBlocking { previous.close() }
            repository = null
            openRepository()
        }
        val repo = repository!!
        lateinit var vm: MemaoViewModel
        compose.runOnUiThread {
            val store = ViewModelStore().also { stores.add(it) }
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MemaoViewModel(repo, context, saved) as T
            })[MemaoViewModel::class.java]
            displayed.value = vm
        }
        return vm
    }

    private fun openRepository(): MemaoRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = preferences!!
        val database = Room.databaseBuilder(context, MemaoDatabase::class.java, name + ".db").build()
        return MemaoRepository(context, fixtureGateway!!, TokenVault(context, name + "_tokens"), database, name, fixtureFiles!!)
            .also { repository = it }
    }

    private fun saveScreenshot(name: String) {
        val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        val image = compose.onNodeWithTag("untagged-fixture").captureToImage()
        File(directory, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private data class ListCall(val query: MemoQuery, val token: String)

    private class FilterGateway(
        private val pageSize: Int = 100,
        private val values: List<Memo> = listOf(
            Memo("memos/plain3", "Hello tomorrow", "users/1", createTime = "2026-11-08T00:00:09Z"),
            Memo("memos/plain2", "Another note", "users/1", createTime = "2026-11-08T00:00:08Z"),
            Memo("memos/plain1", "Hello today", "users/1", createTime = "2026-11-08T00:00:07Z"),
            Memo("memos/named", "Hello #无标签", "users/1", createTime = "2026-11-08T00:00:06Z", tags = listOf("无标签")),
            Memo("memos/daily", "Hello #日常", "users/1", createTime = "2026-11-08T00:00:05Z", tags = listOf("日常")),
            Memo("memos/archive", "Hello 已归档", "users/1", createTime = "2026-11-08T00:00:04Z", state = "ARCHIVED"),
        ),
    ) : MemosGateway {
        private val calls = Collections.synchronizedList(mutableListOf<ListCall>())
        @Volatile var offline = false
        fun lastCall(): ListCall = synchronized(calls) { calls.last() }
        fun callCount() = calls.size
        override suspend fun inspect(serverUrl: String, token: String) = ConnectionInfo("users/1", "隔离筛选账号", version = "0.31.0")
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String): MemoPage {
            calls.add(ListCall(query, pageToken))
            if (offline) throw IOException("Fixture is offline")
            assertFalse("The UI must never combine an ordinary tag with untagged", query.untagged && query.tag != null)
            val matches = values.filter { memo ->
                memo.archived == query.archived && memo.content.contains(query.keyword, ignoreCase = true) &&
                    (query.tag == null || query.tag in memo.tags) && (!query.untagged || memo.tags.isEmpty())
            }
            val start = pageToken.toIntOrNull() ?: 0
            val page = matches.drop(start).take(pageSize)
            return MemoPage(page, if (start + page.size < matches.size) (start + page.size).toString() else "")
        }
        override suspend fun tags(account: Account) = values.flatMap { it.tags }.distinct().map { TagCount(it, 1) }
        override suspend fun get(account: Account, name: String) = error("Must not fetch a memo")
        override suspend fun update(account: Account, request: UpdateMemo) = error("Must not update")
        override suspend fun delete(account: Account, name: String) = error("Must not delete")
        override suspend fun create(account: Account, request: CreateMemo) = error("Must not publish")
        override suspend fun upload(account: Account, request: UploadFile) = error("Must not upload")
        override suspend fun download(account: Account, attachment: Attachment, destination: File) = error("Must not download")
    }
}
