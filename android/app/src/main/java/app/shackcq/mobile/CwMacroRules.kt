package app.shackcq.mobile

const val CW_MACRO_COUNT = 6
const val CW_MACRO_LABEL_MAX = 11
const val CW_MACRO_TEXT_MAX = 24
const val CW_MACRO_TEMPLATE_MAX = 160
const val CQ_REPEAT_MIN_SECONDS = 1
const val CQ_REPEAT_MAX_SECONDS = 5

private val defaultCwMacroLabels = listOf("CQ", "EXCH", "TU", "", "", "")
private const val cwMacroSafeCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,?/=+-@"
private const val cwMacroTemplateCharacters = cwMacroSafeCharacters + "{}<>_"

data class CwMacroContext(
    val myCall: String = "", val call: String = "", val name: String = "", val qth: String = "",
    val rst: String = "", val rstSent: String = "", val rstRecv: String = "", val grid: String = "",
    val myGrid: String = "", val serial: String = "", val exchange: String = "", val reference: String = "",
    val mode: String = "", val band: String = "",
)

data class CwMacroResolution(val text: String = "", val error: String? = null)

fun defaultCwMacroLabel(index: Int): String = defaultCwMacroLabels.getOrNull(index).orEmpty()

fun sanitizeCwMacroLabel(value: String): String = value.uppercase()
    .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '/' }
    .take(CW_MACRO_LABEL_MAX)

fun sanitizeCwMacroText(value: String): String = value.substringBefore(';').substringBefore('\n').substringBefore('\r').uppercase()
    .filter { it in cwMacroSafeCharacters }
    .take(CW_MACRO_TEXT_MAX)

fun sanitizeCwMacroTemplate(value: String): String = value.substringBefore(';').substringBefore('\n').substringBefore('\r').uppercase()
    .filter { it in cwMacroTemplateCharacters }
    .take(CW_MACRO_TEMPLATE_MAX)

fun resolveCwMacroTemplate(template: String, context: CwMacroContext): CwMacroResolution {
    if (template.length > CW_MACRO_TEMPLATE_MAX || template.any { character ->
            character == ';' || character == '\n' || character == '\r' || character.uppercaseChar() !in cwMacroTemplateCharacters
        }) return CwMacroResolution(error = "Template contains unsupported characters or exceeds $CW_MACRO_TEMPLATE_MAX characters")
    val values = mapOf(
        "MYCALL" to context.myCall, "CALL" to context.call, "NAME" to context.name, "QTH" to context.qth,
        "RST" to context.rst, "RST_SENT" to context.rstSent, "RST_RECV" to context.rstRecv,
        "GRID" to context.grid, "MYGRID" to context.myGrid, "SERIAL" to context.serial,
        "EXCHANGE" to context.exchange, "REFERENCE" to context.reference, "MODE" to context.mode, "BAND" to context.band,
    )
    val aliases = mapOf("HIS_CALL" to "CALL", "DX_CALL" to "CALL", "SENT_RST" to "RST_SENT",
        "RECEIVED_RST" to "RST_RECV", "RCVD_RST" to "RST_RECV", "MY_GRID" to "MYGRID")
    val token = Regex("\\{([A-Z_ ]+)(\\?)?}|<([A-Z_ ]+)(\\?)?>", RegexOption.IGNORE_CASE)
    var error: String? = null
    val expanded = token.replace(sanitizeCwMacroTemplate(template)) { match ->
        val rawName = match.groupValues[1].ifBlank { match.groupValues[3] }
        val optional = match.groupValues[2] == "?" || match.groupValues[4] == "?"
        val normalized = rawName.trim().uppercase().replace(Regex("\\s+"), "_")
        val name = aliases[normalized] ?: normalized
        val value = values[name]
        when {
            value == null -> { error = "Unknown macro field $rawName"; "" }
            value.isBlank() && !optional -> { error = "Macro field $rawName is empty"; "" }
            else -> value
        }
    }
    error?.let { return CwMacroResolution(error = it) }
    if (Regex("[{}<>]").containsMatchIn(expanded)) return CwMacroResolution(error = "Unresolved macro field")
    val normalized = expanded.trim().replace(Regex("\\s+"), " ").uppercase()
    val text = sanitizeCwMacroText(normalized)
    if (text.isBlank()) return CwMacroResolution(error = "Resolved CW message is blank")
    if (text != normalized || normalized.length > CW_MACRO_TEXT_MAX) return CwMacroResolution(
        error = "Expanded message must fit the verified $CW_MACRO_TEXT_MAX character KY limit")
    return CwMacroResolution(text = text)
}

fun isCwMacroMode(mode: String): Boolean = mode.trim().uppercase().replace('_', '-') in setOf("CW", "CW-R", "CWR")

fun cwMacroCommand(text: String): String? = sanitizeCwMacroText(text).takeIf(String::isNotBlank)?.let { "KY $it;" }
