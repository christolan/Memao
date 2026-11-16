package com.xiaojiwei.memao.acceptance

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiaojiwei.memao.MainActivity
import com.xiaojiwei.memao.data.remote.MemosProtocol
import com.xiaojiwei.memao.data.remote.OkHttpMemosGateway
import com.xiaojiwei.memao.domain.DomainRules
import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.MemosException
import com.xiaojiwei.memao.model.newUid
import com.xiaojiwei.memao.ui.MemaoViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Actual clipboard URI -> editor -> protected draft -> manual send, opt-in only. */
@RunWith(AndroidJUnit4::class)
class LiveMediaAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun generatedClipboardImageSurvivesDraftAndCacheClearThenUploadsOriginalBytes() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Optional loopback APP media acceptance is disabled", arguments.getString("memaoLive") == "true")
        val keepQaAccount = arguments.getString("memaoKeepQaAccount") == "true"
        val credentials = credentials()
        val gateway = OkHttpMemosGateway({ credentials.second })
        val identity = runBlocking { gateway.inspect(credentials.first, credentials.second) }
        val account = Account("ui-media-acceptance", credentials.first, identity.userName, identity.displayName)
        val filename = "qa-${newUid()}.png"
        val uri = Uri.parse("content://${QaMediaProvider.AUTHORITY}/$filename")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        var connectedHere = false
        var completed = false
        var createdMemo: Memo? = null
        var stage = "核对本机测试账号"
        try {
            compose.waitUntil(30_000) { exists("server") || (exists("drawerToggle") || exists("navNotes")) }
            val restoringAccount = compose.runOnIdle { viewModel().repository.activeAccountId.value != null }
            if (!restoringAccount && exists("server")) {
                connectedHere = true
                try {
                    compose.onNodeWithTag("server", useUnmergedTree = true).performTextReplacement(credentials.first)
                    compose.onNodeWithTag("token", useUnmergedTree = true).performTextReplacement(credentials.second)
                    compose.onNodeWithTag("connect", useUnmergedTree = true).performScrollTo().performClick()
                    compose.waitUntil(30_000) { (exists("drawerToggle") || exists("navNotes")) }
                } catch (_: Throwable) {
                    throw AssertionError("本机媒体验收连接失败，请核对adb reverse及局域网权限（凭据已隐藏）")
                }
            }
            compose.waitUntil(30_000) { compose.runOnIdle { viewModel().state.account != null } }
            compose.runOnIdle {
                val active = viewModel().state.account
                check(active?.userName == account.userName && active.serverUrl == DomainRules.normalizeServer(account.serverUrl)) {
                    "媒体UI验收只允许操作凭据文件对应的本机测试账号"
                }
            }

            go("navNotes")
            val previousDraftIds = compose.runOnIdle { viewModel().state.drafts.map { it.id }.toSet() }
            stage = "向空白编辑器粘贴测试Provider图片URI"
            if (exists("drawerToggle") && !compose.onNodeWithTag("navigationDrawer", useUnmergedTree = true).isDisplayed()) {
                compose.onNodeWithTag("drawerToggle", useUnmergedTree = true).performClick()
            }
            waitTag("newMemo")
            compose.onNodeWithTag("newMemo", useUnmergedTree = true).performClick()
            waitTag("editorBody")
            compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Memao generated QA image", uri)) }
            compose.onNodeWithText("粘贴图片").performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.runOnIdle { viewModel().state.editor?.attachments?.singleOrNull()?.filename == filename }
            }
            val localCopy = compose.runOnIdle { viewModel().state.editor!!.attachments.single().localPath }
            assertArrayEquals(hash(QaPngFixture.bytes), hash(File(localCopy).readBytes()))
            assertEquals("", compose.runOnIdle { viewModel().state.editor!!.content })
            compose.onNodeWithTag("sendMemo", useUnmergedTree = true).assertIsEnabled()
            compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
            waitAbsent("editorBody")
            // Revoking the source clipboard must not revoke the persisted draft copy.
            compose.runOnIdle { clipboard.clearPrimaryClip() }

            stage = "清缓存和Activity重建后保留图片草稿"
            go("navSettings")
            waitTag("clearCache")
            compose.onNodeWithTag("clearCache", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasText("已清理普通缓存，未发送草稿及附件完整保留。")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.activityRule.scenario.recreate()
            go("navDrafts")
            var draftId = ""
            compose.waitUntil(30_000) {
                val matches = compose.runOnIdle { viewModel().state.drafts.filter { it.id !in previousDraftIds && it.attachments.any { a -> a.filename == filename } } }
                draftId = matches.singleOrNull()?.id.orEmpty()
                draftId.isNotEmpty()
            }
            waitTag("draft:$draftId")
            compose.onNodeWithTag("draft:$draftId", useUnmergedTree = true).performClick()
            waitText("继续编辑")
            compose.onNodeWithText("继续编辑").performClick()
            waitTag("editorBody")
            val restoredPath = compose.runOnIdle { viewModel().state.editor!!.attachments.single().localPath }
            assertArrayEquals(hash(QaPngFixture.bytes), hash(File(restoredPath).readBytes()))
            assertEquals("", compose.runOnIdle { viewModel().state.editor!!.content })
            captureEvidence()

            stage = "纯图片草稿手动发送并核对服务器原始字节"
            compose.onNodeWithTag("sendMemo", useUnmergedTree = true).performClick()
            waitAbsent("editorBody")
            compose.waitUntil(30_000) {
                createdMemo = runBlocking { gateway.list(account, MemoQuery(pageSize = 100)).memos.firstOrNull { memo ->
                    memo.attachments.any { it.filename == filename }
                } }
                createdMemo != null
            }
            val memo = requireNotNull(createdMemo)
            assertEquals("", memo.content)
            assertEquals("PRIVATE", memo.visibility)
            assertEquals(filename, memo.attachments.single().filename)
            val downloaded = File(context.cacheDir, "media-ui-${newUid()}.png")
            try {
                runBlocking { gateway.download(account, memo.attachments.single(), downloaded) }
                assertArrayEquals(hash(QaPngFixture.bytes), hash(downloaded.readBytes()))
            } finally { downloaded.delete() }

            if (keepQaAccount) {
                val directory = File(context.filesDir, "qa").apply { mkdirs() }
                val manifest = File(directory, "fixture-cleanup.json")
                val json = if (manifest.isFile) JSONObject(manifest.readText()) else JSONObject()
                val names = json.optJSONArray("memoNames") ?: JSONArray()
                names.put(memo.name)
                json.put("serverUrl", account.serverUrl).put("userName", account.userName)
                    .put("memoNames", names).put("keptForManualQa", true)
                manifest.writeText(json.toString())
            } else if (connectedHere) {
                stage = "清理本次媒体验收账号"
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
            val detail = error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
                .replace(credentials.second, "[令牌]").take(300)
            throw AssertionError("真实APP媒体验收失败，阶段：$stage；${error.javaClass.simpleName}：$detail（凭据已隐藏）")
        } finally {
            compose.runOnIdle { clipboard.clearPrimaryClip() }
            if (!keepQaAccount || !completed) runBlocking {
                val targets = linkedSetOf<String>()
                createdMemo?.let { targets.add(it.name) }
                runCatching { gateway.list(account, MemoQuery(pageSize = 100)).memos }
                    .getOrDefault(emptyList()).filter { memo -> memo.attachments.any { it.filename == filename } }
                    .forEach { targets.add(it.name) }
                targets.forEach { name ->
                    try { gateway.delete(account, name) }
                    catch (error: MemosException) { if (error.statusCode != 404) throw AssertionError("媒体验收临时笔记清理失败") }
                }
            }
        }
    }

    private fun viewModel(): MemaoViewModel = ViewModelProvider(compose.activity)[MemaoViewModel::class.java]
    private fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitTag(tag: String) { compose.waitUntil(30_000) { exists(tag) } }
    private fun waitAbsent(tag: String) { compose.waitUntil(30_000) { !exists(tag) } }
    private fun go(tag: String) {
        compose.waitUntil(30_000) { exists(tag) || exists("drawerToggle") }
        if (exists("drawerToggle") && !compose.onNodeWithTag("navigationDrawer", useUnmergedTree = true).isDisplayed()) {
            compose.onNodeWithTag("drawerToggle", useUnmergedTree = true).performClick()
        }
        waitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
    }
    private fun waitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun credentials(): Pair<String, String> = try {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat /data/local/tmp/memao-qa-credentials.json")
        val json = JSONObject(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
        val origin = MemosProtocol.serverOrigin(json.getString("serverUrl"))
        check(origin.host == "127.0.0.1" || origin.host == "localhost")
        val token = json.getString("token")
        check(token.isNotEmpty() && token.all { it.code in 0x21..0x7e })
        origin.toString() to token
    } catch (_: Throwable) { throw AssertionError("缺少有效的本机媒体验收凭据，仅允许loopback实例") }
    private fun captureEvidence() {
        check(!exists("token") && !exists("server")) { "禁止捕获凭据页面" }
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(250, 5_000)
        val bitmap = automation.takeScreenshot() ?: throw AssertionError("无法获取媒体验收截图")
        try {
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa").apply { mkdirs() }
            File(directory, "media-image-draft.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

/** Source comes from the Java-only standalone provider, byte-for-byte. */
internal object QaPngFixture {
    val bytes: ByteArray get() = QaMediaProvider.fixtureBytes()
}
