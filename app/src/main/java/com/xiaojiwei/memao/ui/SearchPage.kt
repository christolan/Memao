package com.xiaojiwei.memao.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors

@Composable
fun SearchPage(
    vm: MemaoViewModel,
    page: SearchViewModel,
    audio: AudioState,
    onGallery: (List<MediaDisplay>, Int) -> Unit,
    compact: Boolean,
) {
    val colors = LocalMemaoColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val state = page.state
    val scopeLabel = if (state.untagged) "无标签" else state.tag?.let { "#$it" }

    fun submit(keyword: String) {
        if (keyword.isBlank()) return
        keyboard?.hide()
        focusManager.clearFocus()
        vm.submitSearch(keyword)
    }

    LaunchedEffect(page) {
        if (page.input.isBlank()) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Column(Modifier.fillMaxSize().imePadding().testTag("searchPage")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MmField(
                value = page.input,
                onValueChange = vm::searchInputChanged,
                label = "",
                modifier = Modifier.weight(1f),
                placeholder = "搜索笔记",
                inputTag = "search",
                inputModifier = Modifier.focusRequester(focusRequester).semantics { contentDescription = "搜索关键词" },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(page.input) }),
                leading = {
                    MmIconButton(MmIcon.SEARCH, "执行搜索", enabled = page.input.isNotBlank()) { submit(page.input) }
                },
                trailing = {
                    if (page.input.isNotEmpty()) {
                        MmIconButton(MmIcon.CLOSE, "清除关键词") {
                            vm.searchInputChanged("")
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    }
                },
            )
            MmButton("取消", {
                keyboard?.hide()
                focusManager.clearFocus()
                vm.closeSearch()
            }, Modifier.testTag("cancelSearch"))
        }
        scopeLabel?.let {
            Text(
                "搜索范围 · $it",
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                color = colors.secondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MmRule()
        when {
            page.input.isBlank() -> RecentSearches(page.recent, ::submit)
            state.keyword == page.input -> SearchResults(vm, page, audio, onGallery, compact)
        }
    }
}

@Composable
private fun RecentSearches(recent: List<String>, onSearch: (String) -> Unit) {
    val colors = LocalMemaoColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        item {
            Text("最近搜索", Modifier.padding(bottom = 8.dp), color = colors.secondary, style = MaterialTheme.typography.labelMedium)
        }
        if (recent.isEmpty()) {
            item {
                Text("暂无搜索记录", Modifier.padding(vertical = 8.dp), color = colors.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(recent, key = { it }) { keyword ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onSearch(keyword) }
                    .heightIn(min = 48.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LineIcon(MmIcon.SEARCH, tint = colors.secondary)
                Text(keyword, color = colors.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            MmRule()
        }
    }
}

@Composable
private fun SearchResults(
    vm: MemaoViewModel,
    page: SearchViewModel,
    audio: AudioState,
    onGallery: (List<MediaDisplay>, Int) -> Unit,
    compact: Boolean,
) {
    val colors = LocalMemaoColors.current
    val state = page.state
    val shared = vm.state
    val offline = state.offline || shared.offline
    val error = state.error ?: shared.error
    val refreshState = rememberPullToRefreshState()
    Box(Modifier.fillMaxSize().clipToBounds().pullToRefresh(
        state = refreshState,
        isRefreshing = state.refreshing,
        enabled = !state.loading,
        onRefresh = page::pullRefresh,
    )) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("searchResults").semantics {
                if (!state.loading) customActions = listOf(CustomAccessibilityAction("刷新搜索结果") {
                    page.pullRefresh()
                    true
                })
            },
            state = page.listState,
            contentPadding = PaddingValues(horizontal = if (compact) 20.dp else 24.dp, vertical = 16.dp),
        ) {
            if (offline) {
                item(key = "offline") {
                    Text("当前只搜索已缓存的内容，联网后可以查找更多。", color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let {
                item(key = "error") {
                    StateBanner(it, error = true, action = {
                        MmButton("重试", { vm.dismissError(); page.refresh() })
                    })
                }
            }
            if (state.memos.isEmpty() && error == null) {
                if (!state.refreshing && (state.loading || !state.cacheReady)) {
                    items(3) { SkeletonRow() }
                } else {
                    item(key = "empty") { EmptyFeed("没有找到记录", "试试其他关键词。", MmIcon.SEARCH) }
                }
            }
            items(state.memos, key = { it.name }) { memo ->
                MemoRow(
                    memo = memo,
                    selected = shared.editor?.memoName == memo.name,
                    audio = audio,
                    vm = vm,
                    onGallery = onGallery,
                    compact = compact,
                    expanded = page.expandedMemos[memo.name] == true,
                    onToggle = {
                        if (page.expandedMemos[memo.name] == true) page.expandedMemos.remove(memo.name)
                        else page.expandedMemos[memo.name] = true
                    },
                )
            }
            if (state.hasMore && !offline) {
                item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                        MmButton(if (state.loading && !state.refreshing) "正在加载…" else "加载更多", page::loadMore, enabled = !state.loading)
                    }
                }
            }
        }
        PullToRefreshDefaults.Indicator(
            state = refreshState,
            isRefreshing = state.refreshing,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = colors.background,
            color = colors.ink,
        )
    }
}
