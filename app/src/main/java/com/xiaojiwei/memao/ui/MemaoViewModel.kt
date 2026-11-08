package com.xiaojiwei.memao.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.xiaojiwei.memao.domain.MemaoRepository
import com.xiaojiwei.memao.media.ForegroundAudio
import com.xiaojiwei.memao.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class UiPage(val title:String) { NOTES("笔记"), DRAFTS("草稿"), ARCHIVE("归档"), SETTINGS("设置"), ACCOUNTS("服务器账号"), LOGIN("连接服务器") }

data class MemaoUiState(
    val accounts:List<Account> = emptyList(), val accountId:String? = null,
    val settings:AppSettings = AppSettings(),val page:UiPage = UiPage.LOGIN,
    val memos:List<Memo> = emptyList(),val drafts:List<Draft> = emptyList(),val tags:List<TagCount> = emptyList(),
    val keyword:String = "",val tag:String? = null,val draftDetail:Draft? = null,
    val editor:Draft? = null,val preview:Boolean = false,val editorNew:Boolean = false,val editorUnchanged:Boolean = false,
    val paneOpen:Boolean = false,val loading:Boolean = false,val refreshing:Boolean = false,val busy:Boolean = false,
    val sending:Boolean = false,val importing:Boolean = false,val hasMore:Boolean = false,
    val offline:Boolean = false,val error:String? = null,val message:String? = null,
    val saveError:String? = null,val saving:Boolean = false,val preparingMemo:String? = null,
    val files:Map<String,File> = emptyMap(),val loadingFiles:Set<String> = emptySet(),
    val conflict:SendResult.Conflict? = null,val cache:CacheReport? = null,
    val cacheLoading:Boolean = false,val cacheClearing:Boolean = false,
    val cacheReadError:String? = null,val cacheClearError:String? = null,
    val loginError:String? = null,val pendingAuthentication:Account? = null,
    val gallery:Pair<List<MediaDisplay>,Int>? = null,
) {
    val account:Account? get()=accounts.find {it.id==accountId}
}

