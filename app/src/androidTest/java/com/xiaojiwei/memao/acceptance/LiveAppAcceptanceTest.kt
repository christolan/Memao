package com.xiaojiwei.memao.acceptance

import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiaojiwei.memao.MainActivity
import com.xiaojiwei.memao.data.remote.MemosProtocol
import com.xiaojiwei.memao.data.remote.OkHttpMemosGateway
import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.MemosException
import com.xiaojiwei.memao.model.newUid
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Optional real-app continuous acceptance flow. Requires a fresh local app profile,
 * adb reverse, and loopback-only credentials pushed outside app/repository files.
 * Run with -e memaoLive true. The PAT is never embedded or printed by this test.
 */
@RunWith(AndroidJUnit4::class)
class LiveAppAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun serverConnectedWritingDraftsArchiveAndSettings() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Optional loopback APP acceptance is disabled", arguments.getString("memaoLive") == "true")
        val keepQaAccount = arguments.getString("memaoKeepQaAccount") == "true"
        val credentials = readCredentials()
        val gateway = OkHttpMemosGateway({ credentials.token })
        val identity = runBlocking { gateway.inspect(credentials.serverUrl, credentials.token) }
        val account = Account("app-acceptance", credentials.serverUrl, identity.userName, identity.displayName)
        val marker = "uiqa-${newUid().take(12)}"
        val tag = "qa-${newUid().take(8)}"
        val otherTag = "qa-other-${newUid().take(8)}"
        val firstContent = "$marker 第一条记录\n#$tag"
        val secondContent = "$marker 第二条记录\n#$otherTag"
        val editedContent = "$marker 第一条记录，修改已从草稿手动发送\n#$tag"
        val protectedDraft = "$marker 这份未发送草稿在清缓存后仍完整保留"
        val createdNames = linkedSetOf<String>()
        var completed = false
        var stage = "连接服务器"
        try {
            // The password field can contain a PAT in its internal semantics. If
            // login fails, do not propagate Compose's node-tree error or its cause.
            var loginStep = "等待登录表单"
            try {
                waitTag("server")
                loginStep = "填写服务器地址"
                compose.onNodeWithTag("server", useUnmergedTree = true).performTextReplacement(credentials.serverUrl)
                loginStep = "填写访问令牌"
                compose.onNodeWithTag("token", useUnmergedTree = true).performTextReplacement(credentials.token)
                loginStep = "点击连接"
                compose.onNodeWithTag("connect", useUnmergedTree = true).performScrollTo().performClick()
                loginStep = "等待局域网权限与账号首页"
                compose.waitUntil(30_000) {
                    allowPermissionDialogIfPresent()
                    (exists("drawerToggle") || exists("navNotes"))
                }
                loginStep = "确认凭据表单已移除"
                compose.onNodeWithTag("token", useUnmergedTree = true).assertDoesNotExist()
            } catch (error: Throwable) {
                val detail = error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
                    .replace(credentials.token, "[令牌]").take(180)
                throw AssertionError("连接子步骤：$loginStep；${error.javaClass.simpleName}：$detail（凭据已隐藏）")
            }

            stage = "通过APP创建两条私密笔记"
            createThroughApp(firstContent)
            val first = awaitServerMemo(gateway, account, marker, firstContent)
            createdNames.add(first.name)
            assertEquals("PRIVATE", first.visibility)
            go("navNotes")
            createThroughApp(secondContent)
            val second = awaitServerMemo(gateway, account, marker, secondContent)
            createdNames.add(second.name)
            assertEquals("PRIVATE", second.visibility)
            go("navNotes")
            waitTag("memo:${second.name}")
            captureEvidence("home-light", hideKeyboard = true)

            stage = "关键词与单标签组合筛选"
            go("navNotes")
            openSearch()
            searchFor(marker)
            waitTag("memo:${first.name}")
            waitTag("memo:${second.name}")
            closeSearch()
            go("tag:$tag")
            openSearch()
            waitText("最近搜索")
            compose.onNodeWithText(marker).performClick()
            waitTag("memo:${first.name}")
            waitAbsent("memo:${second.name}")
            searchFor("$marker 不匹配")
            waitAbsent("memo:${first.name}")
            waitText("没有找到记录")
            searchFor(marker)
            waitTag("memo:${first.name}")
            waitAbsent("memo:${second.name}")
            closeSearch()

            stage = "编辑退出后自动保存修改草稿"
            compose.onNodeWithTag("memo:${first.name}", useUnmergedTree = true).performTouchInput { longClick() }
            waitTag("editorBody")
            compose.onNodeWithTag("editorBody", useUnmergedTree = true).performTextReplacement(editedContent)
            val selection = TextRange(2, 5)
            compose.onNodeWithTag("editorBody", useUnmergedTree = true).performTextInputSelection(selection)
            compose.activityRule.scenario.recreate()
            waitTag("editorBody")
            compose.onNodeWithTag("editorBody", useUnmergedTree = true).assertTextEquals(editedContent)
            compose.waitUntil(10_000) {
                compose.onNodeWithTag("editorBody", useUnmergedTree = true).fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.TextSelectionRange) == selection
            }
            assertEquals(selection, compose.onNodeWithTag("editorBody", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            captureEvidence("editor", hideKeyboard = false)
            compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
            waitAbsent("editorBody")
            // Leaving the editor must save locally and must not silently publish.
            assertEquals(firstContent, runBlocking { gateway.get(account, first.name) }.content)
            go("navDrafts")
            val modificationDraftTag = onlyDraftTag()
            compose.onNodeWithTag(modificationDraftTag, useUnmergedTree = true).performClick()
            waitText("继续编辑")
            compose.onNodeWithText("继续编辑").performClick()
            waitTag("editorBody")
            compose.onNodeWithTag("editorBody", useUnmergedTree = true).assertTextEquals(editedContent)

            stage = "从草稿手动发送修改"
            compose.onNodeWithTag("sendMemo", useUnmergedTree = true).performClick()
            waitAbsent("editorBody")
            compose.waitUntil(30_000) { runBlocking { gateway.get(account, first.name).content == editedContent } }
            go("navDrafts")
            compose.waitUntil(10_000) { draftTags().isEmpty() }

            stage = "归档与恢复"
            go("navNotes")
            waitTag("memo:${first.name}")
            compose.onNodeWithTag("memoActions:${first.name}", useUnmergedTree = true).performClick()
            compose.onNodeWithText("归档笔记").performClick()
            compose.waitUntil(30_000) { runBlocking { gateway.get(account, first.name).archived } }
            go("navArchive")
            waitTag("memo:${first.name}")
            compose.onNodeWithTag("memoActions:${first.name}", useUnmergedTree = true).performClick()
            compose.onNodeWithText("取消归档").performClick()
            compose.waitUntil(30_000) { runBlocking { !gateway.get(account, first.name).archived } }
            go("navNotes")
            waitTag("memo:${first.name}")

            stage = "创建待保护的本地草稿"
            openNewMemo()
            waitTag("editorBody")
            compose.onNodeWithTag("editorBody", useUnmergedTree = true).performTextReplacement(protectedDraft)
            compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
            waitAbsent("editorBody")
            assertTrue(runBlocking { gateway.list(account, MemoQuery(keyword = protectedDraft)).memos.isEmpty() })

            stage = "主题切换持久化与清缓存保护草稿"
            go("navSettings")
            waitTag("themeDark")
            compose.onNodeWithTag("themeDark", useUnmergedTree = true).performScrollTo().performClick()
            compose.activityRule.scenario.recreate()
            waitTag("themeDark")
            compose.onNodeWithTag("themeDark", useUnmergedTree = true).assertIsSelected()
            captureEvidence("settings-dark", hideKeyboard = true)
            compose.onNodeWithTag("clearCache", useUnmergedTree = true).performScrollTo().performClick()
            waitText("已清理普通缓存，未发送草稿及附件完整保留。")
            go("navDrafts")
            val protectedDraftTag = onlyDraftTag()
            compose.onNodeWithTag(protectedDraftTag, useUnmergedTree = true).performClick()
            waitText("继续编辑")
            compose.onNodeWithText("继续编辑").performClick()
            waitTag("editorBody")
            compose.onNodeWithTag("editorBody", useUnmergedTree = true).assertTextEquals(protectedDraft)
            compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
            waitAbsent("editorBody")
            assertTrue(runBlocking { gateway.list(account, MemoQuery(keyword = protectedDraft)).memos.isEmpty() })

            if (keepQaAccount) {
                stage = "保留本机验收账号与样本供手动复核"
                go("navNotes")
                go("tag:all")
                waitTag("memo:${first.name}")
                captureEvidence("home-dark", hideKeyboard = true)
                val directory = evidenceDirectory()
                File(directory, "fixture-cleanup.json").writeText(JSONObject()
                    .put("serverUrl", account.serverUrl).put("userName", account.userName)
                    .put("memoNames", JSONArray(createdNames.toList()))
                    .put("localDraftMarker", marker).put("keptForManualQa", true).toString())
            } else {
                stage = "清理本测试的本地账号与草稿"
                go("navSettings")
                waitText("管理账号")
                compose.onNodeWithText("管理账号").performScrollTo().performClick()
                waitText("删除")
                compose.onNodeWithText("删除").performClick()
                waitText("删除账号")
                compose.onNodeWithText("删除账号").performClick()
                waitTag("server")
            }
            completed = true
        } catch (error: Throwable) {
            // Never include a cause/node dump if a future UI change restores the
            // login view while its token field still contains a credential.
            val detail = error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
                .replace(credentials.token, "[令牌]").take(300)
            throw AssertionError("真实APP验收失败，阶段：$stage；${error.javaClass.simpleName}：$detail（凭据细节已隐藏）")
        } finally {
            // Only delete the unique notes made by this flow, including a create
            // whose response/UI failed before the note name was captured.
            if (!keepQaAccount || !completed) runBlocking {
                runCatching { gateway.list(account, MemoQuery(keyword = marker)).memos }
                    .getOrDefault(emptyList()).filter { it.content.startsWith(marker) }
                    .forEach { createdNames.add(it.name) }
                createdNames.forEach { name ->
                    try { gateway.delete(account, name) }
                    catch (error: MemosException) { if (error.statusCode != 404) throw AssertionError("本机验收临时笔记清理失败") }
                }
            }
        }
    }

    private fun createThroughApp(content: String) {
        openNewMemo()
        waitTag("editorBody")
        compose.onNodeWithTag("editorBody", useUnmergedTree = true).performTextReplacement(content)
        compose.onNodeWithTag("sendMemo", useUnmergedTree = true).performClick()
        waitAbsent("editorBody")
    }

    private fun awaitServerMemo(gateway: OkHttpMemosGateway, account: Account, marker: String, content: String): Memo {
        var found: Memo? = null
        compose.waitUntil(30_000) {
            found = runBlocking { gateway.list(account, MemoQuery(keyword = marker)).memos.firstOrNull { it.content == content } }
            found != null
        }
        return requireNotNull(found)
    }

    private fun openSearch() {
        waitTag("openSearch")
        compose.onNodeWithTag("openSearch", useUnmergedTree = true).performClick()
        waitTag("search")
    }

    private fun searchFor(keyword: String) {
        val search = compose.onNodeWithTag("search", useUnmergedTree = true)
        search.performTextReplacement(keyword)
        search.performImeAction()
    }

    private fun closeSearch() {
        compose.onNodeWithText("取消").performClick()
        waitAbsent("search")
    }

    private fun openNewMemo() {
        if (exists("drawerToggle") && !compose.onNodeWithTag("navigationDrawer", useUnmergedTree = true).isDisplayed()) {
            compose.onNodeWithTag("drawerToggle", useUnmergedTree = true).performClick()
        }
        waitTag("newMemo")
        compose.onNodeWithTag("newMemo", useUnmergedTree = true).performClick()
    }

    private fun go(tag: String) {
        compose.waitUntil(30_000) { exists(tag) || exists("drawerToggle") }
        val compact = exists("drawerToggle") || exists("drawerClose")
        if (exists("drawerToggle") && !compose.onNodeWithTag("navigationDrawer", useUnmergedTree = true).isDisplayed()) {
            compose.onNodeWithTag("drawerToggle", useUnmergedTree = true).performClick()
        }
        waitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
        if (compact) compose.waitUntil(30_000) { !compose.onNodeWithTag("navigationDrawer", useUnmergedTree = true).isDisplayed() }
    }
    private fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String) { compose.waitUntil(30_000) { exists(tag) } }
    private fun waitAbsent(tag: String) { compose.waitUntil(30_000) { !exists(tag) } }
    private fun waitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun draftTags(): List<String> = compose.onAllNodes(SemanticsMatcher("draft row") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("draft:") == true
    }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
    private fun onlyDraftTag(): String {
        compose.waitUntil(30_000) { draftTags().size == 1 }
        return draftTags().single()
    }

    private data class Credentials(val serverUrl: String, val token: String)

    private fun evidenceDirectory(): File {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa")
        check(directory.exists() || directory.mkdirs()) { "无法创建本机验收截图目录" }
        return directory
    }

    private fun captureEvidence(name: String, hideKeyboard: Boolean) {
        // The login page is never captured, even on a failing test run.
        check(!exists("token") && !exists("server")) { "不允许捕获凭据输入页面" }
        if (hideKeyboard) {
            compose.runOnIdle { compose.activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
            compose.waitUntil(10_000) {
                compose.runOnIdle { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
            }
        }
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(250, 5_000)
        val bitmap = automation.takeScreenshot() ?: throw AssertionError("无法获取本机验收截图")
        try {
            File(evidenceDirectory(), "$name.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "无法保存本机验收截图" }
            }
        } finally { bitmap.recycle() }
    }

    private fun readCredentials(): Credentials = try {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val descriptor = automation.executeShellCommand("cat /data/local/tmp/memao-qa-credentials.json")
        val json = JSONObject(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
        val url = MemosProtocol.serverOrigin(json.getString("serverUrl"))
        check(url.host == "127.0.0.1" || url.host == "localhost")
        val token = json.getString("token")
        check(token.isNotEmpty() && token.all { it.code in 0x21..0x7e })
        Credentials(url.toString(), token)
    } catch (_: Throwable) {
        throw AssertionError("缺少有效的本机验收凭据文件；仅允许隔离的loopback服务器")
    }

    private fun allowPermissionDialogIfPresent() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        fun visit(node: AccessibilityNodeInfo): Boolean {
            val id = node.viewIdResourceName.orEmpty()
            if (id.endsWith("/permission_allow_button") || id.endsWith("/permission_allow_foreground_only_button")) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (i in 0 until node.childCount) if (node.getChild(i)?.let(::visit) == true) return true
            return false
        }
        val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
        roots.any(::visit)
    }
}
