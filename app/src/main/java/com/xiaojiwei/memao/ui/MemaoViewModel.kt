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
    val keyword:String = "",val tag:String? = null,val selected:Memo? = null,val draftDetail:Draft? = null,
    val editor:Draft? = null,val preview:Boolean = false,val editorNew:Boolean = false,
    val paneOpen:Boolean = false,val loading:Boolean = false,val refreshing:Boolean = false,val busy:Boolean = false,
    val sending:Boolean = false,val importing:Boolean = false,val hasMore:Boolean = false,
    val offline:Boolean = false,val error:String? = null,val message:String? = null,
    val saveError:String? = null,val saving:Boolean = false,val complete:Boolean? = null,
    val files:Map<String,File> = emptyMap(),val loadingFiles:Set<String> = emptySet(),
    val conflict:SendResult.Conflict? = null,val cache:CacheReport? = null,
    val loginError:String? = null,val pendingAuthentication:Account? = null,
    val gallery:Pair<List<MediaDisplay>,Int>? = null,
) {
    val account:Account? get()=accounts.find {it.id==accountId}
}

class MemaoViewModel(
    val repository:MemaoRepository, context:Context, private val saved:SavedStateHandle,
) : ViewModel() {
    var state by mutableStateOf(MemaoUiState())
        private set
    val audio=ForegroundAudio(context.applicationContext)
    private var feedJob:Job?=null
    private var draftsJob:Job?=null
    private var autosave:Job?=null
    private var searchJob:Job?=null
    private var memoLoadJob:Job?=null
    private var memoRequestId=0L
    private var feedRequestId=0L
    private var editVersion=0L
    private val saveMutex=Mutex()
    private val mediaJobs=mutableMapOf<String,Job>()
    private var importTarget:Pair<String,String>?=null
    private val connection=context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networkCallback=object:ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network){viewModelScope.launch {state=state.copy(offline=false)}}
        override fun onLost(network:Network){viewModelScope.launch {state=state.copy(offline=connection.activeNetwork==null)}}
    }
    init {
        // A new ViewModel starts a new navigation session. Persistent drafts and filters remain;
        // configuration changes and background returns reuse this ViewModel and keep their scene.
        saved.keys().filter {it.startsWith("page:")||it.startsWith("editor:")}.forEach {saved.remove<String>(it)}
        state=state.copy(offline=connection.activeNetwork==null)
        runCatching {connection.registerDefaultNetworkCallback(networkCallback)}
        viewModelScope.launch {repository.accounts.collect {state=state.copy(accounts=it)}}
        viewModelScope.launch {repository.settings.collect {state=state.copy(settings=it)}}
        viewModelScope.launch {
            repository.activeAccountId.collectLatest {id ->
                if(id!=state.accountId) {
                    cancelMemoRequest()
                    feedRequestId++
                    searchJob?.cancel()
                    audio.stop()
                    mediaJobs.values.forEach {it.cancel()};mediaJobs.clear()
                    state=state.copy(
                        accountId=id,editor=null,draftDetail=null,selected=null,conflict=null,
                        memos=emptyList(),drafts=emptyList(),tags=emptyList(),cache=null,complete=null,
                        hasMore=false,loading=false,refreshing=false,paneOpen=false,sending=false,importing=false,saving=false,
                        files=emptyMap(),loadingFiles=emptySet(),error=null,saveError=null,message=null,
                        preview=false,editorNew=false,gallery=null,
                    )
                    if(id!=null) {
                        state=state.copy(page=UiPage.NOTES,keyword=saved["keyword:$id"]?:"",tag=saved["tag:$id"])
                        subscribe(id)
                    } else {feedJob?.cancel();draftsJob?.cancel();state=state.copy(page=UiPage.LOGIN,memos=emptyList(),drafts=emptyList(),paneOpen=false)}
                }
            }
        }
    }
    private fun query()=MemoQuery(archived=state.page==UiPage.ARCHIVE,keyword=if(state.page==UiPage.ARCHIVE)"" else state.keyword,tag=if(state.page==UiPage.ARCHIVE)null else state.tag)
    private fun subscribe(id:String) {
        draftsJob?.cancel()
        draftsJob=viewModelScope.launch {
            repository.observeDrafts(id).collect {drafts ->
                if(state.accountId!=id)return@collect
                state=state.copy(drafts=drafts,draftDetail=state.draftDetail?.let {old->drafts.find {it.id==old.id}?:old})
            }
        }
        subscribeFeed()
        refresh()
    }
    private fun subscribeFeed() {
        feedJob?.cancel()
        val id=state.accountId?:return
        val q=query()
        feedJob=viewModelScope.launch {repository.observeMemos(id,q).collect {list ->
            if(state.accountId==id&&q==query())state=state.copy(memos=list)
        }}
    }
    fun connect(server:String,token:String) {
        if(state.busy)return
        viewModelScope.launch {
            state=state.copy(busy=true,loginError=null)
            try {
                flushEditorOrThrow()
                val account=repository.connect(server,token)
                cancelMemoRequest()
                state=state.copy(pendingAuthentication=null,page=UiPage.NOTES,keyword="",tag=null,error=null,loginError=null,
                    editor=null,draftDetail=null,selected=null,paneOpen=false,conflict=null)
                if(account.id==state.accountId){subscribeFeed();refresh()}
            } catch(e:Exception) {if(e is CancellationException)throw e;state=state.copy(loginError=readable(e))}
            finally {state=state.copy(busy=false)}
        }
    }
    fun loginError(message:String){state=state.copy(loginError=message)}
    fun showLogin(account:Account?=null) {leaveEditor {
        feedRequestId++;searchJob?.cancel()
        state=state.copy(page=UiPage.LOGIN,pendingAuthentication=account,loginError=null,loading=false,refreshing=false)
    }}
    fun navigate(page:UiPage) {
        leaveEditor {
            feedRequestId++
            searchJob?.cancel()
            state=state.copy(page=page,paneOpen=false,selected=null,draftDetail=null,error=null,conflict=null,loading=false,refreshing=false)
            if(page==UiPage.NOTES||page==UiPage.ARCHIVE){subscribeFeed();refresh()}
            if(page==UiPage.SETTINGS)loadCache()
        }
    }
    fun selectAccount(account:Account) {
        leaveEditor {audio.stop();repository.selectAccount(account.id)}
    }
    fun removeAccount(account:Account) {
        viewModelScope.launch {
            try {
                if(state.accountId==account.id){cancelMemoRequest();autosave?.cancel();state=state.copy(editor=null,draftDetail=null,selected=null);audio.stop()}
                repository.removeAccount(account.id)
                state=state.copy(message="已清理该账号的本地数据，服务端数据未改变。")
            }catch(e:Exception){report(e)}
        }
    }
    fun setKeyword(value:String) {
        cancelMemoRequest()
        feedRequestId++
        state=state.copy(keyword=value,selected=null,paneOpen=false,loading=false,refreshing=false)
        state.accountId?.let {saved["keyword:$it"]=value}
        searchJob?.cancel();searchJob=viewModelScope.launch {delay(300);subscribeFeed();refresh()}
    }
    fun setTag(value:String?) {
        cancelMemoRequest()
        feedRequestId++
        searchJob?.cancel()
        state=state.copy(tag=value,selected=null,paneOpen=false,loading=false,refreshing=false)
        state.accountId?.let {saved["tag:$it"]=value}
        subscribeFeed();refresh()
    }
    fun refresh()=refresh(false)
    fun pullRefresh() {
        if(state.loading||(state.page!=UiPage.NOTES&&state.page!=UiPage.ARCHIVE))return
        searchJob?.cancel()
        subscribeFeed()
        refresh(true)
    }
    private fun refresh(showRefreshIndicator:Boolean) {
        val id=state.accountId?:return
        val q=query()
        val page=state.page
        val requestId=++feedRequestId
        fun isCurrentRequest()=requestId==feedRequestId&&state.accountId==id&&state.page==page&&q==query()
        // Set this before launching so repeated gestures cannot enqueue a second refresh.
        state=state.copy(loading=true,refreshing=showRefreshIndicator,error=null)
        viewModelScope.launch {
            try {
                val result=repository.refresh(id,q)
                if(isCurrentRequest())state=state.copy(hasMore=result.hasMore,offline=result.offline)
                if(!result.offline)runCatching {repository.tags(id)}.getOrNull()?.let {if(state.accountId==id)state=state.copy(tags=it)}
            }catch(e:Exception){if(e is CancellationException)throw e;if(isCurrentRequest())state=state.copy(error=readable(e))}
            finally {if(isCurrentRequest())state=state.copy(loading=false,refreshing=false)}
        }
    }
    fun loadMore() {
        if(state.loading||!state.hasMore)return
        val id=state.accountId?:return;val q=query()
        val page=state.page
        val requestId=++feedRequestId
        fun isCurrentRequest()=requestId==feedRequestId&&state.accountId==id&&state.page==page&&q==query()
        state=state.copy(loading=true,refreshing=false)
        viewModelScope.launch {
            try {val p=repository.loadMore(id,q);if(isCurrentRequest())state=state.copy(hasMore=p.hasMore,offline=p.offline)}
            catch(e:Exception){if(e is CancellationException)throw e;if(isCurrentRequest())report(e)}
            finally {if(isCurrentRequest())state=state.copy(loading=false)}
        }
    }
    fun openMemo(memo:Memo) {
        leaveEditor {
            val id=state.accountId?:return@leaveEditor
            state=state.copy(selected=memo,draftDetail=null,paneOpen=true,complete=null,error=null)
            repository.rememberViewed(id,memo.name)
            openMemoByName(id,memo.name)
        }
    }
    private fun cancelMemoRequest() {
        memoRequestId++
        memoLoadJob?.cancel()
        memoLoadJob=null
    }
    private fun openMemoByName(id:String,name:String) {
        cancelMemoRequest()
        val requestId=memoRequestId
        val page=state.page
        val requestedQuery=query()
        fun isCurrentRequest()=requestId==memoRequestId&&state.accountId==id&&state.page==page&&query()==requestedQuery&&
            state.selected?.name==name&&state.paneOpen&&state.editor==null&&state.draftDetail==null
        memoLoadJob=viewModelScope.launch {
            try {
                val memo=repository.openMemo(id,name)
                if(!isCurrentRequest())return@launch
                val matches=memo.archived==requestedQuery.archived&&
                    (requestedQuery.tag==null||requestedQuery.tag in memo.tags)&&memo.content.contains(requestedQuery.keyword,ignoreCase=true)
                if(!matches) {
                    state=state.copy(selected=null,paneOpen=false,complete=null,message="这条笔记不符合当前筛选。")
                    return@launch
                }
                val complete=repository.isMemoComplete(id,memo)
                if(isCurrentRequest())state=state.copy(selected=memo,paneOpen=true,complete=complete)
            }catch(e:Exception){if(e is CancellationException)throw e;if(isCurrentRequest())state=state.copy(error=readable(e))}
        }
    }
    fun showDraft(draft:Draft){leaveEditor {state=state.copy(draftDetail=draft,selected=null,paneOpen=true,conflict=null)}}
    fun openDraft(draft:Draft,newEditor:Boolean=false) {
        cancelMemoRequest()
        state=state.copy(editor=draft,draftDetail=null,selected=null,paneOpen=true,preview=false,saveError=null,editorNew=newEditor)
        editVersion++
    }
    fun newDraft() {
        leaveEditor {
            val id=state.accountId?:return@leaveEditor
            val d=repository.newDraft(id)
            state=state.copy(editor=d,draftDetail=null,selected=null,paneOpen=true,preview=false,editorNew=true,saveError=null)
            editVersion++
        }
    }
    fun editSelected() {
        val memo=state.selected?:return;val id=state.accountId?:return
        if(state.busy)return
        viewModelScope.launch {
            state=state.copy(busy=true,error=null)
            try {val draft=repository.prepareEdit(id,memo.name);if(state.accountId==id)openDraft(draft)}
            catch(e:Exception){report(e)}
            finally {if(state.accountId==id)state=state.copy(busy=false)}
        }
    }
    fun updateEditor(value:TextFieldValue) {
        val old=state.editor?:return
        if(state.preview&&old.content==value.text)return
        state=state.copy(editor=old.copy(content=value.text,cursorStart=value.selection.start,cursorEnd=value.selection.end),saveError=null)
        editVersion++;autosave?.cancel()
        autosave=viewModelScope.launch {delay(250);flushEditor()}
    }
    fun togglePreview() {state=state.copy(preview=!state.preview)}
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
    private suspend fun flushEditor():Boolean=saveMutex.withLock {
        val d=state.editor?:return@withLock true
        val version=editVersion
        state=state.copy(saving=true)
        try {
            val persisted=repository.saveDraft(d)
            if(state.editor?.id==d.id&&state.accountId==d.accountId) {
                state=state.copy(editor=if(version==editVersion)persisted else state.editor?.copy(revision=persisted.revision,updatedAt=persisted.updatedAt),saving=false,saveError=null)
            }
            true
        }catch(e:Exception){
            if(e is CancellationException)throw e
            if(e.message?.contains("正文仍引用附件")==true&&state.editor?.id==d.id) {
                runCatching {repository.getDraft(d.accountId,d.id)}.getOrNull()?.let {previous->
                    state=state.copy(editor=state.editor?.copy(attachments=previous.attachments,revision=previous.revision))
                }
            }
            state=state.copy(saving=false,saveError="本地保存失败，内容仍在当前页面。"+readable(e))
            false
        }
    }
    private suspend fun flushEditorOrThrow(){if(!flushEditor())throw IllegalStateException(state.saveError?:"本地保存失败")}
    fun retrySave(){viewModelScope.launch {flushEditor()}}
    fun flushOnStop(){if(!state.sending&&!state.importing)viewModelScope.launch {flushEditor()}}
    private fun leaveEditor(after:suspend ()->Unit) {
        if(state.sending||state.importing)return
        cancelMemoRequest()
        viewModelScope.launch {
            autosave?.cancel()
            if(!flushEditor())return@launch
            state=state.copy(editor=null,preview=false,saveError=null)
            after()
        }
    }
    fun back() {
        cancelMemoRequest()
        if(state.editor!=null)leaveEditor {state=state.copy(paneOpen=false,selected=null,draftDetail=null,conflict=null)}
        else if(state.conflict!=null)state=state.copy(conflict=null)
        else if(state.page==UiPage.LOGIN||state.page==UiPage.ACCOUNTS)navigate(if(state.accountId==null)UiPage.LOGIN else UiPage.SETTINGS)
        else state=state.copy(paneOpen=false,selected=null,draftDetail=null)
    }
    suspend fun prepareAttachmentImport():Boolean {
        val d=state.editor?:return false
        if(!flushEditor())return false
        importTarget=d.accountId to d.id
        return true
    }
    fun importFiles(uris:List<Uri>) {
        if(uris.isEmpty()){importTarget=null;return}
        val target=importTarget?:return
        importTarget=null
        viewModelScope.launch {
            state=state.copy(importing=true)
            try {
                flushEditorOrThrow()
                var imported:Draft?=null
                for(uri in uris)imported=repository.importAttachment(target.first,target.second,uri)
                imported?.let {if(state.accountId==target.first&&state.editor?.id==target.second){state=state.copy(editor=it);editVersion++}}
            }catch(e:Exception){report(e)}
            finally {state=state.copy(importing=false)}
        }
    }
    fun removeAttachment(key:String) {
        val d=state.editor?:return
        state=state.copy(editor=d.copy(attachments=d.attachments.filterNot {it.id==key}))
        editVersion++;autosave?.cancel();autosave=viewModelScope.launch {flushEditor()}
    }
    fun send(choice:ConflictChoice?=null) {
        if(state.sending||state.importing)return
        val draft=state.editor?:state.conflict?.draft?:state.draftDetail?:return
        val id=draft.accountId
        autosave?.cancel()
        viewModelScope.launch {
            state=state.copy(sending=true,error=null)
            try {
                flushEditorOrThrow()
                val result=if(choice==null)repository.send(id,draft.id) else repository.resolveConflict(id,draft.id,choice)
                if(state.accountId!=id)return@launch
                when(result) {
                    is SendResult.Sent -> {
                        if(result.newerDraftKept) {
                            val latest=runCatching {repository.getDraft(id,draft.id)}.getOrNull()
                            state=state.copy(editor=latest?:state.editor,conflict=null,message="已发送，更新的修改仍保留在草稿。")
                        } else {
                            state=state.copy(editor=null,draftDetail=null,selected=result.memo,paneOpen=true,conflict=null,message="已发送到服务器")
                        }
                        refresh()
                    }
                    is SendResult.Conflict -> state=state.copy(conflict=result)
                    is SendResult.KeptServer -> {
                        state=state.copy(conflict=null,editor=null,draftDetail=null,selected=result.server,paneOpen=result.server!=null,message="已保留服务端版本，本地草稿仍保留。")
                    }
                    is SendResult.Failed -> state=state.copy(error=result.message)
                    is SendResult.Unknown -> state=state.copy(error="发送结果尚未确定："+result.message+"。下次手动重试会先核对服务端结果。")
                }
            }catch(e:Exception){report(e)}
            finally {if(state.accountId==id)state=state.copy(sending=false)}
        }
    }
    fun cancelConflict(){state=state.copy(conflict=null)}
    fun deleteDraft(draft:Draft) {
        viewModelScope.launch {
            try {
                repository.deleteDraft(draft.accountId,draft.id)
                if(state.editor?.id==draft.id){autosave?.cancel();state=state.copy(editor=null)}
                if(state.draftDetail?.id==draft.id)state=state.copy(draftDetail=null,paneOpen=false)
                state=state.copy(message="已删除本地草稿，服务端笔记未改变。")
            }catch(e:Exception){report(e)}
        }
    }
    fun archiveMemo(memo:Memo) {
        val id=state.accountId?:return
        viewModelScope.launch {
            state=state.copy(busy=true)
            try {repository.setArchived(id,memo.name,!memo.archived);if(state.accountId==id){state=state.copy(selected=null,paneOpen=false,message=if(memo.archived)"已恢复到笔记列表" else "已归档");refresh()}}
            catch(e:Exception){report(e)}finally {if(state.accountId==id)state=state.copy(busy=false)}
        }
    }
    fun deleteMemo(memo:Memo) {
        val id=state.accountId?:return
        viewModelScope.launch {
            state=state.copy(busy=true)
            try {repository.deleteMemo(id,memo.name);if(state.accountId==id){state=state.copy(selected=null,paneOpen=false,message="已删除服务端笔记");refresh()}}
            catch(e:Exception){report(e)}finally {if(state.accountId==id)state=state.copy(busy=false)}
        }
    }
    fun setTheme(mode:ThemeMode){repository.setTheme(mode)}
    fun loadCache(){viewModelScope.launch {runCatching {repository.cacheReport()}.onSuccess {state=state.copy(cache=it)}.onFailure {report(it)}}}
    fun clearCache(){viewModelScope.launch {try {val report=repository.clearOrdinaryCache();state=state.copy(cache=report,files=emptyMap(),complete=null,message="已清理普通缓存，未发送草稿及附件完整保留。");state.selected?.let {memo->state=state.copy(complete=repository.isMemoComplete(state.accountId?:return@launch,memo))}}catch(e:Exception){report(e)}}}
    fun media(attachment:Attachment):MediaDisplay {
        return MediaDisplay(attachment.name,attachment.filename,attachment.type,state.files[attachment.name],attachment.width,attachment.height,attachment.name in state.loadingFiles)
    }
    fun media(attachment:DraftAttachment)=MediaDisplay(attachment.id,attachment.filename,attachment.type,File(attachment.localPath).takeIf {it.isFile})
    fun loadMedia(item:MediaDisplay,play:Boolean=false) {
        val id=state.accountId?:return
        val jobKey="$id|${item.key}"
        if(item.file!=null){if(play)audio.toggle(item.file);return}
        if(jobKey in mediaJobs)return
        val original=(state.selected?.attachments?:emptyList()).find {it.name==item.key}
            ?:state.memos.flatMap {it.attachments}.find {it.name==item.key}?:return
        state=state.copy(loadingFiles=state.loadingFiles+item.key)
        mediaJobs[jobKey]=viewModelScope.launch {
            try {
                val file=repository.localAttachment(id,original)?:repository.ensureAttachment(id,original)
                if(state.accountId==id){state=state.copy(files=state.files+(item.key to file));if(play)audio.toggle(file);state.selected?.let {memo->state=state.copy(complete=repository.isMemoComplete(id,memo))}}
            }catch(e:Exception){if(play)report(e)}
            finally {mediaJobs.remove(jobKey);if(state.accountId==id)state=state.copy(loadingFiles=state.loadingFiles-item.key)}
        }
    }
    fun dismissMessage(){state=state.copy(message=null)}
    fun dismissError(){state=state.copy(error=null)}
    fun notify(message:String){state=state.copy(message=message)}
    fun showGallery(items:List<MediaDisplay>,index:Int){state=state.copy(gallery=items to index)}
    fun closeGallery(){state=state.copy(gallery=null)}
    fun consumeEditorFocus(id:String):Boolean {
        if(!state.editorNew||saved.get<Boolean>("focused:$id")==true)return false
        saved["focused:$id"]=true
        return true
    }
    fun saveScroll(key:String,index:Int,offset:Int){state.accountId?.let {saved["scroll:$it:$key:index"]=index;saved["scroll:$it:$key:offset"]=offset}}
    fun scroll(key:String):Pair<Int,Int>{val id=state.accountId;return (saved.get<Int>("scroll:$id:$key:index")?:0) to (saved.get<Int>("scroll:$id:$key:offset")?:0)}
    private fun report(error:Throwable){if(error is CancellationException)throw error;state=state.copy(error=readable(error))}
    private fun readable(e:Throwable)=e.message?.takeIf {it.isNotBlank()}?:"操作失败，请重试。"
    override fun onCleared(){
        runCatching {connection.unregisterNetworkCallback(networkCallback)}
        audio.close()
        CoroutineScope(Dispatchers.IO).launch {runCatching {repository.close()}}
        super.onCleared()
    }
}
