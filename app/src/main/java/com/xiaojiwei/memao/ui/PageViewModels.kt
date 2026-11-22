package com.xiaojiwei.memao.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaojiwei.memao.domain.DomainRules
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.model.Draft
import com.xiaojiwei.memao.model.Memo
import com.xiaojiwei.memao.model.MemoQuery
import com.xiaojiwei.memao.model.TagCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A page owns its viewport even while its composable is not on screen. */
abstract class ListPageViewModel(private val saved: SavedStateHandle) : ViewModel() {
    var accountId: String? by mutableStateOf(null)
        protected set
    abstract val scrollKey: String
    protected open val persistScroll = true
    private var viewport: Pair<String, LazyListState>? = null
    val listState: LazyListState
        get() {
            viewport?.takeIf { it.first == scrollKey }?.let { return it.second }
            saveViewport()
            return LazyListState(
                if (persistScroll) saved["scroll:$accountId:$scrollKey:index"] ?: 0 else 0,
                if (persistScroll) saved["scroll:$accountId:$scrollKey:offset"] ?: 0 else 0,
            ).also { viewport = scrollKey to it }
        }

    protected fun resetAccount(id: String?) {
        saveViewport()
        accountId = id
        viewport = null
    }

    private fun saveViewport() {
        viewport?.let { (key, state) ->
            saveScroll(accountId, key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }

    protected fun resetScroll() {
        viewport = null
    }

    // The owner and key are captured by the screen; a disposed old screen must not write
    // its final scroll emission into the newly selected account or query.
    fun saveScroll(owner: String?, key: String, index: Int, offset: Int) {
        if (!persistScroll || owner == null || owner != accountId) return
        saved["scroll:$owner:$key:index"] = index
        saved["scroll:$owner:$key:offset"] = offset
    }
}

data class MemoListState(
    val memos: List<Memo> = emptyList(),
    val cacheReady: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    val keyword: String = "",
    val tag: String? = null,
    val untagged: Boolean = false,
    val tags: List<TagCount> = emptyList(),
    val memoToReveal: String? = null,
)

abstract class MemoListViewModel(
    private val repository: MemaoRepository,
    private val saved: SavedStateHandle,
    val page: UiPage,
) : ListPageViewModel(saved) {
    var state by mutableStateOf(MemoListState())
        protected set
    // Reading state belongs to this page session: it survives navigation and resizing,
    // but a new app session or account switch starts with every memo collapsed.
    val expandedMemos = mutableStateMapOf<String, Boolean>()
    val query: MemoQuery
        get() = MemoQuery(archived = page == UiPage.ARCHIVE, keyword = state.keyword, tag = state.tag, untagged = state.untagged)
    override val scrollKey: String get() = "feed:$page:${state.keyword}:${state.tag}:${state.untagged}"
    private var observed: Job? = null
    private var request: Job? = null
    private var generation = 0L
    private var entered = false

    fun bindAccount(id: String?) {
        if (id == accountId) return
        val untagged = page == UiPage.NOTES && id != null && saved.get<Boolean>("untagged:$id") == true
        resetList(id, MemoListState(
            tag = if (page == UiPage.NOTES && id != null && !untagged) saved["tag:$id"] else null,
            untagged = untagged,
        ))
        observeQuery()
    }

    protected fun resetList(id: String?, initial: MemoListState = MemoListState()) {
        generation++
        observed?.cancel(); request?.cancel()
        resetAccount(id)
        entered = false
        expandedMemos.clear()
        state = initial
    }

    /** First entry loads; subsequent navigation only reveals the retained page. */
    fun ensureLoaded() {
        if (accountId == null || entered) return
        entered = true
        refresh()
    }

    private fun observeQuery() {
        observed?.cancel()
        val id = accountId ?: return
        val q = query
        observed = viewModelScope.launch {
            repository.observeMemos(id, q).collect { memos ->
                if (accountId == id && query == q) state = state.copy(memos = memos, cacheReady = true)
            }
        }
    }

    fun setTag(value: String?) {
        if (page != UiPage.NOTES || (value == state.tag && !state.untagged)) return
        changeQuery("", value, untagged = false)
    }

    fun setUntagged(value: Boolean) {
        if (page != UiPage.NOTES || (value == state.untagged && state.tag == null)) return
        changeQuery("", null, untagged = value)
    }

    fun resetFiltersAndRefresh() {
        if (page == UiPage.NOTES) changeQuery("", null, untagged = false)
    }

    protected fun changeQuery(keyword: String, tag: String?, untagged: Boolean, fetchResults: Boolean = true) {
        generation++
        request?.cancel(); observed?.cancel()
        state = state.copy(keyword = keyword, tag = tag, untagged = untagged, memos = emptyList(), cacheReady = false, loading = false,
            refreshing = false, hasMore = false, error = null, memoToReveal = null)
        if (page == UiPage.NOTES) {
            accountId?.let { saved["tag:$it"] = tag; saved["untagged:$it"] = untagged }
        }
        if (fetchResults) {
            observeQuery()
            refresh()
        }
    }

    fun refresh() = fetch(more = false, indicator = false)
    fun pullRefresh() {
        if (state.loading) return
        fetch(more = false, indicator = true)
    }
    fun loadMore() {
        if (state.loading || !state.hasMore) return
        fetch(more = true, indicator = false)
    }

    private fun fetch(more: Boolean, indicator: Boolean) {
        val id = accountId ?: return
        val q = query
        if (page == UiPage.SEARCH && q.keyword.isEmpty()) return
        val requestId = ++generation
        request?.cancel()
        entered = true
        state = state.copy(loading = true, refreshing = indicator, error = null)
        fun current() = requestId == generation && accountId == id && query == q
        request = viewModelScope.launch {
            try {
                val result = if (more) repository.loadMore(id, q) else repository.refresh(id, q)
                if (current()) state = state.copy(hasMore = result.hasMore, offline = result.offline)
                if (page == UiPage.NOTES && !result.offline && current()) {
                    val tags = repository.tags(id)
                    if (current()) state = state.copy(tags = tags)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current()) state = state.copy(error = error.message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试。")
            } finally {
                if (current()) state = state.copy(loading = false, refreshing = false)
            }
        }
    }

    fun reloadTags() {
        if (page != UiPage.NOTES) return
        val id = accountId ?: return
        viewModelScope.launch {
            try {
                val tags = repository.tags(id)
                if (accountId == id) state = state.copy(tags = tags)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }

    fun completeMemoReveal(name: String) {
        if (state.memoToReveal == name) state = state.copy(memoToReveal = null)
    }

    fun dismissError() { state = state.copy(error = null) }
    fun onNetworkAvailable() { state = state.copy(offline = false) }
}

class NotesViewModel(repository: MemaoRepository, saved: SavedStateHandle) :
    MemoListViewModel(repository, saved, UiPage.NOTES) {
    fun revealPublishedMemo(memo: Memo) {
        if (!DomainRules.matches(memo, query)) setTag(null)
        state = state.copy(memoToReveal = memo.name)
    }
}

class ArchiveViewModel(repository: MemaoRepository, saved: SavedStateHandle) :
    MemoListViewModel(repository, saved, UiPage.ARCHIVE)

class SearchViewModel(private val repository: MemaoRepository, saved: SavedStateHandle) :
    MemoListViewModel(repository, saved, UiPage.SEARCH) {
    override val persistScroll = false
    var input by mutableStateOf("")
        private set
    var recent by mutableStateOf(emptyList<String>())
        private set

    fun open(id: String, notesQuery: MemoQuery) {
        resetList(id, MemoListState(tag = notesQuery.tag, untagged = notesQuery.untagged))
        input = ""
        recent = repository.recentSearches(id)
    }

    fun close() {
        resetList(null)
        input = ""
        recent = emptyList()
    }

    fun updateInput(value: String) {
        if (value == input) return
        input = value
        if (state.keyword.isNotEmpty()) {
            resetScroll()
            expandedMemos.clear()
            changeQuery("", state.tag, state.untagged, fetchResults = false)
        }
    }

    fun submit(value: String = input) {
        val id = accountId ?: return
        if (value.isBlank()) return
        updateInput(value)
        recent = repository.rememberSearch(id, value)
        if (value == state.keyword) refresh()
        else changeQuery(value, state.tag, state.untagged)
    }

    fun clearScope() {
        val id = accountId ?: return
        if (state.tag == null && !state.untagged) return
        resetScroll()
        expandedMemos.clear()
        val keyword = input.takeIf { it.isNotBlank() }.orEmpty()
        if (keyword.isNotEmpty()) recent = repository.rememberSearch(id, keyword)
        changeQuery(keyword, null, untagged = false, fetchResults = keyword.isNotEmpty())
    }
}

data class DraftListState(val drafts: List<Draft> = emptyList(), val cacheReady: Boolean = false)

class DraftsViewModel(private val repository: MemaoRepository, saved: SavedStateHandle) : ListPageViewModel(saved) {
    var state by mutableStateOf(DraftListState())
        private set
    override val scrollKey = "feed:${UiPage.DRAFTS}::null"
    private var observed: Job? = null

    fun bindAccount(id: String?) {
        if (id == accountId) return
        observed?.cancel()
        resetAccount(id)
        state = DraftListState()
        if (id != null) observed = viewModelScope.launch {
            repository.observeDrafts(id).collect { drafts ->
                if (accountId == id) state = DraftListState(drafts, cacheReady = true)
            }
        }
    }
}
