package app.shackcq.mobile

const val LOGBOOK_ROW_HEIGHT_DP = 48
const val LOGBOOK_HEADER_HEIGHT_DP = 48
const val LOGBOOK_ROW_FONT_SP = 23.4f
const val PREVIOUS_QSO_DIALOG_WIDTH_FRACTION = 0.63f
const val PREVIOUS_QSO_TITLE_FONT_SP = 30f
const val PREVIOUS_QSO_SUMMARY_FONT_SP = 22.5f
const val PREVIOUS_QSO_SECTION_FONT_SP = 19.5f
const val PREVIOUS_QSO_BODY_FONT_SP = 18f
const val PREVIOUS_QSO_MATRIX_LABEL_FONT_SP = 15f
const val PREVIOUS_QSO_MATRIX_CELL_FONT_SP = 16.5f
const val LIVE_SPOT_HEADER_FONT_SP = 13.2f
const val LIVE_SPOT_ROW_FONT_SP = 14.4f

enum class LogbookColumn(val label: String, val width: Int, val sort: LogbookSort? = null) {
    DATE_TIME("DATE / TIME", 180, LogbookSort.TIME),
    CALLSIGN("DX", 134, LogbookSort.CALLSIGN),
    MODE("MODE", 100, LogbookSort.MODE),
    RST_SENT("RST S", 64),
    RST_RECEIVED("RST R", 64),
    BAND("BAND", 88, LogbookSort.BAND),
    FREQUENCY("FREQUENCY", 140, LogbookSort.FREQUENCY),
    DXCC("DXCC / COUNTRY", 230, LogbookSort.DXCC),
    GRID("GRID", 118),
    QSL("QSL", 56),
    EQSL("eQSL", 64),
    LOTW("LoTW", 64),
    CLUBLOG("CLUBLOG", 92),
    QRZ("QRZ", 56),
    STATE("STATE", 90),
    COUNTY("COUNTY", 160),
    IOTA("IOTA", 118),
    POTA("POTA", 136),
    SOTA("SOTA", 136),
    WWFF("WWFF", 136),
    REGION("REGION", 144),
}

fun decodeLogbookColumns(serialized: String?): List<LogbookColumn> {
    if (serialized.isNullOrBlank()) return LogbookColumn.entries
    val selected = serialized.split(',').mapNotNull { token ->
        LogbookColumn.entries.firstOrNull { it.name == token.trim() }
    }.toSet()
    return LogbookColumn.entries.filter { it in selected }.ifEmpty { LogbookColumn.entries }
}

fun encodeLogbookColumns(columns: Collection<LogbookColumn>): String =
    LogbookColumn.entries.filter { it in columns }.joinToString(",") { it.name }

fun ensureDxccCountryColumn(columns: Collection<LogbookColumn>): List<LogbookColumn> =
    LogbookColumn.entries.filter { it in columns || it == LogbookColumn.DXCC }
