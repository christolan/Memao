package com.xiaojiwei.memao.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaojiwei.memao.R
import com.xiaojiwei.memao.model.Account
import com.xiaojiwei.memao.model.AppSettings
import com.xiaojiwei.memao.model.CacheReport
import com.xiaojiwei.memao.model.ThemeMode
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors
import com.xiaojiwei.memao.ui.theme.MemaoTheme
import com.xiaojiwei.memao.update.AppRelease
import com.xiaojiwei.memao.update.UpdateState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated presentation checks: no user accounts, server requests, or real cache deletion. */
@RunWith(AndroidJUnit4::class)
class SettingsPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ui = mutableStateOf(sampleState())
    private val width = mutableStateOf(420.dp)
    private val fontScale = mutableStateOf(1f)

    @Test fun themeOptionsExposeSingleSelectionAndAccountRowNavigates() {
        val selected = mutableListOf<ThemeMode>()
        var accountOpens = 0
        start(onTheme = { selected += it; ui.value = ui.value.copy(settings = AppSettings(it)) },
            onAccounts = { accountOpens++ })

        val options = listOf("themeSystem" to ThemeMode.SYSTEM, "themeLight" to ThemeMode.LIGHT,
            "themeDark" to ThemeMode.DARK)
        compose.onNodeWithTag("themeSystem").assertIsSelected()
        for ((tag, _) in options) {
            compose.onNodeWithTag(tag).assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            assertTouchTarget(tag)
        }
        val horizontal = options.map { compose.onNodeWithTag(it.first).fetchSemanticsNode().boundsInRoot }
        assertEquals(horizontal[0].top, horizontal[1].top, 1f)
        assertEquals(horizontal[1].top, horizontal[2].top, 1f)
        for ((tag, mode) in options.drop(1) + options.take(1)) {
            compose.onNodeWithTag(tag).performClick().assertIsSelected()
            options.filter { it.first != tag }.forEach { compose.onNodeWithTag(it.first).assertIsNotSelected() }
            compose.runOnIdle { assertEquals(mode, selected.last()); assertEquals(mode, ui.value.settings.theme) }
        }
        compose.onNodeWithTag("settingsAccounts").performScrollTo().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, accountOpens); assertEquals(3, selected.size) }
    }

    @Test fun storageAndAppInformationStayReadOnlyWhileClearIsAnExplicitAction() {
        start()
        for (label in listOf("普通缓存", "受保护的草稿及附件", "Memao", "记录生活里的小事")) {
            compose.onNodeWithText(label).assertExists().assertHasNoClickAction()
        }
        compose.onNodeWithTag("settingsOrdinaryValue").assertTextEquals("128.0 MB").assertHasNoClickAction()
        compose.onNodeWithTag("settingsProtectedValue").assertTextEquals("24.0 MB").assertHasNoClickAction()
        saveScreenshot("settings-compact-light.png")
        compose.onNodeWithTag("clearCache").performScrollTo().assertIsEnabled().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertTouchTarget("clearCache")
        compose.onNodeWithText("保留所有未发送草稿及其图片、音频。").assertExists().assertHasNoClickAction()
        saveScreenshot("settings-compact-storage.png")
    }

    @Test fun cacheReadShowsUnknownThenFailureRetryAndActualValues() {
        ui.value = sampleState().copy(cache = null, cacheLoading = true)
        var reads = 0
        start(onLoadCache = {
            reads++
            ui.value = ui.value.copy(cacheLoading = true, cacheReadError = null)
        })
        compose.onAllNodesWithText("0 B").assertCountEquals(0)
        compose.onAllNodesWithText("读取中…").assertCountEquals(2)
        compose.onNodeWithTag("clearCache").performScrollTo().assertIsNotEnabled()

        compose.runOnIdle { ui.value = ui.value.copy(cacheLoading = false, cacheReadError = "无法读取缓存占用，请重试。") }
        compose.onNodeWithTag("settingsReadError").assertTextEquals("无法读取缓存占用，请重试。")
        compose.onNodeWithTag("settingsOrdinaryValue").assertTextEquals("—")
        compose.onNodeWithTag("clearCache").assertIsNotEnabled()
        compose.onNodeWithTag("cacheReadRetry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reads) }
        compose.onNodeWithTag("cacheReadRetry").assertDoesNotExist()

        compose.runOnIdle { ui.value = ui.value.copy(cacheLoading = false, cache = CacheReport(5 * MB, 2 * MB)) }
        compose.onNodeWithTag("settingsOrdinaryValue").assertTextEquals("5.0 MB")
        compose.onNodeWithTag("settingsProtectedValue").assertTextEquals("2.0 MB")
        compose.onNodeWithTag("clearCache").performScrollTo().assertIsEnabled()
    }

    @Test fun clearBusyFailureAndZeroCacheHaveDistinctRecoverableStates() {
        var clears = 0
        start(onClearCache = {
            clears++
            ui.value = ui.value.copy(cacheClearing = true, cacheClearError = null)
        })
        val clear = compose.onNodeWithTag("clearCache")
        clear.performScrollTo().performClick().assertIsNotEnabled()
        compose.onNodeWithText("正在清理…").assertExists()
        clear.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clears) }
        compose.onNodeWithTag("settingsProtectedValue").assertTextEquals("24.0 MB")

        compose.runOnIdle { ui.value = ui.value.copy(cacheClearing = false, cacheClearError = "清理失败，请重试。") }
        compose.onNodeWithTag("settingsClearError").assertTextEquals("清理失败，请重试。")
        compose.onNodeWithTag("settingsOrdinaryValue").assertTextEquals("128.0 MB")
        compose.onNodeWithText("重试清理").assertExists()
        clear.performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, clears) }

        compose.runOnIdle {
            ui.value = ui.value.copy(cacheClearing = false, cacheClearError = null,
                cache = CacheReport(0, 24 * MB, removedBytes = 128 * MB))
        }
        compose.onNodeWithTag("settingsOrdinaryValue").assertTextEquals("0 B")
        compose.onNodeWithTag("settingsProtectedValue").assertTextEquals("24.0 MB")
        compose.onNodeWithText("暂无可清理缓存").assertExists()
        clear.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, clears) }
        saveScreenshot("settings-cache-cleared.png")
    }

    @Test fun narrowLargeFontReflowsThemeChoicesAndPreservesReadableActions() {
        width.value = 320.dp
        start()
        for (tag in listOf("themeSystem", "themeLight", "themeDark")) assertTouchTarget(tag)
        assertNoTextOverflow()
        saveScreenshot("settings-narrow-light.png")

        compose.runOnIdle {
            fontScale.value = 2f
            val account = ui.value.accounts.single().copy(displayName = "用于检查大字体自动换行的较长账号名称")
            ui.value = ui.value.copy(accounts = listOf(account))
        }
        val stacked = listOf("themeSystem", "themeLight", "themeDark")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(stacked[0].bottom <= stacked[1].top)
        assertTrue(stacked[1].bottom <= stacked[2].top)
        for (tag in listOf("themeSystem", "themeLight", "themeDark")) assertTouchTarget(tag)
        assertNoTextOverflow()
        saveScreenshot("settings-narrow-large-font-top.png")
        compose.onNodeWithTag("settingsAccounts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("clearCache").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertTouchTarget("clearCache")
        assertNoTextOverflow()
        saveScreenshot("settings-narrow-large-font-storage.png")
    }

    @Test fun wideDarkSettingsKeepContentReadableWithinMaximumWidth() {
        width.value = 900.dp
        ui.value = sampleState().copy(settings = AppSettings(ThemeMode.DARK))
        start()
        compose.onNodeWithTag("settingsBody").assertWidthIsEqualTo(640.dp)
        compose.onNodeWithTag("themeDark").assertIsSelected()
        compose.onNodeWithTag("settingsAccounts").assertIsEnabled()
        assertNoTextOverflow()
        saveScreenshot("settings-expanded-dark.png")
    }

    @Test fun appUpdateKeepsCheckDownloadAndInstallationAsSeparateActions() {
        val release = AppRelease(
            versionName = "0.1.19",
            versionCode = 20,
            size = 42,
            sha256 = "a".repeat(64),
            fileName = "Memao-0.1.19-release.apk",
        )
        val update = mutableStateOf<UpdateState>(UpdateState.Unchecked)
        var installRequests = 0
        var confirmations = 0
        start(appUpdate = {
            SettingsUpdate(
                installedVersion = "0.1.18",
                state = update.value,
                onCheck = { update.value = UpdateState.Checking },
                onDownload = { update.value = UpdateState.Downloading(release, 30) },
                onCancelDownload = { update.value = UpdateState.Available(release) },
                onInstall = { installRequests++ },
                onConfirm = { confirmations++ },
            )
        })

        val action = compose.onNodeWithTag("appUpdateAction")
        val status = compose.onNodeWithTag("appUpdateStatus")
        fun assertAction(label: String) = action.performScrollTo().assertTextEquals(label)

        compose.onNodeWithText("当前版本 0.1.18").performScrollTo().assertIsDisplayed()
        assertAction("检查更新").performClick()
        status.assertTextEquals("正在检查更新…")
        action.assertIsNotEnabled()
        compose.runOnIdle { update.value = UpdateState.Current }
        status.assertTextEquals("已是最新版本。")
        assertAction("检查更新").assertIsEnabled()
        compose.runOnIdle { update.value = UpdateState.CheckFailed }
        assertAction("重新检查").performClick()
        compose.runOnIdle { update.value = UpdateState.Available(release) }
        status.assertTextEquals("发现新版本 0.1.19")

        assertAction("下载更新").performClick()
        status.assertTextEquals("正在下载 30%")
        assertAction("取消下载").performClick()
        assertAction("下载更新").assertIsEnabled()
        compose.runOnIdle { update.value = UpdateState.Available(release, error = R.string.memao_update_download_failed) }
        assertAction("重试下载").assertIsEnabled()
        compose.onNodeWithTag("appUpdateCheckAgain").performScrollTo().performClick()
        status.assertTextEquals("正在检查更新…")

        compose.runOnIdle { update.value = UpdateState.Ready(release) }
        status.assertTextEquals("版本 0.1.19 已下载。")
        compose.runOnIdle { assertEquals(0, installRequests) }
        assertAction("安装更新").performClick()
        compose.runOnIdle {
            assertEquals(1, installRequests)
            update.value = UpdateState.Ready(release, message = R.string.memao_update_install_permission)
        }
        assertAction("前往授权").performClick()
        compose.runOnIdle {
            assertEquals(2, installRequests)
            update.value = UpdateState.Confirm(release, Intent())
        }
        assertAction("打开安装确认").performClick()
        compose.runOnIdle {
            assertEquals(1, confirmations)
            update.value = UpdateState.Installing(release)
        }
        status.assertTextEquals("正在安装…")
        action.assertIsNotEnabled()
    }

    private fun start(
        onTheme: (ThemeMode) -> Unit = { ui.value = ui.value.copy(settings = AppSettings(it)) },
        onAccounts: () -> Unit = {},
        onLoadCache: () -> Unit = {},
        onClearCache: () -> Unit = {},
        appUpdate: @Composable () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                MemaoTheme(darkTheme = ui.value.settings.theme == ThemeMode.DARK) {
                    Box(Modifier.requiredWidth(width.value).requiredHeight(760.dp)
                        .background(LocalMemaoColors.current.background).testTag("settings-fixture")) {
                        SettingsContent(ui.value, onTheme, onAccounts, onLoadCache, onClearCache, appUpdate = appUpdate)
                    }
                }
            }
        }
    }

    private fun assertTouchTarget(tag: String) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val minimum = 48 * compose.density.density
        assertTrue("$tag needs a 48 dp wide touch target", bounds.width + 1 >= minimum)
        assertTrue("$tag needs a 48 dp tall touch target", bounds.height + 1 >= minimum)
    }

    private fun assertNoTextOverflow() {
        val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        val failures = mutableListOf<String>()
        repeat(textNodes.fetchSemanticsNodes().size) { index ->
            val results = mutableListOf<TextLayoutResult>()
            textNodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            results.forEach { result ->
                val ellipsized = (0 until result.lineCount).any(result::isLineEllipsized)
                // String Text's semantics rebuilds MultiParagraph with the parent maximum width,
                // while size remains the actual measured text size. Its total width therefore
                // cannot diagnose clipping. Check rendered line extents and full text coverage;
                // allow at most one physical pixel for float-to-integer layout rounding.
                val roundingPx = 1f
                val lineOutsideSize = (0 until result.lineCount).any { line ->
                    result.getLineLeft(line) < -roundingPx ||
                        result.getLineRight(line) > result.size.width + roundingPx ||
                        result.getLineTop(line) < -roundingPx ||
                        result.getLineBottom(line) > result.size.height + roundingPx
                }
                val coveredEnd = if (result.lineCount > 0) result.getLineEnd(result.lineCount - 1, visibleEnd = false) else 0
                val missingText = coveredEnd != result.layoutInput.text.length
                val heightOverflow = result.multiParagraph.height > result.size.height + roundingPx
                if (lineOutsideSize || heightOverflow || missingText || ellipsized || result.multiParagraph.didExceedMaxLines) {
                    val lines = (0 until result.lineCount).joinToString { line ->
                        "$line:[left=${result.getLineLeft(line)},right=${result.getLineRight(line)}," +
                            "top=${result.getLineTop(line)},bottom=${result.getLineBottom(line)}," +
                            "start=${result.getLineStart(line)},end=${result.getLineEnd(line, visibleEnd = false)}," +
                            "ellipsis=${result.isLineEllipsized(line)}]"
                    }
                    failures += "Text must wrap rather than clip: ${result.layoutInput.text.text}; " +
                        "lineOutsideSize=$lineOutsideSize, heightOverflow=$heightOverflow, " +
                        "coveredEnd=$coveredEnd/${result.layoutInput.text.length}, " +
                        "didOverflowWidth=${result.didOverflowWidth}, didOverflowHeight=${result.didOverflowHeight}, " +
                        "size=${result.size}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
                        "lineCount=${result.lineCount}, didExceedMaxLines=${result.multiParagraph.didExceedMaxLines}, " +
                        "constraints=${result.layoutInput.constraints}, density=${result.layoutInput.density}, " +
                        "fontSize=${result.layoutInput.style.fontSize}, lineHeight=${result.layoutInput.style.lineHeight}, " +
                        "softWrap=${result.layoutInput.softWrap}, overflow=${result.layoutInput.overflow}, lines=$lines"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun saveScreenshot(name: String) {
        val root = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        val image = compose.onNodeWithTag("settings-fixture").captureToImage()
        File(root, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private const val MB = 1024L * 1024
        private fun sampleState(): MemaoUiState {
            val account = Account("settings-fixture", "https://fixture.invalid", "users/1", "演示账号")
            return MemaoUiState(accounts = listOf(account), accountId = account.id, page = UiPage.SETTINGS,
                cache = CacheReport(128 * MB, 24 * MB))
        }
    }
}
