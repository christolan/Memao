package com.xiaojiwei.memao.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaojiwei.memao.ui.theme.MemaoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiPresentationTest {
    @get:Rule val compose=createComposeRule()

    @Test fun loginValidatesRootHttpServerAndKeepsTokenOutOfSavedState() {
        var connection:Pair<String,String>?=null
        val restore=StateRestorationTester(compose)
        restore.setContent {MemaoTheme {LoginPage(MemaoUiState(),{server,token->connection=server to token},null)}}
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        compose.onNodeWithText("请输入有效的 HTTP 或 HTTPS 服务器地址。").assertExists()
        compose.onNodeWithText("请填写访问令牌。").assertExists()
        assertNull(connection)
        compose.onNodeWithTag("server").performTextInput("http://192.168.0.5:5230/")
        compose.onNodeWithTag("token").performTextInput("fixture-only-token")
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("http://192.168.0.5:5230/" to "fixture-only-token",connection)}
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("token").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
    }

    @Test fun loginRejectsSubpathWithoutMakingConnection() {
        var calls=0
        compose.setContent {MemaoTheme {LoginPage(MemaoUiState(),{_,_->calls++},null)}}
        compose.onNodeWithTag("server").performTextInput("https://example.com/memos")
        compose.onNodeWithTag("token").performTextInput("fixture-only-token")
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        compose.onNodeWithText("首版支持部署在域名根路径的 Memos。").assertExists()
        compose.runOnIdle {assertEquals(0,calls)}
    }

    @Test fun advancedHtmlIsPresentedAsLiteralText() {
        compose.setContent {MemaoTheme {Markdown("<script>alert('x')</script>")}}
        compose.onNodeWithText("<script>alert('x')</script>").assertExists()
    }

    @Test fun markdownRendersCommonSubsetWithoutTruncation() {
        compose.setContent {MemaoTheme {Markdown("# 标题\n\n**粗体**与*斜体*\n\n- [ ] 待办\n- [x] 完成\n\n最后一行")}}
        compose.onNodeWithText("标题").assertExists()
        compose.onNodeWithText("粗体与斜体").assertExists()
        compose.onNodeWithText("待办").assertExists()
        compose.onNodeWithText("完成").assertExists()
        compose.onNodeWithText("最后一行").assertExists()
    }
}
