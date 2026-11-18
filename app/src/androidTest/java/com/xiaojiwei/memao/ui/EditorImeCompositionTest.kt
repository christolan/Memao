package com.xiaojiwei.memao.ui

import android.content.Context
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaojiwei.memao.data.local.MemaoDatabase
import com.xiaojiwei.memao.data.local.ModelCodec
import com.xiaojiwei.memao.data.local.TokenVault
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class EditorImeCompositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixtureName = "editor_ime_${UUID.randomUUID()}"
    private val storage = File(context.cacheDir, fixtureName)
    private val store = ViewModelStore()
    private lateinit var database: MemaoDatabase
    private var repository: MemaoRepository? = null
    @Volatile private var connection: InputConnection? = null

    @After fun cleanFixture() {
        compose.runOnUiThread { store.clear() }
        runBlocking { repository?.close() }
        storage.deleteRecursively()
        context.deleteSharedPreferences(fixtureName)
        context.deleteSharedPreferences("${fixtureName}_tokens")
    }

    @Test fun sameFramePinyinRollbackKeepsCompositionAndCanCommitCandidate() {
        val account = start()
        compose.onNodeWithTag("drawerToggle").performClick()
        compose.onNodeWithTag("newMemo").performClick()
        compose.waitUntil(10_000) { connection != null }
        val editor = compose.onNodeWithTag("editorBody").assertIsFocused()
        val initialConnection = compose.runOnIdle { checkNotNull(connection) }

        compose.runOnIdle { assertTrue(initialConnection.setComposingText("ni", 1)) }
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("ni")))

        // Separate IME edits in one main-thread turn can return to the previous frame's value.
        compose.runOnIdle {
            assertTrue(initialConnection.setComposingText("ni h", 1))
            assertTrue(initialConnection.setComposingText("ni", 1))
        }
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("ni")))
        editor.assertIsFocused()
        compose.runOnIdle {
            assertSame(initialConnection, connection)
            assertTrue(initialConnection.commitText("你", 1))
        }

        // A still-active composing range is replaced, so the raw pinyin cannot leak into the note.
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("你")))
        compose.waitUntil(10_000) {
            runBlocking {
                database.dao().drafts(account.id).singleOrNull()
                    ?.let { ModelCodec.draft(it.payload).content } == "你"
            }
        }
    }

    private fun start(): Account {
        database = Room.inMemoryDatabaseBuilder(context, MemaoDatabase::class.java).build()
        val repo = MemaoRepository(
            context, EditorImeGateway, TokenVault(context, "${fixtureName}_tokens"),
            database, fixtureName, storage,
        ).also { repository = it }
        val account = runBlocking { repo.connect("https://fixture.invalid", "fixture-only-token") }
        val vm = compose.runOnUiThread {
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MemaoViewModel(repo, context, SavedStateHandle()) as T
            })[MemaoViewModel::class.java]
        }
        compose.setContent {
            InterceptPlatformTextInput(
                interceptor = { request, next ->
                    next.startInputMethod(PlatformTextInputMethodRequest { attributes ->
                        val created = request.createInputConnection(attributes)
                        connection = created
                        object : InputConnectionWrapper(created, false) {
                            // The installed IME does not own this test's synthetic composing range.
                            override fun finishComposingText(): Boolean = true
                        }
                    })
                },
            ) {
                Box(Modifier.requiredWidth(420.dp)) { MemaoApp(vm) { _, _ -> } }
            }
        }
        compose.waitUntil(10_000) { vm.state.accountId == account.id }
        return account
    }

    private object EditorImeGateway : MemosGateway {
        override suspend fun inspect(serverUrl: String, token: String) =
            ConnectionInfo("users/1", "测试账号", version = "0.31.0")
        override suspend fun list(account: Account, query: MemoQuery, pageToken: String) = MemoPage(emptyList())
        override suspend fun tags(account: Account) = emptyList<TagCount>()
        override suspend fun get(account: Account, name: String): Memo = error("Unexpected get")
        override suspend fun create(account: Account, request: CreateMemo): Memo = error("Unexpected create")
        override suspend fun update(account: Account, request: UpdateMemo): Memo = error("Unexpected update")
        override suspend fun delete(account: Account, name: String) = error("Unexpected delete")
        override suspend fun upload(account: Account, request: UploadFile): Attachment = error("Unexpected upload")
        override suspend fun download(account: Account, attachment: Attachment, destination: File) = error("Unexpected download")
    }
}
