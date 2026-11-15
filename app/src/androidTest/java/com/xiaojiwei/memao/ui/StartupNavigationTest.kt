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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
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
import androidx.test.platform.app.InstrumentationRegistry
import com.xiaojiwei.memao.data.local.MemaoDatabase
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated lifecycle/navigation fixtures only; never opens the user's saved account store. */
@RunWith(AndroidJUnit4::class)
class StartupNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val width = mutableStateOf(420.dp)
    private val displayed = mutableStateOf<MemaoViewModel?>(null)
    private val stores = mutableListOf<ViewModelStore>()
    private var repository: MemaoRepository? = null
    private var fixtureFiles: File? = null
    private var preferences: String? = null
    private val responses = mutableListOf<PendingResponse>()

    @After fun cleanFixture() {
        responses.forEach { it.result.complete(it.memo) }
        compose.runOnUiThread { stores.forEach { it.clear() } }
        runBlocking { repository?.close() }
        fixtureFiles?.deleteRecursively()
        preferences?.let { name ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(name + "_tokens")
        }
    }

    @Test fun freshViewModelsIgnoreViewedPageAndEditorHistoryAtBothWidthsButKeepDrafts() {
        val gateway = StartupGateway()
        val (repo, account) = fixture(gateway)
        val draft = runBlocking {
            repo.saveDraft(repo.newDraft(account.id).copy(content = "未发送的记录仍可手动继续", cursorStart = 2, cursorEnd = 5))
        }
        repo.rememberViewed(account.id, gateway.memo.name)
        mount()

        for ((size, oldPage) in listOf(420.dp to UiPage.ARCHIVE, 1000.dp to UiPage.SETTINGS, 420.dp to null, 1000.dp to null)) {
            val history = if (oldPage == null) SavedStateHandle() else SavedStateHandle(mapOf(
                "page:${account.id}" to oldPage.name,
                "editor:${account.id}" to draft.id,
            ))
            val vm = replaceViewModel(repo, history)
            compose.runOnIdle { width.value = size }
            awaitReady(vm, account.id, draft.id)
            assertAtNotesList(vm)
            compose.onNodeWithTag("memo:${gateway.memo.name}").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(gateway.memo.name, vm.notes.listState.layoutInfo.visibleItemsInfo.first().key)
            }
            compose.onNodeWithTag("editorBody").assertDoesNotExist()
            compose.onNodeWithTag("editMemo").assertDoesNotExist()
            compose.onNodeWithText("选择一条记录").assertDoesNotExist()
            saveScreenshot(if (size == 1000.dp) "startup-expanded-notes.png" else "startup-compact-notes.png")
            assertEquals(draft.content, runBlocking { repo.getDraft(account.id, draft.id) }.content)
        }
        assertEquals("Startup must not fetch a historical detail", 0, gateway.getCalls)

        // Recovery is now an explicit action from the drafts list, including its saved selection.
        compose.onNodeWithTag("navDrafts").performScrollTo().performClick()
        compose.onNodeWithTag("draft:${draft.id}").performScrollTo().performClick()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("editorBody").assertTextEquals(draft.content)
        compose.onNodeWithTag("editorBody").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        assertEquals(0, gateway.publishes)
    }

    @Test fun returningFromBackgroundKeepsCurrentReadingPositionAndUnsentEditorSelection() {
        val gateway = StartupGateway(memo = Memo("memos/reading", (0 until 40).joinToString("\n\n") { "阅读段落 $it：保留当前阅读位置。" }, "users/1"))
        val (repo, account) = fixture(gateway)
        mount()
        val vm = replaceViewModel(repo)
        awaitReady(vm, account.id)
        compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { click(Offset(centerX, 12f)) }
        assertAtNotesList(vm)
        compose.onNodeWithTag("memo:${gateway.memo.name}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        assertTrue("The entire reading fixture must be expanded before scrolling", renderedBodyLines(gateway.memo.name) >= 40)
        saveScreenshot("memo-background-expanded-before-scroll.png")
        compose.onNodeWithTag("feedList").performTouchInput {
            swipeUp(startY = height * .65f, endY = height * .25f, durationMillis = 450)
        }
        val readingPosition = compose.runOnIdle {
            vm.notes.listState.firstVisibleItemIndex to vm.notes.listState.firstVisibleItemScrollOffset
        }
        assertTrue("Reading fixture must actually scroll", readingPosition.first > 0 || readingPosition.second > 0)
        saveScreenshot("memo-background-reading-position.png")
        backgroundAndReturn()
        compose.runOnIdle {
            assertSame(vm, displayed.value)
            assertFalse(vm.state.paneOpen)
            assertEquals(readingPosition, vm.notes.listState.firstVisibleItemIndex to vm.notes.listState.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithTag("feedList").performScrollToIndex(0)
        compose.onNodeWithTag("memo:${gateway.memo.name}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            .performTouchInput { longClick(Offset(centerX, 12f)) }
        compose.waitUntil(10_000) { vm.state.editor != null }
        compose.onNodeWithTag("editorBody").performTextReplacement("切到后台后继续编辑，仍然不发送")
        compose.onNodeWithTag("editorBody").performTextInputSelection(TextRange(2, 5))
        val draftId = compose.runOnIdle { vm.state.editor!!.id }
        backgroundAndReturn()
        compose.onNodeWithTag("editorBody").assertTextEquals("切到后台后继续编辑，仍然不发送")
        compose.onNodeWithTag("editorBody").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.runOnIdle { assertSame(vm, displayed.value); assertEquals(draftId, vm.state.editor?.id) }
        compose.waitUntil(10_000) { !vm.state.saving }
        val savedDraft = runBlocking { repo.getDraft(account.id, draftId) }
        assertEquals("切到后台后继续编辑，仍然不发送", savedDraft.content)
        assertEquals(2, savedDraft.cursorStart)
        assertEquals(5, savedDraft.cursorEnd)
        assertEquals(0, gateway.publishes)
    }

    @Test fun lateEditResponsesCannotReopenAfterBackOrNavigation() {
        val gateway = StartupGateway()
        val (repo, account) = fixture(gateway)
        mount()
        val vm = replaceViewModel(repo)
        awaitReady(vm, account.id)
        for (navigate in listOf(false, true)) {
            val response = PendingResponse(gateway.memo).also { responses.add(it) }
            gateway.pending = response
            val existingJobs = compose.runOnIdle { vm.viewModelScope.coroutineContext[Job]!!.children.toSet() }
            compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { longClick(Offset(centerX, 12f)) }
            compose.waitUntil(10_000) { response.started.isCompleted }
            // Join only the new request jobs so assertions happen after completion, not merely
            // after the fake HTTP response was released; long-lived subscriptions stay active.
            val requestJobs = compose.runOnIdle { vm.viewModelScope.coroutineContext[Job]!!.children.filter { it !in existingJobs }.toList() }
            assertTrue("The edit preparation request must still be in flight", requestJobs.isNotEmpty())
            compose.runOnIdle { if (navigate) vm.navigate(UiPage.NOTES) else vm.back() }
            assertAtNotesList(vm)
            response.result.complete(response.memo)
            runBlocking { withTimeout(10_000) { requestJobs.forEach { it.join() } } }
            compose.waitForIdle()
            assertAtNotesList(vm)
            compose.onNodeWithTag("memo:${gateway.memo.name}").assertIsDisplayed()
            compose.onNodeWithTag("editMemo").assertDoesNotExist()
            gateway.pending = null
        }
    }

    @Test fun sixLinesStayFullAndTappingDoesNotFetchOrOpenAnotherPage() {
        val content = (1..6).joinToString("\n") { "这是第 $it 行" }
        val gateway = StartupGateway(Memo("memos/six", content, "users/1"))
        val (repo, account) = fixture(gateway)
        mount()
        val vm = replaceViewModel(repo)
        awaitReady(vm, account.id)
        assertEquals(6, renderedBodyLines(gateway.memo.name))
        compose.onNodeWithTag("memoToggle:${gateway.memo.name}").assertDoesNotExist()
        compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { click(Offset(centerX, 12f)) }
        assertAtNotesList(vm)
        assertEquals(0, gateway.getCalls)
        assertEquals(6, renderedBodyLines(gateway.memo.name))
        compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { longClick(Offset(centerX, 12f)) }
        compose.waitUntil(10_000) { vm.state.editor != null }
        compose.onNodeWithTag("editorBody").assertTextEquals(content)
        assertEquals(1, gateway.getCalls)
        saveScreenshot("memo-long-press-editor.png")
        assertEquals(0, gateway.publishes)
    }

    @Test fun visualWrappingUsesSixLinesAndExpansionSurvivesPagesAndWindowChanges() {
        // A single source line is intentionally much longer than six rendered lines.
        val content = "连续自动换行内容，用于检查实际可见行数。".repeat(60)
        val gateway = StartupGateway(Memo("memos/wrapped", content, "users/1"))
        val (repo, account) = fixture(gateway)
        mount()
        val vm = replaceViewModel(repo)
        awaitReady(vm, account.id)
        assertEquals(6, renderedBodyLines(gateway.memo.name))
        compose.onNodeWithTag("memo:${gateway.memo.name}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        saveScreenshot("memo-six-lines-compact.png")
        compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { click(Offset(centerX, 12f)) }
        assertTrue(renderedBodyLines(gateway.memo.name) > 6)
        assertAtNotesList(vm)
        assertEquals(0, gateway.getCalls)
        compose.runOnIdle { width.value = 1000.dp; vm.setTheme(ThemeMode.DARK) }
        compose.waitUntil(10_000) { vm.state.settings.theme == ThemeMode.DARK }
        compose.onNodeWithTag("memo:${gateway.memo.name}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        val available = compose.onNodeWithTag("navigationShell").fetchSemanticsNode().size.width
        val feed = compose.onNodeWithTag("feedPage").fetchSemanticsNode().size.width
        assertTrue("Reading list should use the content width instead of one third", feed > available / 2)
        saveScreenshot("memo-expanded-wide-dark.png")
        compose.onNodeWithTag("navDrafts").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.page == UiPage.DRAFTS }
        compose.onNodeWithTag("navNotes").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.page == UiPage.NOTES }
        compose.onNodeWithTag("memo:${gateway.memo.name}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        compose.runOnIdle { width.value = 420.dp }
        compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { click(Offset(centerX, 12f)) }
        assertEquals(6, renderedBodyLines(gateway.memo.name))
        compose.onNodeWithTag("memo:${gateway.memo.name}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠"))
        assertEquals(0, gateway.getCalls)
    }

    @Test fun markdownBlocksShareOneSixLineBudgetAndExpandWithoutLosingContent() {
        val content = "# 第一行标题\n\n第二行正文\n第三行正文\n\n- 第四行项目\n- 第五行项目\n\n> 第六行引用\n> 第七行引用\n\n第八行正文"
        val gateway = StartupGateway(Memo("memos/markdown", content, "users/1"))
        val (repo, account) = fixture(gateway)
        mount()
        val vm = replaceViewModel(repo)
        awaitReady(vm, account.id)
        assertEquals(6, renderedBodyLines(gateway.memo.name))
        compose.onNodeWithText("第八行正文").assertDoesNotExist()
        saveScreenshot("memo-markdown-six-lines.png")
        compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { click(Offset(centerX, 12f)) }
        compose.onNodeWithText("第八行正文").assertExists()
        assertTrue(renderedBodyLines(gateway.memo.name) >= 8)
        saveScreenshot("memo-markdown-expanded.png")
        compose.onNodeWithTag("memo:${gateway.memo.name}").performTouchInput { click(Offset(centerX, 12f)) }
        assertEquals(6, renderedBodyLines(gateway.memo.name))
        compose.onNodeWithText("第八行正文").assertDoesNotExist()
        assertAtNotesList(vm)
        assertEquals(0, gateway.getCalls)
    }

    @Test fun rowMenuCopiesFullSourceAndDeletionRequiresConfirmation() {
        val content = (1..9).joinToString("\n") { "复制完整正文第 $it 行" }
        val gateway = StartupGateway(Memo("memos/actions", content, "users/1"))
        val (repo, account) = fixture(gateway)
        mount()
        val vm = replaceViewModel(repo)
        awaitReady(vm, account.id)
        compose.onNodeWithTag("memoActions:${gateway.memo.name}").performClick()
        // DropdownMenu is a separate popup window, outside the Compose fixture layer.
        compose.waitForIdle()
        val menuScreenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val evidence = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        File(evidence, "memo-actions-compact.png").outputStream().use {
            menuScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        menuScreenshot.recycle()
        compose.onNodeWithText("复制正文").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(content, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("memoActions:${gateway.memo.name}").performClick()
        compose.onNodeWithText("删除笔记").performClick()
        compose.onNodeWithText("删除这条笔记？").assertIsDisplayed()
        assertEquals(0, gateway.deletes)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("memo:${gateway.memo.name}").assertExists()
        assertEquals(0, gateway.deletes)
        compose.onNodeWithTag("memoActions:${gateway.memo.name}").performClick()
        compose.onNodeWithText("删除笔记").performClick()
        compose.onNodeWithText("删除笔记").performClick()
        compose.waitUntil(10_000) { gateway.deletes == 1 && vm.state.memos.isEmpty() }
        assertEquals(0, gateway.publishes)
    }

    private fun renderedBodyLines(name: String): Int {
        val texts = compose.onAllNodes(
            hasTestTag("memoLineText") and hasAnyAncestor(hasTestTag("memo:$name")), useUnmergedTree = true,
        )
        return texts.fetchSemanticsNodes().indices.sumOf { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            texts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.sumOf { it.lineCount }
        }
    }

    private fun backgroundAndReturn() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun assertAtNotesList(vm: MemaoViewModel) = compose.runOnIdle {
        assertEquals(UiPage.NOTES, vm.state.page)
        assertFalse(vm.state.paneOpen)
        assertNull(vm.state.preparingMemo)
        assertNull(vm.state.draftDetail)
        assertNull(vm.state.editor)
    }

    private fun awaitReady(vm: MemaoViewModel, accountId: String, draftId: String? = null) {
        compose.waitUntil(10_000) { vm.state.accountId == accountId && !vm.state.loading && vm.state.memos.isNotEmpty() && (draftId == null || vm.state.drafts.any { it.id == draftId }) }
    }

    private fun mount() {
        compose.setContent {
            Box(Modifier.requiredWidth(width.value).requiredHeight(760.dp).testTag("startup-app-fixture")) {
                displayed.value?.let { vm -> key(vm) { MemaoApp(vm) { _, _ -> } } }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        val image = compose.onNodeWithTag("startup-app-fixture").captureToImage()
        File(directory, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun replaceViewModel(repo: MemaoRepository, saved: SavedStateHandle = SavedStateHandle()): MemaoViewModel {
        lateinit var vm: MemaoViewModel
        compose.runOnUiThread {
            // onCleared closes the repository. Stop the previous scope now and defer store
            // disposal until teardown so fresh VMs can reuse this isolated persistent store.
            displayed.value?.viewModelScope?.cancel()
            val store = ViewModelStore().also { stores.add(it) }
            val context = ApplicationProvider.getApplicationContext<Context>()
            vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MemaoViewModel(repo, context, saved) as T
            })[MemaoViewModel::class.java]
            displayed.value = vm
        }
        return vm
    }

    private fun fixture(gateway: StartupGateway): Pair<MemaoRepository, Account> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "startup_" + UUID.randomUUID().toString().replace("-", "")
        preferences = name
        val root = File(context.cacheDir, name).apply { mkdirs() }
        fixtureFiles = root
        val database = Room.inMemoryDatabaseBuilder(context, MemaoDatabase::class.java).build()
        val repo = MemaoRepository(context, gateway, TokenVault(context, name + "_tokens"), database, name, root)
        repository = repo
        val account = runBlocking { repo.connect("https://fixture.invalid", "fixture-only-token") }
        return repo to account
    }

    private class PendingResponse(val memo: Memo) {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Memo>()
    }

    private class StartupGateway(val memo: Memo = Memo("memos/previous", "上次阅读的记录", "users/1")) : MemosGateway {
        @Volatile var pending: PendingResponse? = null
        @Volatile var getCalls = 0
        var publishes = 0
        @Volatile var deletes = 0
        override suspend fun inspect(serverUrl: String, token: String) = ConnectionInfo("users/1", "隔离测试账号", version = "0.31.0")
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String) = MemoPage(listOf(memo).filter { deletes == 0 && it.archived == query.archived })
        override suspend fun tags(account: Account) = emptyList<TagCount>()
        override suspend fun get(account: Account, name: String): Memo {
            getCalls++
            val response = pending ?: return memo
            // Model an already-started request completing after cancellation/navigation.
            return withContext(NonCancellable) { response.started.complete(Unit); response.result.await() }
        }
        override suspend fun create(account: Account, request: CreateMemo): Memo { publishes++; error("Must not publish") }
        override suspend fun update(account: Account, request: UpdateMemo): Memo { publishes++; error("Must not publish") }
        override suspend fun delete(account: Account, name: String) { deletes++ }
        override suspend fun upload(account: Account, request: UploadFile): Attachment = error("Must not upload")
        override suspend fun download(account: Account, attachment: Attachment, destination: File) = error("Must not download")
    }
}
