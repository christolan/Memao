package com.xiaojiwei.memao.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.media.ForegroundAudio
import com.xiaojiwei.memao.model.*
import com.xiaojiwei.memao.update.AppUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class UiPage(val title:String) { NOTES("笔记"), SEARCH("搜索"), DRAFTS("草稿"), ARCHIVE("归档"), SETTINGS("设置"), SYNC("同步管理"), ACCOUNTS("服务器账号"), LOGIN("连接服务器") }

data class MemaoUiState(
    val accounts:List<Account> = emptyList(), val accountId:String? = null,
    val settings:AppSettings = AppSettings(),val page:UiPage = UiPage.LOGIN,
    val memos:List<Memo> = emptyList(),val drafts:List<Draft> = emptyList(),val tags:List<TagCount> = emptyList(),
    val tag:String? = null,val untagged:Boolean = false,val draftDetail:Draft? = null,
    val editor:Draft? = null,val preview:Boolean = false,val editorNew:Boolean = false,val editorUnchanged:Boolean = false,
    val paneOpen:Boolean = false,val loading:Boolean = false,val refreshing:Boolean = false,val busy:Boolean = false,
    val sending:Boolean = false,val importing:Boolean = false,val hasMore:Boolean = false,
    val offline:Boolean = false,val error:String? = null,val message:String? = null,
    val saveError:String? = null,val saving:Boolean = false,val preparingMemo:String? = null,
    val files:Map<String,File> = emptyMap(),val loadingFiles:Set<String> = emptySet(),
    val cache:CacheReport? = null,
    val cacheLoading:Boolean = false,val cacheClearing:Boolean = false,
    val cacheReadError:String? = null,val cacheClearError:String? = null,
    val loginError:String? = null,val pendingAuthentication:Account? = null,
    val gallery:Pair<List<MediaDisplay>,Int>? = null,
) {
    val account:Account? get()=accounts.find {it.id==accountId}
}

