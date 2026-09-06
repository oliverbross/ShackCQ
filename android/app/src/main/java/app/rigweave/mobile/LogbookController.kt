package app.rigweave.mobile

import android.os.CancellationSignal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class LogbookController(private val repository: LogbookRepository) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val generation=AtomicLong(0)
    private var job:Job?=null
    private var signal:CancellationSignal?=null
    private var cursor:LogbookCursor?=null
    private val pages=mutableListOf<List<Qso>>()
    private val cursors=mutableListOf<LogbookCursor?>()
    private var exactTotal:Int?=null
    private var more=false
    var state by mutableStateOf<LogbookQueryState>(LogbookQueryState.Idle);private set
    var appliedFilter by mutableStateOf(LogbookFilter(limit=50));private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet());private set
    var stationId:String?=null;private set
    var pageSize by mutableStateOf(50);private set
    var pageIndex by mutableStateOf(0);private set
    var refreshing by mutableStateOf(false);private set
    var refreshError by mutableStateOf("");private set

    fun apply(filter:LogbookFilter,station:String?=stationId){
        val normalised=filter.copy(limit=filter.limit.coerceIn(1,250))
        if(normalised==appliedFilter&&station==stationId&&state !is LogbookQueryState.Idle)return
        appliedFilter=normalised;stationId=station;pageSize=appliedFilter.limit;load(reset=true,debounceMs=250)
    }
    fun refresh()=load(reset=true,preserveRows=true)
    fun retry()=load(reset=true)
    fun reset(){selectedIds=emptySet();apply(LogbookFilter(limit=50),stationId)}
    fun loadNext(){val ready=state as? LogbookQueryState.Ready?:return;if(!ready.hasMore)return;load(reset=false)}
    fun loadPrevious(){if(pageIndex<=0)return;pageIndex--;cursor=cursors.getOrNull(pageIndex);state=LogbookQueryState.Ready(pages[pageIndex],exactTotal,true)}
    fun toggleSelection(id:String){selectedIds=if(id in selectedIds)selectedIds-id else selectedIds+id}
    fun clearSelection(){selectedIds=emptySet()}

    private fun load(reset:Boolean,debounceMs:Long=0,preserveRows:Boolean=false){
        val request=generation.incrementAndGet();signal?.cancel();job?.cancel();signal=CancellationSignal()
        val existing=(state as? LogbookQueryState.Ready)?.rows.orEmpty()
        val preserving=reset&&preserveRows&&existing.isNotEmpty()
        refreshError="";refreshing=preserving
        if(reset){cursor=null;pageIndex=0;if(!preserving){pages.clear();cursors.clear();exactTotal=null;state=LogbookQueryState.LoadingFirstPage}}
        else state=LogbookQueryState.LoadingAnotherPage(existing)
        job=scope.launch{
            try{
                if(debounceMs>0)delay(debounceMs)
                var health=withContext(Dispatchers.IO){repository.health()}
                while(health.state!=ProjectionState.READY){
                    if(request!=generation.get())return@launch
                    state=LogbookQueryState.ProjectionOptimising(health.progress)
                    delay(250)
                    health=withContext(Dispatchers.IO){repository.health()}
                }
                val page=withContext(Dispatchers.IO){repository.page(appliedFilter,stationId,pageSize,if(reset)null else cursor,
                    offsetPage=if(reset)0 else pageIndex+1,exactCount=false,signal=signal)}
                if(request!=generation.get())return@launch
                cursor=page.nextCursor
                if(reset){pages.clear();cursors.clear();pages+=page.rows;cursors+=page.nextCursor}else{pageIndex++;if(pages.size>pageIndex)pages[pageIndex]=page.rows else pages+=page.rows;if(cursors.size>pageIndex)cursors[pageIndex]=page.nextCursor else cursors+=page.nextCursor}
                exactTotal=page.exactTotal?:exactTotal;more=page.hasMore
                state=if(page.rows.isEmpty())LogbookQueryState.Empty else LogbookQueryState.Ready(page.rows,exactTotal,page.hasMore)
                if(reset&&page.rows.isNotEmpty()){
                    val total=withContext(Dispatchers.IO){repository.count(appliedFilter,stationId,signal)}
                    if(request==generation.get()){
                        exactTotal=total
                        state=LogbookQueryState.Ready(page.rows,total,page.hasMore)
                    }
                }
            }catch(_:CancellationException){if(request==generation.get()&&!preserving)state=LogbookQueryState.Cancelled}
            catch(error:Throwable){if(request==generation.get()){if(preserving){refreshError=error.message?:"Logbook refresh failed";state=LogbookQueryState.Ready(existing,exactTotal,more)}else state=LogbookQueryState.RecoverableError(error.message?:"Logbook query failed")}}
            finally{if(request==generation.get())refreshing=false}
        }
    }

    fun close(){signal?.cancel();scope.cancel()}
}
