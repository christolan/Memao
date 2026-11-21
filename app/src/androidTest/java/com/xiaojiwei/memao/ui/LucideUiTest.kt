package com.xiaojiwei.memao.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.model.*
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import com.xiaojiwei.memao.ui.theme.MemaoTheme
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LucideUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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

    @Test fun tokenVisibilityChangesDisplayedTextAndActionWithoutChangingValue() {
        compose.setContent { MemaoTheme { LoginPage(MemaoUiState(), { _, _ -> }, null) } }
        val token = compose.onNodeWithTag("token")
        token.performTextInput("fixture-only-token")
        token.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("显示访问令牌").performScrollTo().performClick()
        // Password keyboard type keeps password semantics even when the glyphs are visible.
        val visibleLayout = mutableListOf<TextLayoutResult>()
        token.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(visibleLayout) }
        assertEquals("fixture-only-token", visibleLayout.single().layoutInput.text.text)
        token.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("fixture-only-token")))
        compose.onNodeWithContentDescription("隐藏访问令牌").performClick()
        token.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        val hiddenLayout = mutableListOf<TextLayoutResult>()
        token.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(hiddenLayout) }
        assertTrue(hiddenLayout.single().layoutInput.text.text != "fixture-only-token")
        compose.onNodeWithContentDescription("显示访问令牌").assertHasClickAction()
    }

    @Test fun taskIconsAnnounceBothStatesWithoutBecomingEditableCheckboxes() {
        compose.setContent { MemaoTheme { Markdown("- [ ] 未处理的事项\n- [x] 已处理的事项") } }
        for (label in listOf("未完成待办", "已完成待办")) {
            compose.onNodeWithContentDescription(label)
                .assertExists()
                .assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        }
        compose.onNodeWithText("未处理的事项").assertExists()
        compose.onNodeWithText("已处理的事项").assertExists()
    }

    @Test fun audioButtonChangesItsAnnouncedActionAndRetainsTouchTarget() {
        val file = File(compose.activity.cacheDir, "lucide-ui-fixture.wav")
        val item = MediaDisplay("audio-fixture", "测试音频.wav", "audio/wav", file)
        val audio = mutableStateOf(AudioState(path = file.absolutePath, duration = 30_000))
        var presses = 0
        compose.setContent {
            MemaoTheme {
                AttachmentList(listOf(item), audio.value, {}, {
                    presses++
                    audio.value = audio.value.copy(playing = !audio.value.playing)
                }, {}, { _, _ -> })
            }
        }
        compose.onNodeWithContentDescription("播放音频")
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        compose.onNodeWithContentDescription("暂停音频").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("播放音频").assertHasClickAction()
        compose.runOnIdle { assertEquals(2, presses); assertEquals(false, audio.value.playing) }
    }

    @Test fun allVectorsRenderWithThemeTintAndRespectCallerSizes() {
        val dark = mutableStateOf(false)
        compose.setContent {
            MemaoTheme(darkTheme = dark.value) {
                val c = LocalMemaoColors.current
                Column(Modifier.width(320.dp).background(c.background).padding(16.dp).testTag("lucide-gallery")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(MmIcon.NOTES, Modifier.testTag("size-default"))
                        LineIcon(MmIcon.NOTES, Modifier.size(MmIconSize.Navigation).testTag("size-navigation"))
                        LineIcon(MmIcon.NOTES, Modifier.size(MmIconSize.Empty).testTag("size-empty"))
                    }
                    Spacer(Modifier.height(16.dp))
                    MmIcon.entries.chunked(6).forEach { row ->
                        Row {
                            row.forEach { icon ->
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    LineIcon(icon, Modifier.size(MmIconSize.Empty).testTag("icon:${icon.name}"))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("size-default").assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        compose.onNodeWithTag("size-navigation").assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
        compose.onNodeWithTag("size-empty").assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp)
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark }
            for (icon in MmIcon.entries) {
                val pixels = compose.onNodeWithTag("icon:${icon.name}").captureToImage().toPixelMap()
                var visibleInk = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    val luminance = (pixel.red + pixel.green + pixel.blue) / 3
                    if (pixel.alpha > .8f && if (isDark) luminance > .7f else luminance < .3f) visibleInk++
                }
                assertTrue("${icon.name} should render visible themed ink (dark=$isDark)", visibleInk > 4)
            }
            saveScreenshot("lucide-${if (isDark) "dark" else "light"}.png", "lucide-gallery")
        }
    }

    @Test fun editorToolbarFoldsToolsWithoutMovingTheBarAndShortcutsStillFormatSelection() {
        val gateway = FixtureGateway()
        val vm = createViewModel(gateway)
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { MemaoTheme { EditorPage(vm, AudioState(), { _, _ -> }) } }
        compose.waitUntil(10_000) { vm.state.accountId != null }
        compose.runOnIdle { vm.openDraft(vm.repository.newDraft(vm.state.accountId!!), newEditor = true) }
        fun keyboardHeight() = compose.runOnIdle {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        val more = compose.onNodeWithContentDescription("更多工具")
        val collapse = compose.onNodeWithContentDescription("收起更多工具")
        val panel = compose.onNodeWithTag("editorMoreTools")
        val toolbar = compose.onNodeWithTag("editorToolbar")
        compose.waitUntil(10_000) {
            val imeHeight = keyboardHeight()
            val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
            val toolbarBottom = toolbar.fetchSemanticsNode().boundsInRoot.bottom
            imeHeight > 0 && abs(rootBottom - toolbarBottom - imeHeight) <= 1f
        }
        compose.onNodeWithText("私密笔记").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加图片").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("粗体").assertDoesNotExist()
        val initialKeyboardHeight = keyboardHeight()
        val toolbarBounds = toolbar.fetchSemanticsNode().boundsInRoot
        more.performClick()
        panel.assertIsDisplayed()
        compose.onNodeWithTag("editorBody").assertIsFocused()
        assertEquals(initialKeyboardHeight, keyboardHeight())
        assertEquals(toolbarBounds, toolbar.fetchSemanticsNode().boundsInRoot)
        saveScreenshot("editor-more-tools.png")
        collapse.performClick()
        panel.assertDoesNotExist()
        val examples = listOf(
            "标题" to "# 文字", "粗体" to "**文字**", "斜体" to "*文字*", "无序列表" to "- 文字",
            "有序列表" to "1. 文字", "引用" to "> 文字", "链接" to "[文字](https://)", "待办列表" to "- [ ] 文字",
        )
        for ((label, expected) in examples) {
            compose.runOnIdle {
                vm.editorInput!!.edit {
                    replace(0, length, "文字")
                    selection = TextRange(0, 2)
                }
            }
            if (label == "标题" || label == "无序列表") {
                compose.onNodeWithContentDescription(label)
                    .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
            } else {
                more.performClick()
                compose.onNodeWithText(label).performScrollTo().performClick()
                panel.assertDoesNotExist()
            }
            compose.onNodeWithTag("editorBody").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)),
            )
        }
        compose.onNodeWithText("预览").performClick()
        compose.onNodeWithContentDescription("标题").assertDoesNotExist()
        compose.onNodeWithContentDescription("无序列表").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加图片").assertIsDisplayed()
        more.performClick()
        compose.onNodeWithText("音频").assertIsDisplayed()
        compose.onNodeWithText("粘贴图片").assertIsDisplayed()
        compose.onNodeWithText("标签").assertIsDisplayed()
        compose.onNodeWithText("粗体").assertDoesNotExist()
        compose.onNodeWithText("编辑").performClick()
        panel.assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, gateway.publishes) }
        saveScreenshot("lucide-editor.png")
    }

    @Test fun restoreArchivedMemoUsesRestoreActionAndReturnsItToNotes() {
        val archived = Memo("memos/lucidefixture", "记录一段日常\n\n- [ ] 看看窗外\n- [x] 留住想法", "users/1",
            createTime = "2026-10-27T00:00:00Z", updateTime = "2026-10-27T00:00:00Z", state = "ARCHIVED")
        val gateway = FixtureGateway(archived)
        val vm = createViewModel(gateway)
        val width = mutableStateOf(420.dp)
        compose.setContent { Box(Modifier.requiredWidth(width.value).fillMaxHeight().testTag("app-fixture")) { MemaoApp(vm) { _, _ -> } } }
        compose.waitUntil(10_000) { vm.state.accountId != null }
        compose.runOnIdle { vm.navigate(UiPage.ARCHIVE) }
        compose.waitUntil(10_000) { vm.state.memos.any { it.name == archived.name } }
        compose.onNodeWithTag("memoActions:${archived.name}").performClick()
        compose.onNodeWithText("取消归档").assertIsEnabled()
        saveScreenshot("lucide-menu-compact-light.png", "app-fixture")
        compose.runOnIdle { width.value = 1000.dp; vm.setTheme(ThemeMode.DARK) }
        compose.waitUntil(10_000) { vm.state.settings.theme == ThemeMode.DARK }
        saveScreenshot("lucide-menu-expanded-dark.png", "app-fixture")
        compose.onNodeWithText("取消归档").performClick()
        compose.waitUntil(10_000) { vm.state.message == "已恢复到笔记列表" }
        assertEquals("NORMAL", gateway.memo?.state)
        assertEquals(listOf(UpdateMemo(archived.name, state = "NORMAL")), gateway.updates)
        compose.onNodeWithTag("navNotes").performClick()
        compose.waitUntil(10_000) { vm.state.memos.any { it.name == archived.name } }
        compose.onNodeWithTag("memo:${archived.name}").assertExists()
    }

    private fun saveScreenshot(name: String, tag: String? = null) {
        val root = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        val image = if (tag == null) compose.onRoot().captureToImage() else compose.onNodeWithTag(tag).captureToImage()
        File(root, name).outputStream().use { out -> image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private fun createViewModel(gateway: FixtureGateway): MemaoViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lucide_" + UUID.randomUUID().toString().replace("-", "")
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
        return vm
    }

    private class FixtureGateway(var memo: Memo? = null) : MemosGateway {
        var publishes = 0
        val updates = mutableListOf<UpdateMemo>()
        override suspend fun inspect(serverUrl: String, token: String) = ConnectionInfo("users/1", "测试账号", version = "0.31.0")
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String) = MemoPage(listOfNotNull(memo).filter { it.archived == query.archived })
        override suspend fun tags(account: Account) = emptyList<TagCount>()
        override suspend fun get(account: Account, name: String): Memo = requireNotNull(memo?.takeIf { it.name == name })
        override suspend fun create(account: Account, request: CreateMemo): Memo { publishes++; error("Must not publish") }
        override suspend fun update(account: Account, request: UpdateMemo): Memo {
            publishes++
            updates.add(request)
            return requireNotNull(memo).copy(state = requireNotNull(request.state)).also { memo = it }
        }
        override suspend fun delete(account: Account, name: String) = error("Must not delete")
        override suspend fun upload(account: Account, request: UploadFile): Attachment = error("Must not upload")
        override suspend fun download(account: Account, attachment: Attachment, destination: File) = error("Must not download")
    }
}
