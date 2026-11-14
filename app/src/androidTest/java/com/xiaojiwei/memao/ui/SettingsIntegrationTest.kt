package com.xiaojiwei.memao.ui

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaojiwei.memao.data.local.MemaoDatabase
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings UI through the real ViewModel/Room/files, using only isolated fixture accounts. */
@RunWith(AndroidJUnit4::class)
class SettingsIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val name = "settings_" + UUID.randomUUID().toString().replace("-", "")
    private var repository: MemaoRepository? = null
    private var store: ViewModelStore? = null
    private val gateway = SettingsGateway()
    private val context: Context get() = compose.activity.applicationContext
    private val root: File get() = File(context.cacheDir, name)

    private fun openRepository(): MemaoRepository = MemaoRepository(
        context, gateway, TokenVault(context, name + "_tokens"),
        Room.databaseBuilder(context, MemaoDatabase::class.java, name + ".db").build(), name, root,
    ).also { repository = it }

    @After fun cleanFixture() {
        compose.runOnUiThread { store?.clear() }
        runBlocking {
            repository?.let { repo ->
                repo.accounts.value.forEach { repo.removeAccount(it.id) }
                repo.close()
            }
        }
        context.deleteDatabase(name + ".db")
        context.deleteSharedPreferences(name)
        context.deleteSharedPreferences(name + "_tokens")
        root.deleteRecursively()
    }

    @Test fun settingsActionsPersistThemeAndPreserveBothAccountsDraftsWhenClearing() {
        val repo = openRepository()
        val drafts = runBlocking {
            (1..2).map { index ->
                val account = repo.connect("https://fixture.invalid", "fixture-$index")
                val draft = repo.saveDraft(repo.newDraft(account.id).copy(content = "账号 $index 的未发送正文"))
                val source = File(context.cacheDir, "$name-$index.png").apply { writeBytes(byteArrayOf(index.toByte(), 3, 5, 7)) }
                val withMedia = repo.importAttachment(account.id, draft.id, Uri.fromFile(source))
                source.delete()
                repo.openMemo(account.id, "memos/cached-$index")
                withMedia
            }
        }
        runBlocking { repo.selectAccount(drafts.first().accountId) }
        lateinit var vm: MemaoViewModel
        compose.runOnUiThread {
            store = ViewModelStore()
            vm = ViewModelProvider(store!!, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MemaoViewModel(repo, context, SavedStateHandle()) as T
            })[MemaoViewModel::class.java]
        }
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) Box(Modifier.requiredWidth(420.dp).requiredHeight(780.dp)) { MemaoApp(vm) { _, _ -> } }
        }
        compose.waitUntil(10_000) { vm.state.accountId == drafts.first().accountId && vm.state.accounts.size == 2 && !vm.state.loading }
        compose.runOnIdle { vm.navigate(UiPage.SETTINGS) }
        compose.waitUntil(10_000) { vm.state.cache != null && !vm.state.cacheLoading }
        val before = vm.state.cache!!
        assertTrue(before.ordinaryBytes > 0)
        assertTrue(before.protectedBytes > 0)

        compose.onNodeWithTag("themeDark").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.state.settings.theme == ThemeMode.DARK }
        compose.onNodeWithTag("themeDark").assertIsSelected()
        compose.onNodeWithTag("settingsAccounts").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.state.page == UiPage.ACCOUNTS }
        compose.onNodeWithTag("pageTitle").assertTextEquals("服务器账号")
        compose.runOnIdle { vm.back() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { vm.state.page == UiPage.SETTINGS && !vm.state.cacheLoading }
        compose.onNodeWithTag("clearCache").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !vm.state.cacheClearing && vm.state.cache?.ordinaryBytes == 0L }
        compose.onNodeWithTag("clearCache").assertIsNotEnabled()
        compose.onNodeWithText("暂无可清理缓存").assertExists()
        compose.onNodeWithTag("appUpdateAction").performScrollTo().assertTextEquals("检查更新").assertIsEnabled()
        assertEquals(before.protectedBytes, vm.state.cache!!.protectedBytes)
        runBlocking {
            drafts.forEachIndexed { index, draft ->
                val kept = repo.getDraft(draft.accountId, draft.id)
                assertEquals(draft.content, kept.content)
                assertArrayEquals(byteArrayOf((index + 1).toByte(), 3, 5, 7), File(kept.attachments.single().localPath).readBytes())
            }
        }
        assertEquals(0, gateway.writes)

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnUiThread { store!!.clear(); store = null }
        runBlocking { repo.close() }
        repository = null
        val reopened = openRepository()
        assertEquals(ThemeMode.DARK, reopened.settings.value.theme)
        runBlocking {
            assertEquals(before.protectedBytes, reopened.cacheReport().protectedBytes)
            drafts.forEachIndexed { index, draft ->
                val kept = reopened.getDraft(draft.accountId, draft.id)
                assertEquals(draft.content, kept.content)
                assertArrayEquals(byteArrayOf((index + 1).toByte(), 3, 5, 7), File(kept.attachments.single().localPath).readBytes())
            }
        }
    }

    private class SettingsGateway : MemosGateway {
        var writes = 0
        override suspend fun inspect(serverUrl: String, token: String): ConnectionInfo {
            val id = token.substringAfterLast('-')
            return ConnectionInfo("users/$id", "设置测试账号$id", version = "0.31.0")
        }
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String) = MemoPage(listOf(memo(account)))
        override suspend fun tags(account: Account) = emptyList<TagCount>()
        override suspend fun get(account: Account, name: String) = memo(account).copy(name = name)
        private fun memo(account: Account) = Memo("memos/cached-${account.userName.substringAfterLast('/')}", "可清理的服务端笔记", account.userName)
        override suspend fun create(account: Account, request: CreateMemo): Memo { writes++; error("No server writes") }
        override suspend fun update(account: Account, request: UpdateMemo): Memo { writes++; error("No server writes") }
        override suspend fun delete(account: Account, name: String) { writes++; error("No server writes") }
        override suspend fun upload(account: Account, request: UploadFile): Attachment { writes++; error("No server writes") }
        override suspend fun download(account: Account, attachment: Attachment, destination: File) = error("No network downloads")
    }
}