class MemaoViewModel(
    val repository:MemaoRepository, context:Context, private val saved:SavedStateHandle, private val ownsRepository:Boolean=true,
) : ViewModel() {
    internal val appUpdate by lazy { AppUpdate.get(context.applicationContext) }
    private var shellState by mutableStateOf(MemaoUiState())
    private val cacheSettings=CacheSettingsController(
        scope=viewModelScope,
        readCache=repository::cacheReport,
        clearCache=repository::clearOrdinaryCache,
        onStateChanged={cache->shellState=shellState.copy(
            cache=cache.report,cacheLoading=cache.loading,cacheClearing=cache.clearing,
            cacheReadError=cache.readError,cacheClearError=cache.clearError,
        )},
        onCleared={notifyCurrentSession->shellState=shellState.copy(
            files=emptyMap(),message=if(notifyCurrentSession)"已清理普通缓存，草稿及附件完整保留。" else shellState.message,
        )},
    )
    // The shell survives configuration changes and owns the page ViewModels' lifetime.
    // Clearing its store cancels all page collectors and in-flight work before repository close.
    private val pageStore=ViewModelStore()
    private val pageProvider=ViewModelProvider(pageStore,object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T:ViewModel> create(modelClass:Class<T>):T=when(modelClass) {
            NotesViewModel::class.java->NotesViewModel(repository,saved)
            SearchViewModel::class.java->SearchViewModel(repository,saved)
            ArchiveViewModel::class.java->ArchiveViewModel(repository,saved)
            DraftsViewModel::class.java->DraftsViewModel(repository,saved)
            else->error("Unknown page ViewModel")
        } as T
    })
    val notes=pageProvider[NotesViewModel::class.java]
    val search=pageProvider[SearchViewModel::class.java]
    val archive=pageProvider[ArchiveViewModel::class.java]
    val drafts=pageProvider[DraftsViewModel::class.java]
    private val currentList:MemoListViewModel?
        get()=when(shellState.page){UiPage.NOTES->notes;UiPage.SEARCH->search;UiPage.ARCHIVE->archive;else->null}
    // Compatibility projection for the shared drawer/draft preview/editor. Lists themselves read
    // their page ViewModel directly; this projection owns no list data or request state.
    val state:MemaoUiState
        get() {
            val list=currentList?.state
            val localDrafts=drafts.state.drafts
            val editor=currentEditor()
            return shellState.copy(
                editor=editor,
                memos=list?.memos.orEmpty(),drafts=localDrafts,tags=notes.state.tags,
                tag=notes.state.tag,untagged=notes.state.untagged,
                loading=list?.loading?:false,refreshing=list?.refreshing?:false,hasMore=list?.hasMore?:false,
                busy=shellState.busy||shellState.preparingMemo!=null,
                editorUnchanged=editor?.let {current->editorBaseline?.let(current::hasSameContentAs)}?:false,
                saving=shellState.saving||(shellState.saveError==null&&editor?.let {current->
                    savedEditor?.let {persisted->
                        current.id!=persisted.id||current.accountId!=persisted.accountId||!current.hasSameContentAs(persisted)
                    }?:true
                }==true),
                offline=shellState.offline||(list?.offline?:false),error=shellState.error?:list?.error,
                draftDetail=shellState.draftDetail?.let { old->localDrafts.find {it.id==old.id}?:old },
            )
        }
    val audio=ForegroundAudio(context.applicationContext)
    internal var editorInput by mutableStateOf<TextFieldState?>(null)
        private set
    private var previewSelection by mutableStateOf<TextRange?>(null)
    private var editorInputObserver:Job?=null
    private var autosave:Job?=null
    private var memoLoadJob:Job?=null
    private var memoRequestId=0L
    private var editorBaseline:Draft?=null
    private var savedEditor:Draft?=null
    private var editorSession=0L
    private var discardUnchangedEditor=false
    private val saveMutex=Mutex()
    private val editPreparationMutex=Mutex()
    private val mediaJobs=mutableMapOf<String,Job>()
    private var importTarget:Pair<String,String>?=null
    private val connection=context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networkCallback=object:ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network){viewModelScope.launch {
            shellState=shellState.copy(offline=false)
            notes.onNetworkAvailable();search.onNetworkAvailable();archive.onNetworkAvailable()
            repository.requestSync()
        }}
        override fun onLost(network:Network){viewModelScope.launch {shellState=shellState.copy(offline=connection.activeNetwork==null)}}
    }
    init {
        // A new ViewModel starts a new navigation session. Persistent drafts and filters remain;
        // configuration changes and background returns reuse this ViewModel and keep their scene.
        saved.keys().filter {it.startsWith("page:")||it.startsWith("editor:")||it.startsWith("keyword:")}.forEach {saved.remove<String>(it)}
        shellState=shellState.copy(offline=connection.activeNetwork==null)
        runCatching {connection.registerDefaultNetworkCallback(networkCallback)}
        viewModelScope.launch {repository.accounts.collect {shellState=shellState.copy(accounts=it)}}
        viewModelScope.launch {repository.settings.collect {shellState=shellState.copy(settings=it)}}
        viewModelScope.launch {
            repository.activeAccountId.collectLatest {id ->
                if(id!=state.accountId) {
                    cancelMemoRequest()
                    cacheSettings.reset()
                    clearEditor()
                    audio.stop()
                    mediaJobs.values.forEach {it.cancel()};mediaJobs.clear()
                    shellState=shellState.copy(
                        accountId=id,editor=null,draftDetail=null,
                        memos=emptyList(),drafts=emptyList(),tags=emptyList(),cache=null,
                        hasMore=false,loading=false,refreshing=false,paneOpen=false,busy=false,sending=false,importing=false,saving=false,
                        files=emptyMap(),loadingFiles=emptySet(),error=null,saveError=null,message=null,
                        preview=false,editorNew=false,gallery=null,
                    )
                    search.close()
                    notes.bindAccount(id);archive.bindAccount(id);drafts.bindAccount(id)
                    if(id!=null) {
                        shellState=shellState.copy(page=UiPage.NOTES)
                        notes.ensureLoaded()
                    } else shellState=shellState.copy(page=UiPage.LOGIN,paneOpen=false)
                }
            }
        }
    }
    private fun query()=currentList?.query?:notes.query
    fun connect(server:String,token:String) {
        if(state.busy)return
        viewModelScope.launch {
            shellState=shellState.copy(busy=true,loginError=null)
            try {
                flushEditorOrThrow()
                val previousAccount=state.accountId
                val account=repository.connect(server,token)
                repository.requestSync()
                cancelMemoRequest()
                clearEditor()
                shellState=shellState.copy(pendingAuthentication=null,page=UiPage.NOTES,tag=null,error=null,loginError=null,
                    editor=null,draftDetail=null,paneOpen=false)
                if(account.id==previousAccount&&account.id==state.accountId)notes.resetFiltersAndRefresh()
            } catch(e:Exception) {if(e is CancellationException)throw e;shellState=shellState.copy(loginError=readable(e))}
            finally {shellState=shellState.copy(busy=false)}
        }
    }
    fun loginError(message:String){shellState=shellState.copy(loginError=message)}
    fun showLogin(account:Account?=null) {leaveEditor {
        if(shellState.page==UiPage.SEARCH)search.close()
        shellState=shellState.copy(page=UiPage.LOGIN,pendingAuthentication=account,loginError=null,loading=false,refreshing=false)
    }}
    fun navigate(page:UiPage) {
        if(page==UiPage.SEARCH) {
            openSearch()
            return
        }
        leaveEditor {
            if(shellState.page==UiPage.SEARCH)search.close()
            shellState=shellState.copy(page=page,paneOpen=false,draftDetail=null,error=null,message=null,loading=false,refreshing=false)
            currentList?.ensureLoaded()
        }
    }
    fun openSearch() {
        if(shellState.page!=UiPage.NOTES)return
        val id=shellState.accountId?:return
        leaveEditor {
            search.open(id,notes.query)
            shellState=shellState.copy(page=UiPage.SEARCH,paneOpen=false,draftDetail=null,error=null)
        }
    }
    fun closeSearch() {
        if(shellState.page==UiPage.SEARCH)navigate(UiPage.NOTES)
    }
    fun selectAccount(account:Account) {
        leaveEditor {audio.stop();repository.selectAccount(account.id)}
    }
    fun removeAccount(account:Account) {
        viewModelScope.launch {
            try {
                if(state.accountId==account.id){cancelMemoRequest();clearEditor();shellState=shellState.copy(draftDetail=null);audio.stop()}
                repository.removeAccount(account.id)
                shellState=shellState.copy(message="已清理该账号的本地数据，服务端数据未改变。")
            }catch(e:Exception){report(e)}
        }
    }
    fun searchInputChanged(value:String) {
        if(shellState.page!=UiPage.SEARCH||value==search.input)return
        cancelMemoRequest()
        shellState=shellState.copy(error=null)
        search.updateInput(value)
    }
    fun submitSearch(value:String) {
        if(shellState.page!=UiPage.SEARCH||value.isBlank())return
        cancelMemoRequest()
        shellState=shellState.copy(error=null)
        search.submit(value)
    }
    fun clearSearchScope() {
        if(shellState.page!=UiPage.SEARCH)return
        cancelMemoRequest()
        shellState=shellState.copy(error=null)
        search.clearScope()
    }
    fun filterByBodyTag(value:String) {
        val selected=notes.state.tag==value&&!notes.state.untagged
        setTag(if(selected)null else value)
        navigate(UiPage.NOTES)
    }
    fun setTag(value:String?) {
        cancelMemoRequest()
        shellState=shellState.copy(paneOpen=false,error=null)
        notes.setTag(value)
    }
    fun setUntagged(value:Boolean) {
        cancelMemoRequest()
        shellState=shellState.copy(paneOpen=false,error=null)
        notes.setUntagged(value)
    }
    fun refresh(){currentList?.refresh()}
    fun pullRefresh(){currentList?.pullRefresh()}
    fun loadMore(){currentList?.loadMore()}
    /** Prepare an editable draft directly from a list row; reading never opens a separate pane. */
    fun editMemo(memo:Memo) {
        if(shellState.busy||state.sending||state.importing)return
        leaveEditor {
            val id=state.accountId?:return@leaveEditor
            val requestId=memoRequestId
            val page=state.page
            val requestedQuery=query()
            shellState=shellState.copy(preparingMemo=memo.name,draftDetail=null,paneOpen=false,error=null)
            fun isCurrentRequest()=requestId==memoRequestId&&state.accountId==id&&state.page==page&&
                query()==requestedQuery&&state.preparingMemo==memo.name&&state.editor==null
            memoLoadJob=viewModelScope.launch {
                try {
                    // Existing edits resume their saved draft. New edits fetch current content and
                    // pin/cache every attachment before the repository permits editing, including offline.
                    // A replacement request waits for the cancelled predecessor's cleanup.
                    // It must not adopt a temporary copy that the old request is about to discard.
                    editPreparationMutex.withLock {
                        val prepared=repository.prepareEditSession(id,memo.name)
                        if(isCurrentRequest()) {
                            memoLoadJob=null
                            openDraft(prepared.draft)
                            discardUnchangedEditor=prepared.created
                        } else if(prepared.created)withContext(NonCancellable) {
                            repository.discardUnchangedEdit(prepared.draft,prepared.draft)
                        }
                    }
                }catch(e:Exception) {
                    if(e is CancellationException)throw e
                    if(isCurrentRequest())shellState=shellState.copy(error=readable(e))
                }finally {
                    if(requestId==memoRequestId) {
                        memoLoadJob=null
                        shellState=shellState.copy(preparingMemo=null)
                    }
                }
            }
        }
    }
    private fun cancelMemoRequest() {
        memoRequestId++
        memoLoadJob?.cancel()
        memoLoadJob=null
        shellState=shellState.copy(preparingMemo=null)
    }
    fun showDraft(draft:Draft){leaveEditor {shellState=shellState.copy(draftDetail=draft,paneOpen=true)}}
    fun openDraft(draft:Draft,newEditor:Boolean=false) {
        cancelMemoRequest()
        autosave?.cancel()
        editorBaseline=draft;discardUnchangedEditor=false
        savedEditor=draft;editorSession++
        shellState=shellState.copy(editor=draft,draftDetail=null,paneOpen=true,preview=false,saveError=null,saving=false,editorNew=newEditor)
        observeEditorInput(draft)
    }
    fun newDraft() {
        leaveEditor {
            val id=state.accountId?:return@leaveEditor
            openDraft(repository.newDraft(id),newEditor=true)
        }
    }

    // Read directly at the point of use. The autosave observer may not have run yet when a
    // toolbar action, lifecycle event, or a previous write races with a new IME edit.
    private fun currentEditor():Draft? {
        val draft=shellState.editor?:return null
        val input=editorInput?:return draft
        val text=input.text.toString()
        val selection=previewSelection?:input.selection
        return if(draft.content==text&&draft.cursorStart==selection.start&&draft.cursorEnd==selection.end)draft
        else draft.copy(content=text,cursorStart=selection.start,cursorEnd=selection.end)
    }

    private fun observeEditorInput(draft:Draft) {
        editorInputObserver?.cancel()
        previewSelection=null
        val selection=TextRange(draft.cursorStart.coerceIn(0,draft.content.length),
            draft.cursorEnd.coerceIn(0,draft.content.length))
        val input=TextFieldState(draft.content,selection)
        editorInput=input
        editorInputObserver=viewModelScope.launch {
            snapshotFlow {input.text.toString() to input.selection}.collect {
                if(editorInput!==input)return@collect
                val previous=shellState.editor?:return@collect
                val current=currentEditor()?:return@collect
                if(previous==current)return@collect
                shellState=shellState.copy(editor=current,
                    saveError=if(previous.content==current.content)shellState.saveError else null)
                if(!shellState.sending&&!shellState.importing)scheduleEditorSave()
            }
        }
    }
    private fun scheduleEditorSave() {
        autosave?.cancel()
        autosave=viewModelScope.launch {delay(250);flushEditor()}
    }

    private fun clearEditor() {
        autosave?.cancel()
        editorInputObserver?.cancel()
        editorInput=null
        previewSelection=null
        editorBaseline=null;savedEditor=null;editorSession++;discardUnchangedEditor=false
        shellState=shellState.copy(editor=null,preview=false,saveError=null,saving=false)
    }
    fun togglePreview() {
        val input=editorInput?:return
        if(shellState.preview) {
            // Removing the text field for preview can collapse its selection on focus loss.
            previewSelection?.let {range->if(input.selection!=range)input.edit {selection=range}}
            previewSelection=null
        } else previewSelection=input.selection
        shellState=shellState.copy(preview=!shellState.preview)
    }
    fun format(prefix:String,suffix:String="") {
        editorInput?.edit {
            val previous=selection
            if(suffix.isNotEmpty())replace(previous.max,previous.max,suffix)
            replace(previous.min,previous.min,prefix)
            selection=TextRange(previous.start+prefix.length,previous.end+prefix.length)
        }
    }
    internal suspend fun cachedEditorTags(accountId: String): List<String> = try {
        repository.tags(accountId, cachedOnly = true).map { it.tag }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
    fun addTag(raw:String) {
        val tag=raw.trim().removePrefix("#").replace(Regex("\\s+"),"")
        if(tag.isBlank())return
        val input=editorInput?:return
        input.edit {
            val text=asCharSequence()
            val separator=if(text.isBlank()||text.endsWith('\n'))"" else "\n"
            append(separator+"#"+tag)
            selection=TextRange(length)
        }
        if(shellState.preview)previewSelection=input.selection
    }
    private suspend fun flushEditor():Boolean=saveMutex.withLock {flushEditorLocked()}
    private suspend fun flushEditorLocked():Boolean {
        val d=currentEditor()?:return true
        val session=editorSession
        fun sameEditor()=editorSession==session&&shellState.editor?.id==d.id&&shellState.accountId==d.accountId
        fun sameSnapshot()=sameEditor()&&currentEditor()==d
        shellState=shellState.copy(saving=true,saveError=null)
        try {
            val persisted=repository.saveDraft(d)
            if(sameEditor()) {
                savedEditor=persisted
                shellState=shellState.copy(editor=if(sameSnapshot())persisted else
                    shellState.editor?.copy(revision=persisted.revision,updatedAt=persisted.updatedAt),saveError=null)
            }
            return true
        }catch(e:Exception){
            if(e is CancellationException)throw e
            if(!sameSnapshot())return false
            if(e.message?.contains("正文仍引用附件")==true) {
                val previous=runCatching {repository.getDraft(d.accountId,d.id)}.getOrNull()
                if(!sameSnapshot())return false
                if(previous!=null) {
                    savedEditor=previous
                    shellState=shellState.copy(editor=shellState.editor?.copy(attachments=previous.attachments,revision=previous.revision))
                }
            }
            shellState=shellState.copy(saveError="本地保存失败，内容仍在当前页面。"+readable(e))
            return false
        }finally {
            if(sameEditor())shellState=shellState.copy(saving=false)
        }
    }
    private suspend fun flushEditorOrThrow(){if(!flushEditor())throw IllegalStateException(state.saveError?:"本地保存失败")}
    fun retrySave(){viewModelScope.launch {flushEditor()}}
    fun flushOnStop(){if(!state.sending&&!state.importing)viewModelScope.launch {flushEditor()}}
    private fun leaveEditor(after:suspend ()->Unit) {
        if(state.sending||state.importing)return
        cancelMemoRequest()
        val requestId=memoRequestId
        val accountId=state.accountId
        viewModelScope.launch {
            autosave?.cancel()
            val left=saveMutex.withLock {
                // Keep saving, optional cleanup and clearing the editor in one critical section:
                // a queued autosave/background flush must not recreate a just-discarded copy.
                while(true) {
                    if(requestId!=memoRequestId||state.accountId!=accountId)return@withLock false
                    if(!flushEditorLocked())return@withLock false
                    if(requestId!=memoRequestId||state.accountId!=accountId)return@withLock false
                    val current=currentEditor()
                    if(current!=null&&current!=savedEditor)continue
                    val baseline=editorBaseline
                    if(current!=null&&baseline!=null&&discardUnchangedEditor&&current.hasSameContentAs(baseline)) {
                        try {repository.discardUnchangedEdit(baseline,current)}
                        catch(e:Exception) {
                            if(e is CancellationException)throw e
                            shellState=shellState.copy(saveError="本地保存失败，内容仍在当前页面。"+readable(e))
                            return@withLock false
                        }
                    }
                    // Text entered during repository IO belongs to the user; save it before leaving.
                    if(currentEditor()!=current)continue
                    if(requestId!=memoRequestId||state.accountId!=accountId)return@withLock false
                    clearEditor()
                    break
                }
                true
            }
            if(left)after()
        }
    }
    fun back() {
        val preparing=shellState.preparingMemo!=null
        cancelMemoRequest()
        if(state.editor!=null)leaveEditor {shellState=shellState.copy(paneOpen=false,draftDetail=null)}
        else if(state.page==UiPage.SEARCH) {if(!preparing)closeSearch()}
        else if(state.page==UiPage.LOGIN||state.page==UiPage.ACCOUNTS||state.page==UiPage.SYNC)navigate(if(state.accountId==null)UiPage.LOGIN else UiPage.SETTINGS)
        else shellState=shellState.copy(paneOpen=false,draftDetail=null)
    }
    suspend fun prepareAttachmentImport():Boolean {
        val d=state.editor?:return false
        cancelMemoRequest()
        if(!flushEditor()||state.editor?.id!=d.id)return false
        importTarget=d.accountId to d.id
        return true
    }
    fun importFiles(uris:List<Uri>) {
        if(uris.isEmpty()){importTarget=null;return}
        val target=importTarget?:return
        importTarget=null
        viewModelScope.launch {
            shellState=shellState.copy(importing=true)
            try {
                flushEditorOrThrow()
                for(uri in uris) {
                    val imported=repository.importAttachment(target.first,target.second,uri)
                    if(state.accountId==target.first&&state.editor?.id==target.second) {
                        savedEditor=imported
                        shellState=shellState.copy(editor=imported)
                    }
                }
            }catch(e:Exception){report(e)}
            finally {
                shellState=shellState.copy(importing=false)
                val current=currentEditor()
                if(current!=null&&current!=savedEditor&&shellState.saveError==null)scheduleEditorSave()
            }
        }
    }
    fun removeAttachment(key:String) {
        val d=state.editor?:return
        shellState=shellState.copy(editor=d.copy(attachments=d.attachments.filterNot {it.id==key}),saveError=null)
        autosave?.cancel();autosave=viewModelScope.launch {flushEditor()}
    }
    fun saveNote() {
        if(state.sending||state.importing)return
        val draft=state.editor?:return
        discardUnchangedEditor=false
        autosave?.cancel()
        shellState=shellState.copy(sending=true,saveError=null,error=null)
        viewModelScope.launch {
            try {
                val memo=saveMutex.withLock {
                    if (!flushEditorLocked()) return@withLock null
                    repository.saveNote(draft.accountId,draft.id)
                } ?: return@launch
                if(state.accountId!=draft.accountId)return@launch
                clearEditor()
                if(shellState.page==UiPage.SEARCH)search.close()
                if(memo.archived)archive.revealMemo(memo) else notes.revealMemo(memo)
                shellState=shellState.copy(page=if(memo.archived)UiPage.ARCHIVE else UiPage.NOTES,
                    draftDetail=null,paneOpen=false,message="已保存")
            }catch(error:Exception) {
                if(error is CancellationException)throw error
                if(state.accountId==draft.accountId)shellState=shellState.copy(saveError=readable(error))
            }finally {
                if(state.accountId==draft.accountId)shellState=shellState.copy(sending=false)
            }
        }
    }

    fun deleteDraft(draft:Draft) {
        viewModelScope.launch {
            try {
                repository.deleteDraft(draft.accountId,draft.id)
                if(state.editor?.id==draft.id)clearEditor()
                if(state.draftDetail?.id==draft.id)shellState=shellState.copy(draftDetail=null,paneOpen=false)
                shellState=shellState.copy(message="已删除草稿")
            }catch(e:Exception){report(e)}
        }
    }
    fun archiveMemo(memo:Memo) {
        mutateMemo(memo,if(memo.archived)"已恢复到笔记列表" else "已归档") {id->
            repository.changeNote(id,memo.name,!memo.archived)
        }
    }
    fun deleteMemo(memo:Memo) {
        mutateMemo(memo,"已删除") {id->
            repository.changeNote(id,memo.name,null)
            if(state.accountId==id) {
                notes.expandedMemos.remove(memo.name)
                search.expandedMemos.remove(memo.name)
                archive.expandedMemos.remove(memo.name)
            }
        }
    }
    private fun mutateMemo(memo:Memo,message:String,action:suspend (String)->Unit) {
        if(state.busy||state.sending||state.importing)return
        val id=state.accountId?:return
        val editorId=state.editor?.takeIf {it.memoName==memo.name}?.id
        shellState=shellState.copy(busy=true,error=null)
        viewModelScope.launch {
            try {
                if(editorId!=null)flushEditorOrThrow()
                action(id)
                if(state.accountId==id) {
                    shellState=shellState.copy(message=message)
                }
            }catch(e:Exception){if(state.accountId==id)report(e)}
            finally {if(state.accountId==id)shellState=shellState.copy(busy=false)}
        }
    }
    fun setTheme(mode:ThemeMode){repository.setTheme(mode)}
    fun loadCache(){cacheSettings.load()}
    fun clearCache(){cacheSettings.clear()}
    fun media(attachment:Attachment):MediaDisplay {
        return MediaDisplay(attachment.name,attachment.filename,attachment.type,state.files[attachment.name],attachment.width,attachment.height,attachment.name in state.loadingFiles)
    }
    fun media(attachment:DraftAttachment)=MediaDisplay(attachment.id,attachment.filename,attachment.type,File(attachment.localPath).takeIf {it.isFile})
    fun loadMedia(item:MediaDisplay,play:Boolean=false) {
        val id=state.accountId?:return
        val jobKey="$id|${item.key}"
        if(item.file!=null){if(play)audio.toggle(item.file);return}
        if(jobKey in mediaJobs)return
        val original=sequenceOf(notes.state.memos,search.state.memos,archive.state.memos).flatten()
            .flatMap {it.attachments.asSequence()}.find {it.name==item.key}?:return
        shellState=shellState.copy(loadingFiles=state.loadingFiles+item.key)
        mediaJobs[jobKey]=viewModelScope.launch {
            try {
                val file=repository.localAttachment(id,original)?:repository.ensureAttachment(id,original)
                if(state.accountId==id){shellState=shellState.copy(files=state.files+(item.key to file));if(play)audio.toggle(file)}
            }catch(e:Exception){if(play)report(e)}
            finally {mediaJobs.remove(jobKey);if(state.accountId==id)shellState=shellState.copy(loadingFiles=state.loadingFiles-item.key)}
        }
    }
    fun dismissMessage(){shellState=shellState.copy(message=null)}
    fun dismissError(){shellState=shellState.copy(error=null);currentList?.dismissError()}
    fun notify(message:String){shellState=shellState.copy(message=message)}
    fun showGallery(items:List<MediaDisplay>,index:Int){shellState=shellState.copy(gallery=items to index)}
    fun closeGallery(){shellState=shellState.copy(gallery=null)}
    fun consumeEditorFocus(id:String):Boolean {
        if(!state.editorNew||saved.get<Boolean>("focused:$id")==true)return false
        saved["focused:$id"]=true
        return true
    }
    fun saveScroll(key:String,index:Int,offset:Int){state.accountId?.let {saved["scroll:$it:$key:index"]=index;saved["scroll:$it:$key:offset"]=offset}}
    fun scroll(key:String):Pair<Int,Int>{val id=state.accountId;return (saved.get<Int>("scroll:$id:$key:index")?:0) to (saved.get<Int>("scroll:$id:$key:offset")?:0)}
    private fun report(error:Throwable){if(error is CancellationException)throw error;shellState=shellState.copy(error=readable(error))}
    private fun readable(e:Throwable)=e.message?.takeIf {it.isNotBlank()}?:"操作失败，请重试。"
    override fun onCleared(){
        cacheSettings.close()
        runCatching {connection.unregisterNetworkCallback(networkCallback)}
        pageStore.clear()
        audio.close()
        if(ownsRepository)CoroutineScope(Dispatchers.IO).launch {runCatching {repository.close()}}
        super.onCleared()
    }
}
