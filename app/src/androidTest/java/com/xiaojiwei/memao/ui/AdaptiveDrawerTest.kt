package com.xiaojiwei.memao.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** These are constrained-window emulator checks, not physical foldable-device acceptance. */
@RunWith(AndroidJUnit4::class)
class AdaptiveDrawerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val width = mutableStateOf(420.dp)
    private val height = mutableStateOf(760.dp)
    private val fontScale = mutableStateOf(1f)
    private var repository: MemaoRepository? = null
    private var store: ViewModelStore? = null
    private var fixtureFiles: File? = null
    private var preferences: String? = null

    @After fun cleanFixture() {
        compose.runOnUiThread { store?.clear() }
        runBlocking { repository?.close() }
        fixtureFiles?.deleteRecursively()
        preferences?.let { name ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(name + "_tokens")
        }
    }

    @Test fun compactDrawerStartsHiddenAndClosesOnSelectionScrimAndBack() {
        val vm = start()
        compose.onNodeWithTag("drawerToggle").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("navNotes").assertIsNotDisplayed()
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.onNodeWithTag("newMemo").assertDoesNotExist()
        saveScreenshot("drawer-compact-closed.png")

        openDrawer()
        compose.onNodeWithTag("newMemo").assertIsDisplayed()
        compose.onNodeWithTag("navNotes").assertIsSelected()
        compose.onNodeWithTag("navDrafts").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.page == UiPage.DRAFTS }
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()

        openDrawer()
        compose.onNodeWithTag("navDrafts").assertIsSelected()
        saveScreenshot("drawer-compact-open.png", "navigationDrawer")
        tapScrim()
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(UiPage.DRAFTS, vm.state.page) }

        openDrawer()
        pressBack()
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(UiPage.DRAFTS, vm.state.page) }
        openDrawer()
        compose.onNodeWithTag("drawerClose").performClick()
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
    }

    @Test fun closingDrawerOverEditorPreservesDraftAndNavigationSavesIt() {
        val gateway = DrawerGateway()
        val vm = start(gateway)
        openDrawer()
        compose.onNodeWithTag("newMemo").performClick()
        compose.waitUntil(10_000) { vm.state.editor != null }
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.onNodeWithTag("editorBody").performTextInput("抽屉切换仍保留的内容")
        compose.onNodeWithTag("editorBody").performTextInputSelection(TextRange(2, 5))
        val draftId = compose.runOnIdle { vm.state.editor!!.id }

        openDrawer()
        pressBack()
        compose.onNodeWithTag("editorBody").assertTextEquals("抽屉切换仍保留的内容")
        compose.onNodeWithTag("editorBody").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)),
        )
        compose.runOnIdle { assertEquals(draftId, vm.state.editor!!.id) }
        openDrawer()
        compose.onNodeWithTag("navDrafts").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.page == UiPage.DRAFTS && vm.state.editor == null && !vm.state.saving }
        val saved = runBlocking { vm.repository.getDraft(vm.state.accountId!!, draftId) }
        assertEquals("抽屉切换仍保留的内容", saved.content)
        assertEquals(0, gateway.publishes)
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
    }

    @Test fun windowBreakpointChangesDrawerWithoutReopeningModalOnReturn() {
        width.value = 599.dp
        val vm = start()
        openDrawer()
        compose.runOnIdle { width.value = 600.dp }
        compose.onNodeWithTag("navigationDrawer").assertExists()
        compose.onNodeWithTag("drawerToggle").assertDoesNotExist()
        compose.onNodeWithTag("drawerClose").assertDoesNotExist()
        compose.onNodeWithTag("navArchive").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.page == UiPage.ARCHIVE }
        compose.onNodeWithTag("navArchive").assertIsSelected()
        compose.onNodeWithTag("navigationDrawer").assertExists()

        compose.runOnIdle { width.value = 599.dp }
        compose.onNodeWithTag("drawerToggle").assertExists()
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(UiPage.ARCHIVE, vm.state.page) }
        openDrawer()
        compose.onNodeWithTag("navArchive").assertIsSelected()
    }

    @Test fun permanentDrawerRemainsUsableInDarkThemeWithLargeFontAndShortWindow() {
        width.value = 1000.dp
        height.value = 430.dp
        fontScale.value = 1.8f
        val longTag = "散步途中看到的风景与随手记录的灵感"
        val vm = start(DrawerGateway(listOf(Memo("memos/longtag", "短句 #$longTag", "users/1", tags = listOf(longTag)))))
        compose.runOnIdle { vm.setTheme(ThemeMode.DARK) }
        compose.waitUntil(10_000) { vm.state.settings.theme == ThemeMode.DARK }
        compose.onNodeWithTag("drawerToggle").assertDoesNotExist()
        compose.onNodeWithTag("navSettings").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { vm.state.page == UiPage.SETTINGS }
        compose.onNodeWithTag("navSettings").assertIsSelected()
        compose.onNodeWithTag("drawerTagFilters").assertDoesNotExist()
        saveScreenshot("drawer-expanded-dark-large-font.png")
        compose.onNodeWithTag("navNotes").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { vm.state.page == UiPage.NOTES }
        compose.onNodeWithTag("tag:$longTag").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { vm.state.tag == longTag }
        compose.onNodeWithTag("navigationDrawer").assertIsDisplayed()
        compose.onNodeWithTag("tag:$longTag").assertIsSelected()
        val tagLayout = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("#$longTag") and hasAnyAncestor(hasTestTag("tag:$longTag")) and hasAnyAncestor(hasTestTag("navigationDrawer")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(tagLayout) }
        assertTrue("Long labels must wrap across lines", tagLayout.single().lineCount > 1)
        assertTrue("Long labels must remain fully readable at 1.8× font scale", !tagLayout.single().hasVisualOverflow)
        saveScreenshot("drawer-tags-expanded-dark-large-font.png")
        compose.onNodeWithTag("newMemo").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { vm.state.editor != null }
        compose.onNodeWithTag("navigationDrawer").assertExists()
        compose.onNodeWithTag("editorBody").assertExists()
        compose.onNodeWithTag("drawerTagFilters").assertDoesNotExist()
    }

    @Test fun resizingRetainsSearchFilterAndFeedPosition() {
        val matching = (0 until 30).map { index ->
            Memo("memos/drawer$index", "记录 $index\n#日常", "users/1", createTime = "2026-11-06T00:00:00Z", tags = listOf("日常"))
        }
        val otherTag = Memo("memos/travel", "记录旅途 #旅行", "users/1", createTime = "2026-11-06T00:00:00Z", tags = listOf("旅行"))
        val otherKeyword = Memo("memos/walk", "散步 #日常", "users/1", createTime = "2026-11-06T00:00:00Z", tags = listOf("日常"))
        val vm = start(DrawerGateway(memos = matching + otherTag + otherKeyword))
        compose.onNodeWithTag("search").performTextInput("记录")
        compose.waitUntil(10_000) { vm.state.keyword == "记录" && vm.state.memos.size == 31 }
        compose.onNodeWithTag("tag:日常").assertIsNotDisplayed()
        compose.onNodeWithTag("activeTagFilter").assertDoesNotExist()
        openDrawer()
        compose.onNodeWithTag("drawerTagFilters").assert(hasAnyAncestor(hasTestTag("navigationDrawer")))
        compose.onNodeWithTag("tag:all").assertIsSelected()
        compose.onNodeWithTag("tag:日常").assert(hasAnyAncestor(hasTestTag("drawerTagFilters"))).performScrollTo()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("tag:日常").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { assertEquals("Filtering waits until the modal drawer finishes closing", null, vm.state.tag) }
            compose.onNodeWithTag("navigationDrawer").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(10_000) { vm.state.tag == "日常" && vm.state.memos.map { it.name }.toSet() == matching.map { it.name }.toSet() }
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.onNodeWithTag("tag:日常").assertIsNotDisplayed()
        compose.onNodeWithTag("activeTagFilter").assertTextEquals("标签：#日常").assertHasNoClickAction()
        saveScreenshot("drawer-tags-compact-selected.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("memo:memos/drawer18"))
        val key = "feed:${UiPage.NOTES}:记录:日常"
        val initialIndex = compose.runOnIdle { vm.scroll(key).first }
        assertTrue("Fixture must be scrolled past its header", initialIndex > 1)
        openDrawer()
        compose.onNodeWithTag("tag:日常").performScrollTo().assertIsSelected()
        saveScreenshot("drawer-tags-compact-open.png")
        pressBack()
        compose.runOnIdle { width.value = 1000.dp }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("记录", vm.state.keyword)
            assertEquals("日常", vm.state.tag)
            assertEquals(initialIndex, vm.scroll(key).first)
            width.value = 420.dp
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(initialIndex, vm.scroll(key).first) }
        compose.runOnIdle { width.value = 1000.dp }
        compose.onNodeWithTag("tag:日常").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("tag:all").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.tag == null && vm.state.memos.map { it.name }.toSet() == (matching + otherTag).map { it.name }.toSet() }
        compose.runOnIdle { assertEquals("记录", vm.state.keyword) }
        compose.onNodeWithTag("navigationDrawer").assertIsDisplayed()
        compose.onNodeWithTag("tag:all").assertIsSelected()
        compose.onNodeWithTag("activeTagFilter").assertDoesNotExist()
        compose.onNodeWithTag("search").assertTextEquals("记录")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("memo:${otherTag.name}"))
        compose.onNodeWithTag("memo:${otherTag.name}").assertIsDisplayed()
    }

    @Test fun loginNeverShowsNavigationAtEitherWidth() {
        val vm = start()
        compose.runOnIdle { vm.showLogin() }
        compose.waitUntil(10_000) { vm.state.page == UiPage.LOGIN }
        compose.onNodeWithTag("drawerToggle").assertDoesNotExist()
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.runOnIdle { width.value = 1000.dp }
        compose.onNodeWithTag("drawerToggle").assertDoesNotExist()
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.onNodeWithTag("server").assertExists()
    }

    @Test fun officialDrawerSlidesThroughIntermediateFramesInBothDirectionsAndCanReverse() {
        val vm = start()
        val drawer = compose.onNodeWithTag("navigationDrawer")
        val initialNodeId = drawer.fetchSemanticsNode().id
        val closedX = drawerX()
        compose.mainClock.autoAdvance = false
        try {
            saveScreenshot("drawer-motion-01-closed.png")
            compose.onNodeWithTag("drawerToggle").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            val openingX = drawerX()
            assertEquals("Sheet must stay composed while opening", initialNodeId, drawer.fetchSemanticsNode().id)
            saveScreenshot("drawer-motion-02-opening.png")
            compose.mainClock.advanceTimeBy(1500)
            val openX = drawerX()
            assertTrue("Opening must visibly travel through an intermediate position: $closedX < $openingX < $openX", openingX > closedX + 1f && openingX < openX - 1f)
            drawer.assertIsDisplayed()
            saveScreenshot("drawer-motion-03-open.png")

            compose.onNodeWithTag("drawerClose").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            val closingX = drawerX()
            assertTrue("Closing must slide before becoming hidden: $closedX < $closingX < $openX", closingX > closedX + 1f && closingX < openX - 1f)
            assertEquals("Sheet must not be disposed at the start of closing", initialNodeId, drawer.fetchSemanticsNode().id)
            saveScreenshot("drawer-motion-04-closing.png")
            compose.mainClock.advanceTimeBy(1500)
            assertEquals(closedX, drawerX(), 1f)
            drawer.assertIsNotDisplayed()
            saveScreenshot("drawer-motion-05-closed.png")

            // Reverse a still-running opening via its close action. Closing must start
            // from that position rather than flashing an entirely open or absent sheet.
            compose.onNodeWithTag("drawerToggle").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            val interruptedX = drawerX()
            compose.onNodeWithTag("drawerClose").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            val reversedX = drawerX()
            assertTrue("Reversing must move toward the closed anchor", reversedX < interruptedX && reversedX > closedX)
            compose.mainClock.advanceTimeBy(1500)
            assertEquals(closedX, drawerX(), 1f)
            assertEquals(initialNodeId, drawer.fetchSemanticsNode().id)
            drawer.assertIsNotDisplayed()
            saveScreenshot("drawer-motion-06-reversed-closed.png")

            compose.onNodeWithTag("drawerToggle").performClick()
            compose.mainClock.advanceTimeBy(1500)
            compose.onNodeWithTag("navDrafts").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            assertTrue("Destination selection must still animate the outgoing sheet", drawerX() > closedX + 1f)
            compose.runOnIdle { assertEquals("Destination stays unchanged until close completes", UiPage.NOTES, vm.state.page) }
            saveScreenshot("drawer-motion-07-navigation-closing.png")
            compose.mainClock.advanceTimeBy(1500)
            compose.waitUntil(10_000) { vm.state.page == UiPage.DRAFTS }
            drawer.assertIsNotDisplayed()

            compose.onNodeWithTag("drawerToggle").performClick()
            compose.mainClock.advanceTimeBy(1500)
            compose.onNodeWithTag("newMemo").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            assertTrue("New memo selection must finish closing before opening the editor", drawerX() > closedX + 1f)
            compose.runOnIdle { assertEquals(null, vm.state.editor) }
            saveScreenshot("drawer-motion-08-new-memo-closing.png")
            compose.mainClock.advanceTimeBy(1500)
            compose.waitUntil(10_000) { vm.state.editor != null }
            drawer.assertIsNotDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    @Test fun officialDrawerSupportsSwipeCloseAndReopenWithoutChangingDestination() {
        val vm = start()
        openDrawer()
        compose.onNodeWithTag("navigationDrawer").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        val surface = compose.onNodeWithTag("drawer-app-fixture")
        val size = surface.fetchSemanticsNode().size
        surface.performTouchInput {
            swipe(Offset(16f, size.height * .5f), Offset(size.width * .8f, size.height * .5f), 400)
        }
        compose.onNodeWithTag("navigationDrawer").assertIsDisplayed()
        compose.onNodeWithTag("navNotes").assertIsSelected()
        compose.runOnIdle { assertEquals(UiPage.NOTES, vm.state.page) }
        pressBack()
        compose.onNodeWithTag("navigationDrawer").assertIsNotDisplayed()
        compose.runOnIdle { assertEquals(UiPage.NOTES, vm.state.page) }
    }

    private fun drawerX(): Float = compose.onNodeWithTag("navigationDrawer").fetchSemanticsNode().positionInRoot.x

    private fun tapScrim() {
        val surface = compose.onNodeWithTag("drawer-app-fixture")
        val size = surface.fetchSemanticsNode().size
        surface.performTouchInput { click(Offset(size.width - 8f, size.height * .5f)) }
    }

    private fun openDrawer() {
        compose.onNodeWithTag("drawerToggle").performClick()
        compose.onNodeWithTag("navigationDrawer").assertIsDisplayed()
    }

    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("System Back must target the fixture window", compose.activity.hasWindowFocus()) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // System/predictive-back delivery crosses the Android input dispatcher and can
        // arrive after Compose first reports idle. Wait for its observable result,
        // without sending another key or invoking the app's handler directly.
        compose.waitUntil(10_000) { !compose.onNodeWithTag("navigationDrawer").isDisplayed() }
        compose.waitForIdle()
    }

    private fun start(gateway: DrawerGateway = DrawerGateway()): MemaoViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "drawer_" + UUID.randomUUID().toString().replace("-", "")
        preferences = name
        val database = Room.inMemoryDatabaseBuilder(context, MemaoDatabase::class.java).build()
        val root = File(context.cacheDir, name).apply { mkdirs() }
        fixtureFiles = root
        val repo = MemaoRepository(context, gateway, TokenVault(context, name + "_tokens"), database, name, root)
        repository = repo
        runBlocking { repo.connect("https://fixture.invalid", "fixture-only-token") }
        lateinit var vm: MemaoViewModel
        compose.runOnUiThread {
            store = ViewModelStore()
            vm = ViewModelProvider(store!!, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MemaoViewModel(repo, context, SavedStateHandle()) as T
            })[MemaoViewModel::class.java]
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                Box(Modifier.requiredWidth(width.value).requiredHeight(height.value).testTag("drawer-app-fixture")) {
                    MemaoApp(vm) { _, _ -> }
                }
            }
        }
        compose.waitUntil(10_000) { vm.state.accountId != null && !vm.state.loading }
        return vm
    }

    private fun saveScreenshot(name: String, tag: String = "drawer-app-fixture") {
        val root = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        val image = compose.onNodeWithTag(tag).captureToImage()
        File(root, name).outputStream().use { out -> image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private class DrawerGateway(val memos: List<Memo> = emptyList()) : MemosGateway {
        var publishes = 0
        override suspend fun inspect(serverUrl: String, token: String) = ConnectionInfo("users/1", "测试账号", version = "0.31.0")
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String) = MemoPage(memos.filter {
            it.archived == query.archived && (query.keyword.isEmpty() || it.content.contains(query.keyword)) && (query.tag == null || query.tag in it.tags)
        })
        override suspend fun tags(account: Account) = memos.flatMap { it.tags }.groupingBy { it }.eachCount().map { (tag, count) -> TagCount(tag, count) }
        override suspend fun get(account: Account, name: String): Memo = memos.first { it.name == name }
        override suspend fun create(account: Account, request: CreateMemo): Memo { publishes++; error("Must not publish") }
        override suspend fun update(account: Account, request: UpdateMemo): Memo { publishes++; error("Must not publish") }
        override suspend fun delete(account: Account, name: String) = error("Must not delete")
        override suspend fun upload(account: Account, request: UploadFile): Attachment = error("Must not upload")
        override suspend fun download(account: Account, attachment: Attachment, destination: File) = error("Must not download")
    }
}