class MemaoViewModel(
    val repository:MemaoRepository, context:Context, private val saved:SavedStateHandle,
) : ViewModel() {
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
            ArchiveViewModel::class.java->ArchiveViewModel(repository,saved)
            DraftsViewModel::class.java->DraftsViewModel(repository,saved)
            else->error("Unknown page ViewModel")
        } as T
    })
    val notes=pageProvider[NotesViewModel::class.java]
    val archive=pageProvider[ArchiveViewModel::class.java]
    val drafts=pageProvider[DraftsViewModel::class.java]
    private val currentList:MemoListViewModel?
        get()=when(shellState.page){UiPage.NOTES->notes;UiPage.ARCHIVE->archive;else->null}
    // Compatibility projection for the shared drawer/draft preview/editor. Lists themselves read
    // their page ViewModel directly; this projection owns no list data or request state.
    val state:MemaoUiState
        get() {
            val list=currentList?.state
            val localDrafts=drafts.state.drafts
            return shellState.copy(
                memos=list?.memos.orEmpty(),drafts=localDrafts,tags=notes.state.tags,
                keyword=notes.state.keyword,tag=notes.state.tag,
                loading=list?.loading?:false,refreshing=list?.refreshing?:false,hasMore=list?.hasMore?:false,
                busy=shellState.busy||shellState.preparingMemo!=null,
                editorUnchanged=shellState.editor?.let {current->editorBaseline?.let(current::hasSameContentAs)}?:false,
                offline=shellState.offline||(list?.offline?:false),error=shellState.error?:list?.error,
                draftDetail=shellState.draftDetail?.let { old->localDrafts.find {it.id==old.id}?:old },
            )
        }
    val audio=ForegroundAudio(context.applicationContext)
    private var autosave:Job?=null
    private var memoLoadJob:Job?=null
    private var memoRequestId=0L
    private var editVersion=0L
    private var editorBaseline:Draft?=null
    private var discardUnchangedEditor=false
    private val saveMutex=Mutex()
    private val editPreparationMutex=Mutex()
    private val mediaJobs=mutableMapOf<String,Job>()
    private var importTarget:Pair<String,String>?=null
    private val connection=context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networkCallback=object:ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network){viewModelScope.launch {
            shellState=shellState.copy(offline=false)
            notes.onNetworkAvailable();archive.onNetworkAvailable()
        }}
        override fun onLost(network:Network){viewModelScope.launch {shellState=shellState.copy(offline=connection.activeNetwork==null)}}
    }
    init {
        // A new ViewModel starts a new navigation session. Persistent drafts and filters remain;
        // configuration changes and background returns reuse this ViewModel and keep their scene.
        saved.keys().filter {it.startsWith("page:")||it.startsWith("editor:")}.forEach {saved.remove<String>(it)}
        shellState=shellState.copy(offline=connection.activeNetwork==null)
        runCatching {connection.registerDefaultNetworkCallback(networkCallback)}
        viewModelScope.launch {repository.accounts.collect {shellState=shellState.copy(accounts=it)}}
        viewModelScope.launch {repository.settings.collect {shellState=shellState.copy(settings=it)}}
        viewModelScope.launch {
            repository.activeAccountId.collectLatest {id ->
                if(id!=state.accountId) {
                    cancelMemoRequest()
                    cacheSettings.reset()
                    editorBaseline=null;discardUnchangedEditor=false
                    audio.stop()
                    mediaJobs.values.forEach {it.cancel()};mediaJobs.clear()
                    shellState=shellState.copy(
                        accountId=id,editor=null,draftDetail=null,conflict=null,
                        memos=emptyList(),drafts=emptyList(),tags=emptyList(),cache=null,
                        hasMore=false,loading=false,refreshing=false,paneOpen=false,busy=false,sending=false,importing=false,saving=false,
                        files=emptyMap(),loadingFiles=emptySet(),error=null,saveError=null,message=null,
                        preview=false,editorNew=false,gallery=null,
                    )
                    notes.bindAccount(id);archive.bindAccount(id);drafts.bindAccount(id)
                    if(id!=null) {
                        shellState=shellState.copy(page=UiPage.NOTES)
                        notes.ensureLoaded()
                    } else shellState=shellState.copy(page=UiPage.LOGIN,paneOpen=false)
                }
            }
        }
    }
    private fun query()=if(state.page==UiPage.ARCHIVE)archive.query else notes.query
    fun connect(server:String,token:String) {
        if(state.busy)return
        viewModelScope.launch {
            shellState=shellState.copy(busy=true,loginError=null)
            try {
                flushEditorOrThrow()
                val previousAccount=state.accountId
                val account=repository.connect(server,token)
                cancelMemoRequest()
                shellState=shellState.copy(pendingAuthentication=null,page=UiPage.NOTES,keyword="",tag=null,error=null,loginError=null,
                    editor=null,draftDetail=null,paneOpen=false,conflict=null)
                if(account.id==previousAccount&&account.id==state.accountId)notes.resetFiltersAndRefresh()
            } catch(e:Exception) {if(e is CancellationException)throw e;shellState=shellState.copy(loginError=readable(e))}
            finally {shellState=shellState.copy(busy=false)}
        }
    }
    fun loginError(message:String){shellState=shellState.copy(loginError=message)}
    fun showLogin(account:Account?=null) {leaveEditor {
        shellState=shellState.copy(page=UiPage.LOGIN,pendingAuthentication=account,loginError=null,loading=false,refreshing=false)
    }}
    fun navigate(page:UiPage) {
        leaveEditor {
            shellState=shellState.copy(page=page,paneOpen=false,draftDetail=null,error=null,conflict=null,loading=false,refreshing=false)
            currentList?.ensureLoaded()
        }
    }
    fun selectAccount(account:Account) {
        leaveEditor {audio.stop();repository.selectAccount(account.id)}
    }
    fun removeAccount(account:Account) {
        viewModelScope.launch {
            try {
                if(state.accountId==account.id){cancelMemoRequest();autosave?.cancel();shellState=shellState.copy(editor=null,draftDetail=null);audio.stop()}
                repository.removeAccount(account.id)
                shellState=shellState.copy(message="已清理该账号的本地数据，服务端数据未改变。")
            }catch(e:Exception){report(e)}
        }
    }
    fun setKeyword(value:String) {
        cancelMemoRequest()
        shellState=shellState.copy(paneOpen=false,error=null)
        notes.setKeyword(value)
    }
    fun setTag(value:String?) {
        cancelMemoRequest()
        shellState=shellState.copy(paneOpen=false,error=null)
        notes.setTag(value)
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
    fun showDraft(draft:Draft){leaveEditor {shellState=shellState.copy(draftDetail=draft,paneOpen=true,conflict=null)}}
    fun openDraft(draft:Draft,newEditor:Boolean=false) {
        cancelMemoRequest()
        editorBaseline=draft;discardUnchangedEditor=false
        shellState=shellState.copy(editor=draft,draftDetail=null,paneOpen=true,preview=false,saveError=null,editorNew=newEditor)
        editVersion++
    }
    fun newDraft() {
        leaveEditor {
            val id=state.accountId?:return@leaveEditor
            val d=repository.newDraft(id)
            editorBaseline=d;discardUnchangedEditor=false
            shellState=shellState.copy(editor=d,draftDetail=null,paneOpen=true,preview=false,editorNew=true,saveError=null)
            editVersion++
        }
    }
    fun updateEditor(value:TextFieldValue) {
        val old=state.editor?:return
        if(state.preview&&old.content==value.text)return
        shellState=shellState.copy(editor=old.copy(content=value.text,cursorStart=value.selection.start,cursorEnd=value.selection.end),saveError=null)
        editVersion++;autosave?.cancel()
        autosave=viewModelScope.launch {delay(250);flushEditor()}
    }
    fun togglePreview() {shellState=shellState.copy(preview=!state.preview)}
    fun format(prefix:String,suffix:String="") {
        val draft=state.editor?:return
        val start=draft.cursorStart.coerceIn(0,draft.content.length)
        val end=draft.cursorEnd.coerceIn(start,draft.content.length)
        val selected=draft.content.substring(start,end)
        val content=draft.content.substring(0,start)+prefix+selected+suffix+draft.content.substring(end)
        updateEditor(TextFieldValue(content,TextRange(start+prefix.length,end+prefix.length)))
    }
    fun addTag(raw:String) {
        val tag=raw.trim().removePrefix("#").replace(Regex("\\s+"),"")
        if(tag.isBlank())return
        val d=state.editor?:return
        val end=d.content.length
        val separator=if(d.content.isBlank()||d.content.endsWith("\n"))"" else "\n"
        updateEditor(TextFieldValue(d.content+separator+"#"+tag,TextRange(end+separator.length+tag.length+1)))
    }
    private suspend fun flushEditor():Boolean=saveMutex.withLock {flushEditorLocked()}
    private suspend fun flushEditorLocked():Boolean {
        val d=state.editor?:return true
        val version=editVersion
        shellState=shellState.copy(saving=true)
        try {
            val persisted=repository.saveDraft(d)
            if(state.editor?.id==d.id&&state.accountId==d.accountId) {
                shellState=shellState.copy(editor=if(version==editVersion)persisted else state.editor?.copy(revision=persisted.revision,updatedAt=persisted.updatedAt),saving=false,saveError=null)
            }
            return true
        }catch(e:Exception){
            if(e is CancellationException)throw e
            if(e.message?.contains("正文仍引用附件")==true&&state.editor?.id==d.id) {
                runCatching {repository.getDraft(d.accountId,d.id)}.getOrNull()?.let {previous->
                    shellState=shellState.copy(editor=state.editor?.copy(attachments=previous.attachments,revision=previous.revision))
                }
            }
            shellState=shellState.copy(saving=false,saveError="本地保存失败，内容仍在当前页面。"+readable(e))
            return false
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
                    val version=editVersion
                    if(!flushEditorLocked())return@withLock false
                    if(requestId!=memoRequestId||state.accountId!=accountId)return@withLock false
                    if(version!=editVersion)continue
                    val current=state.editor
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
                    if(version!=editVersion)continue
                    if(requestId!=memoRequestId||state.accountId!=accountId)return@withLock false
                    shellState=shellState.copy(editor=null,preview=false,saveError=null)
                    editorBaseline=null;discardUnchangedEditor=false
                    break
                }
                true
            }
            if(left)after()
        }
    }
    fun back() {
        cancelMemoRequest()
        if(state.editor!=null)leaveEditor {shellState=shellState.copy(paneOpen=false,draftDetail=null,conflict=null)}
        else if(state.conflict!=null)shellState=shellState.copy(conflict=null)
        else if(state.page==UiPage.LOGIN||state.page==UiPage.ACCOUNTS)navigate(if(state.accountId==null)UiPage.LOGIN else UiPage.SETTINGS)
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
                var imported:Draft?=null
                for(uri in uris)imported=repository.importAttachment(target.first,target.second,uri)
                imported?.let {if(state.accountId==target.first&&state.editor?.id==target.second){shellState=shellState.copy(editor=it);editVersion++}}
            }catch(e:Exception){report(e)}
            finally {shellState=shellState.copy(importing=false)}
        }
    }
    fun removeAttachment(key:String) {
        val d=state.editor?:return
        shellState=shellState.copy(editor=d.copy(attachments=d.attachments.filterNot {it.id==key}))
        editVersion++;autosave?.cancel();autosave=viewModelScope.launch {flushEditor()}
    }
    fun send(choice:ConflictChoice?=null) {
        if(state.sending||state.importing)return
        val draft=state.editor?:state.conflict?.draft?:state.draftDetail?:return
        // A send click, including retries/conflict choices, permanently protects this session's
        // copy. Failed or uncertain submissions must never be mistaken for an untouched edit.
        discardUnchangedEditor=false
        cancelMemoRequest()
        val id=draft.accountId
        autosave?.cancel()
        viewModelScope.launch {
            shellState=shellState.copy(sending=true,error=null)
            try {
                flushEditorOrThrow()
                val result=if(choice==null)repository.send(id,draft.id) else repository.resolveConflict(id,draft.id,choice)
                if(state.accountId!=id)return@launch
                when(result) {
                    is SendResult.Sent -> {
                        if(result.newerDraftKept) {
                            val latest=runCatching {repository.getDraft(id,draft.id)}.getOrNull()
                            shellState=shellState.copy(editor=latest?:state.editor,conflict=null,message="已发送，更新的修改仍保留在草稿。")
                        } else {
                            shellState=shellState.copy(editor=null,draftDetail=null,paneOpen=false,conflict=null,message="已发送到服务器")
                        }
                        notes.reloadTags()
                    }
                    is SendResult.Conflict -> shellState=shellState.copy(conflict=result)
                    is SendResult.KeptServer -> {
                        shellState=shellState.copy(conflict=null,editor=null,draftDetail=null,paneOpen=false,message="已保留服务端版本，本地草稿仍保留。")
                    }
                    is SendResult.Failed -> shellState=shellState.copy(error=result.message)
                    is SendResult.Unknown -> shellState=shellState.copy(error="发送结果尚未确定："+result.message+"。下次手动重试会先核对服务端结果。")
                }
            }catch(e:Exception){report(e)}
            finally {if(state.accountId==id)shellState=shellState.copy(sending=false)}
        }
    }
    fun cancelConflict(){shellState=shellState.copy(conflict=null)}
    fun deleteDraft(draft:Draft) {
        viewModelScope.launch {
            try {
                repository.deleteDraft(draft.accountId,draft.id)
                if(state.editor?.id==draft.id){autosave?.cancel();shellState=shellState.copy(editor=null)}
                if(state.draftDetail?.id==draft.id)shellState=shellState.copy(draftDetail=null,paneOpen=false)
                shellState=shellState.copy(message="已删除本地草稿，服务端笔记未改变。")
            }catch(e:Exception){report(e)}
        }
    }
    fun archiveMemo(memo:Memo) {
        mutateMemo(memo,if(memo.archived)"已恢复到笔记列表" else "已归档") {id->
            repository.setArchived(id,memo.name,!memo.archived)
        }
    }
    fun deleteMemo(memo:Memo) {
        mutateMemo(memo,"已删除服务端笔记") {id->
            repository.deleteMemo(id,memo.name)
            if(state.accountId==id) {
                notes.expandedMemos.remove(memo.name)
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
                // Row actions are available next to a wide-screen editor. Save the affected
                // draft before mutating its server note; leave unrelated editing undisturbed.
                if(editorId!=null)flushEditorOrThrow()
                action(id)
                if(state.accountId==id) {
                    // Keep any open draft, including edits typed while the request was running.
                    // A later send of a deleted/changed server memo follows the existing conflict flow.
                    shellState=shellState.copy(message=message)
                    notes.reloadTags()
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
        val original=(notes.state.memos+archive.state.memos).asSequence()
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
        CoroutineScope(Dispatchers.IO).launch {runCatching {repository.close()}}
        super.onCleared()
    }
}
