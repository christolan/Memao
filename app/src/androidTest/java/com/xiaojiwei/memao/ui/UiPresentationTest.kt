package com.xiaojiwei.memao.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaojiwei.memao.ui.theme.MemaoTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun loginCaretAndPlaceholderAreVerticallyCenteredInBothFields() {
        compose.setContent {
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                MemaoTheme { LoginPage(MemaoUiState(), { _, _ -> }, null) }
            }
        }
        assertCenteredField("server", "https://memos.example.com")
        assertCenteredField("token", "粘贴 Memos 访问令牌")
    }

    private fun assertCenteredField(tag: String, placeholder: String) {
        val field = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithText(placeholder, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tolerance = with(compose.density) { 1.dp.toPx() }
        assertTrue(
            "$tag placeholder center ${hint.center.y} should match field center ${field.center.y}; field h=${field.height} hint h=${hint.height} density=${compose.density.density}",
            abs(hint.center.y - field.center.y) <= tolerance,
        )
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        var top = pixels.height
        var bottom = -1
        var hits = 0
        for (y in 0 until pixels.height) {
            var row = false
            val caretColumns = (pixels.width * 0.08f).toInt().coerceAtLeast(8)
            for (x in 0 until caretColumns) {
                val pixel = pixels[x, y]
                val red = (pixel.red * 255f).toInt()
                val green = (pixel.green * 255f).toInt()
                val blue = (pixel.blue * 255f).toInt()
                if (abs(red - 0x24) <= 48 && abs(green - 0x26) <= 48 && abs(blue - 0x29) <= 48 && pixel.alpha > .8f) {
                    row = true
                    break
                }
            }
            if (row) {
                hits++
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        assertTrue("$tag should draw an ink caret", hits >= 4)
        val caretCenter = (top + bottom) / 2f
        val imageCenter = (pixels.height - 1) / 2f
        assertTrue(
            "$tag caret center $caretCenter (y $top..$bottom) image h=${pixels.height} center $imageCenter field h=${field.height}",
            abs(caretCenter - imageCenter) <= tolerance.coerceAtLeast(1f),
        )
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

    @Test fun foldedMarkdownDistinguishesLinkTapsLongPressAndBlankSpaceAtLargeFont() {
        val uri = "https://example.invalid/note"
        val opened = mutableListOf<String>()
        val expanded = mutableStateOf(false)
        var edits = 0
        var toggles = 0
        var overflowing = false
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened.add(uri) }
        }
        val content = "[链接]($uri)\n普通正文\n" + "字号放大后仍按实际排版行数折叠。".repeat(16)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1.8f),
                LocalUriHandler provides handler,
            ) {
                MemaoTheme {
                    FoldableMarkdown(content, expanded.value, { overflowing = it },
                        { toggles++; expanded.value = !expanded.value }, { edits++ },
                        modifier = Modifier.width(320.dp), compact = true)
                }
            }
        }
        val text = compose.onNodeWithTag("memoLineText", useUnmergedTree = true)
        fun layout(): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        val folded = layout()
        assertEquals(6, folded.lineCount)
        compose.runOnIdle { assertTrue(overflowing) }
        val linkPoint = folded.getBoundingBox(0).center
        text.performTouchInput { longClick(linkPoint) }
        compose.runOnIdle { assertEquals(1, edits); assertEquals(0, toggles); assertTrue(opened.isEmpty()) }

        text.performTouchInput { click(linkPoint) }
        compose.runOnIdle { assertEquals(listOf(uri), opened); assertEquals(0, toggles); assertEquals(1, edits) }

        val plainPoint = folded.getBoundingBox(folded.layoutInput.text.text.indexOf("普通")).center
        text.performTouchInput { click(plainPoint) }
        compose.runOnIdle { assertTrue(expanded.value); assertEquals(1, toggles); assertEquals(listOf(uri), opened) }
        assertTrue(layout().lineCount > 6)

        // getOffsetForPosition alone maps this empty area to the final link character.
        // Only a hit inside that character's actual bounding box may open the URI.
        val current = layout()
        val blankPoint = Offset(current.size.width - 8f, current.getBoundingBox(0).center.y)
        assertTrue(blankPoint.x > current.getBoundingBox(1).right)
        text.performTouchInput { click(blankPoint) }
        compose.runOnIdle { assertEquals(false, expanded.value); assertEquals(2, toggles); assertEquals(listOf(uri), opened); assertEquals(1, edits) }
        assertEquals(6, layout().lineCount)
    }

    @Test fun ellipsisInsideLongLinkExpandsWithoutOpeningHiddenLinkText() {
        val uri = "https://example.invalid/long-link"
        val opened = mutableListOf<String>()
        val expanded = mutableStateOf(false)
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened.add(uri) }
        }
        val content = "[" + "这段超长链接文字用于验证折叠省略号。".repeat(24) + "]($uri)"
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                MemaoTheme {
                    FoldableMarkdown(content, expanded.value, {}, { expanded.value = !expanded.value }, {},
                        modifier = Modifier.width(240.dp), compact = true)
                }
            }
        }
        val text = compose.onNodeWithTag("memoLineText", useUnmergedTree = true)
        fun layout(): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        val folded = layout()
        assertEquals(6, folded.lineCount)
        assertTrue("The target must be a real truncated line", folded.isLineEllipsized(5))
        val ellipsis = Offset(folded.getLineRight(5) - 1f, (folded.getLineTop(5) + folded.getLineBottom(5)) / 2)
        text.performTouchInput { click(ellipsis) }
        compose.runOnIdle { assertTrue(expanded.value); assertTrue(opened.isEmpty()) }
        assertTrue(layout().lineCount > 6)

        // Removing the synthetic ellipsis must not disable ordinary visible link text.
        val visibleLink = layout().getBoundingBox(0).center
        text.performTouchInput { click(visibleLink) }
        compose.runOnIdle { assertEquals(listOf(uri), opened); assertTrue(expanded.value) }
    }
}
