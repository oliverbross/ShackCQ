package app.shackcq.mobile

data class LogbookCursor(val createdAt: Long, val qsoId: String)

data class LogbookQueryPage(
    val rows: List<Qso>,
    val exactTotal: Int?,
    val hasMore: Boolean,
    val nextCursor: LogbookCursor?,
    val queryPlan: String,
)

sealed interface LogbookQueryState {
    data object Idle : LogbookQueryState
    data object LoadingFirstPage : LogbookQueryState
    data class LoadingAnotherPage(val rows: List<Qso>) : LogbookQueryState
    data class Ready(val rows: List<Qso>, val exactTotal: Int?, val hasMore: Boolean) : LogbookQueryState
    data object Empty : LogbookQueryState
    data class ProjectionOptimising(val progress: Float) : LogbookQueryState
    data class RecoverableError(val message: String) : LogbookQueryState
    data object Cancelled : LogbookQueryState
}
