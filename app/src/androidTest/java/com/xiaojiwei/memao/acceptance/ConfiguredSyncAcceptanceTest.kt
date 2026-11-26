package com.xiaojiwei.memao.acceptance

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiaojiwei.memao.MainActivity
import com.xiaojiwei.memao.MemaoApplication
import com.xiaojiwei.memao.data.local.MemaoDatabase
import com.xiaojiwei.memao.data.local.ModelCodec
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.data.remote.OkHttpMemosGateway
import com.xiaojiwei.memao.domain.CONFLICT
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConfiguredSyncAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MemaoApplication

    private fun configuredAccount(): Account {
        assumeTrue(InstrumentationRegistry.getArguments().getString("memaoConfiguredSync") == "true")
        compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
        return runBlocking {
            withTimeout(15_000) {
                val id = app.repository.activeAccountId.first { it != null }
                app.repository.accounts.first { accounts -> accounts.any { it.id == id } }.first { it.id == id }
            }
        }
    }

    @Test fun offlineActionsAppearImmediatelyAndSyncOnReconnect() {
        val account = configuredAccount()
        val gateway = OkHttpMemosGateway(TokenVault(app)::get)
        val connection = app.getSystemService(ConnectivityManager::class.java)
        val marker = "Memao sync acceptance ${newUid()}"
        var name: String? = null
        val previousWifi = shell("settings get global wifi_on").trim()
        val previousMobile = shell("settings get global mobile_data").trim()
        assertEquals("Wi-Fi must be enabled before running this acceptance test", "1", previousWifi)
        try {
            shell("svc data disable")
            shell("svc wifi disable")
            compose.waitUntil(15_000) { connection.activeNetwork == null }
            openMenu("newMemo")
            compose.onNodeWithTag("editorBody").performTextReplacement(marker)
            compose.onNodeWithTag("sendMemo").performClick()
            waitText(marker)
            compose.onNodeWithText("同步中").assertDoesNotExist()
            compose.onNodeWithText("待同步").assertDoesNotExist()
            val saved = runBlocking { app.repository.syncOperations(account.id).first().single { ModelCodec.memo(it.proposed).content == marker } }
            name = saved.memoName
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { it.setShowWhenLocked(true); it.setTurnScreenOn(true) }
            waitText(marker)
            rowAction(name, "归档笔记")
            openMenu("navArchive")
            waitText(marker)
            rowAction(name, "取消归档")
            openMenu("navNotes")
            waitText(marker)
            rowAction(name, "删除笔记")
            compose.onNodeWithText("删除笔记").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(marker).fetchSemanticsNodes().isEmpty() }
            openMenu("navSettings")
            compose.onNodeWithTag("settingsSync").performScrollTo().performClick()
            waitText("待同步 · 已删除")
            compose.onNodeWithText("撤销删除").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("撤销删除").fetchSemanticsNodes().isEmpty() }
            openMenu("navNotes")
            waitText(marker)

            shell("svc wifi enable")
            compose.waitUntil(45_000) {
                runBlocking { runCatching { gateway.get(account, name).content == marker }.getOrDefault(false) }
            }
            compose.waitUntil(30_000) { runBlocking { app.repository.syncOperations(account.id).first().none { it.memoName == name } } }
            assertFalse(runBlocking { gateway.get(account, name).archived })
            rowAction(name, "删除笔记")
            compose.onNodeWithText("删除笔记").performClick()
            compose.waitUntil(45_000) {
                runBlocking { (runCatching { gateway.get(account, name) }.exceptionOrNull() as? MemosException)?.statusCode == 404 }
            }
            compose.waitUntil(30_000) { runBlocking { app.repository.syncOperations(account.id).first().none { it.memoName == name } } }
        } finally {
            shell("svc wifi enable")
            if (previousMobile == "1") shell("svc data enable")
            name?.let { savedName -> runBlocking { runCatching { gateway.delete(account, savedName) } } }
        }
    }

    @Test fun mergesIndependentEditsAndRetainsOverlappingVersions() {
        val configured = configuredAccount()
        val secret = checkNotNull(TokenVault(app).get(configured.id))
        val gateway = OkHttpMemosGateway({ secret })
        val fixture = "sync-acceptance-${newUid()}"
        val database = Room.inMemoryDatabaseBuilder(app, MemaoDatabase::class.java).build()
        val files = File(app.cacheDir, fixture)
        val vault = TokenVault(app, fixture)
        val repository = MemaoRepository(app, gateway, vault, database, fixture, files)
        val marker = "Memao sync acceptance ${newUid()}"
        var account: Account? = null
        var name: String? = null
        runBlocking {
            try {
                val owner = repository.connect(configured.serverUrl, secret)
                account = owner
                val created = repository.saveDraft(repository.newDraft(owner.id).copy(content = "$marker\nFirst section\nLast section"))
                val memo = repository.saveNote(owner.id, created.id)
                name = memo.name
                assertFalse(repository.synchronize(retry = true))
                assertEquals(memo.content, gateway.get(owner, memo.name).content)
                val base = repository.prepareEdit(owner.id, memo.name)
                repository.saveDraft(base.copy(content = "$marker\nFirst local\nLast section"))
                repository.saveNote(owner.id, base.id)
                gateway.update(owner, UpdateMemo(memo.name, "$marker\nFirst section\nLast remote"))
                assertFalse(repository.synchronize(retry = true))
                val merged = "$marker\nFirst local\nLast remote"
                assertEquals(merged, gateway.get(owner, memo.name).content)

                val overlap = repository.prepareEdit(owner.id, memo.name)
                val local = "$marker\nMine\nLast remote"
                val server = "$marker\nTheirs\nLast remote"
                repository.saveDraft(overlap.copy(content = local))
                repository.saveNote(owner.id, overlap.id)
                gateway.update(owner, UpdateMemo(memo.name, server))
                assertFalse(repository.synchronize(retry = true))
                val conflict = repository.syncOperations(owner.id).first().single { it.memoName == memo.name }
                assertEquals(CONFLICT, conflict.status)
                assertEquals(local, ModelCodec.memo(conflict.proposed).content)
                assertEquals(server, ModelCodec.memo(checkNotNull(conflict.remote)).content)
                repository.resolveSync(owner.id, conflict.id, ConflictChoice.KEEP_SERVER)
                assertEquals(local, repository.getDraft(owner.id, overlap.id).content)
                assertEquals(server, gateway.get(owner, memo.name).content)
            } finally {
                if (name != null && account != null) runCatching { gateway.delete(account, name) }
                repository.close()
                app.deleteSharedPreferences(fixture)
                files.deleteRecursively()
            }
        }
    }

    private fun openMenu(tag: String) {
        compose.onNodeWithContentDescription("打开导航菜单").performClick()
        compose.onNodeWithTag(tag).performClick()
    }

    private fun rowAction(name: String, action: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("memoActions:$name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("memoActions:$name").performClick()
        compose.onNodeWithText(action).performClick()
    }

    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
