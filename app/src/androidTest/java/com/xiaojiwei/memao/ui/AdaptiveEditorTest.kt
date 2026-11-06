package com.xiaojiwei.memao.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Logical window constraints exercise both layouts; physical Find N6 hinges require separate device QA. */
@RunWith(AndroidJUnit4::class)
class AdaptiveEditorTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var database:MemaoDatabase?=null
    private var repository:MemaoRepository?=null
    private var store:ViewModelStore?=null
    private var files:File?=null
    private var preferences:String?=null

    @After fun clean() {
        compose.runOnUiThread {store?.clear()}
        runBlocking {repository?.close()}
        files?.deleteRecursively()
        preferences?.let {name->
            val context=ApplicationProvider.getApplicationContext<Context>()
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(name+"_tokens")
        }
    }

    @Test fun changingWindowWidthKeepsDraftCursorPreviewAndNeverPublishes() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="adaptive_"+UUID.randomUUID().toString().replace("-","")
        preferences=name
        val db=Room.inMemoryDatabaseBuilder(context,MemaoDatabase::class.java).build()
        database=db
        val root=File(context.cacheDir,name).apply {mkdirs()};files=root
        val gateway=EditorGateway()
        val repo=MemaoRepository(context,gateway,TokenVault(context,name+"_tokens"),db,name,root)
        repository=repo
        runBlocking {repo.connect("https://fixture.invalid","fixture-only-token")}
        lateinit var vm:MemaoViewModel
        compose.runOnUiThread {
            store=ViewModelStore()
            vm=ViewModelProvider(store!!,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T:ViewModel> create(modelClass:Class<T>):T=MemaoViewModel(repo,context,SavedStateHandle()) as T
            })[MemaoViewModel::class.java]
        }
        val width=mutableStateOf(420.dp)
        compose.setContent {Box(Modifier.requiredWidth(width.value).fillMaxHeight()){MemaoApp(vm){_,_->}}}
        compose.waitUntil(10_000){vm.state.accountId!=null}
        compose.onNodeWithTag("drawerToggle").performClick()
        compose.onNodeWithTag("newMemo").performClick()
        compose.waitUntil(10_000){vm.state.editor!=null}
        compose.onNodeWithTag("editorBody").performTextInput("第一行记录\n折叠后继续编辑 #日常")
        compose.onNodeWithTag("editorBody").performTextInputSelection(TextRange(2,5))
        val draftId=vm.state.editor!!.id
        compose.onNodeWithTag("editorBody").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(2,5)))
        compose.runOnIdle {
            assertEquals("设置选区后、改变宽度之前",2,vm.state.editor!!.cursorStart)
            assertEquals("设置选区后、改变宽度之前",5,vm.state.editor!!.cursorEnd)
            width.value=1000.dp
        }
        compose.waitForIdle()
        compose.onNodeWithTag("editorBody").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("第一行记录\n折叠后继续编辑 #日常")))
        compose.runOnIdle {
            assertEquals(draftId,vm.state.editor!!.id)
            assertEquals(2,vm.state.editor!!.cursorStart)
            assertEquals(5,vm.state.editor!!.cursorEnd)
            width.value=600.dp
        }
        compose.waitForIdle()
        compose.onNodeWithTag("navigationDrawer").assertExists()
        compose.onNodeWithTag("editorBody").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(2,5)))
        compose.runOnIdle {
            width.value=420.dp
        }
        compose.waitForIdle()
        compose.onNodeWithTag("editorBody").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(2,5)))
        compose.onNodeWithText("预览",useUnmergedTree=true).performClick()
        compose.runOnIdle {width.value=1000.dp}
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(true,vm.state.preview)
            assertEquals(draftId,vm.state.editor!!.id)
            assertEquals(2,vm.state.editor!!.cursorStart)
            assertEquals(5,vm.state.editor!!.cursorEnd)
            assertEquals(0,gateway.creates)
            width.value=420.dp
        }
        compose.waitForIdle()
        compose.onNodeWithText("编辑",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("editorBody").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(2,5)))
        compose.runOnUiThread {vm.flushOnStop()}
        compose.waitUntil(10_000){!vm.state.saving}
        val persisted=runBlocking {repo.getDraft(vm.state.accountId!!,draftId)}
        assertEquals("第一行记录\n折叠后继续编辑 #日常",persisted.content)
        assertEquals(2,persisted.cursorStart)
        assertEquals(5,persisted.cursorEnd)
    }

    private class EditorGateway:MemosGateway {
        var creates=0
        override suspend fun inspect(serverUrl:String,token:String)=ConnectionInfo("users/1","测试账号",version="0.31.0")
        override suspend fun list(account:Account,query:MemoQuery,pageToken:String)=MemoPage(emptyList())
        override suspend fun tags(account:Account)=emptyList<TagCount>()
        override suspend fun get(account:Account,name:String):Memo=throw MemosException("不存在",404)
        override suspend fun create(account:Account,request:CreateMemo):Memo {creates++;error("测试不应发送")}
        override suspend fun update(account:Account,request:UpdateMemo):Memo=error("测试不应发送")
        override suspend fun delete(account:Account,name:String)=error("测试不应删除")
        override suspend fun upload(account:Account,request:UploadFile):Attachment=error("测试不应上传")
        override suspend fun download(account:Account,attachment:Attachment,destination:File)=error("测试不应下载")
    }
}
