package app.shackcq.mobile.keyer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

private const val KEYER_SCHEMA = 2
private const val KEYER_STORE = "shackcq-keyer"
private const val KEYER_DOCUMENT = "keyer_document"
private const val KEYER_LAST_GOOD = "keyer_document_last_good"

class KeyerProfileStore(
    context: Context,
    cwLabels: List<String>,
    cwTexts: List<String>,
    voiceLabels: List<String>,
    legacyRepeatSeconds: Int = 10,
) {
    private val prefs = context.getSharedPreferences(KEYER_STORE, Context.MODE_PRIVATE)
    private val migrationBaseline = migrate(cwLabels, cwTexts, voiceLabels)
    private var lastGood = prefs.getString(KEYER_LAST_GOOD, null)
    var profiles by mutableStateOf(emptyList<KeyerProfile>()); private set
    var activeProfileId by mutableStateOf(KeyerProfileId("general-cw")); private set
    var hotkeysEnabled by mutableStateOf(prefs.getBoolean("hotkeys_enabled", false)); private set
    var showStrip by mutableStateOf(prefs.getBoolean("show_strip", false)); private set
    var fallbackToGeneral by mutableStateOf(prefs.getBoolean("fallback_general", false)); private set
    var repeatIntervalSeconds by mutableStateOf(prefs.getInt("repeat_interval_seconds", legacyRepeatSeconds.coerceIn(2, 600))); private set
    var repeatMaximumCycles by mutableStateOf(prefs.getInt("repeat_maximum_cycles", 10).coerceIn(1, 50)); private set
    var repeatMaximumMinutes by mutableStateOf(prefs.getInt("repeat_maximum_minutes", 10).coerceIn(1, 30)); private set
    var repeatStopsOnInput by mutableStateOf(prefs.getBoolean("repeat_stops_on_input", true)); private set
    var status by mutableStateOf(""); private set

    init {
        profiles = runCatching { prefs.getString(KEYER_DOCUMENT, null)?.let(::decode) ?: migrationBaseline }
            .getOrElse { error -> runCatching { lastGood?.let(::decode) }.getOrNull()?.also {
                status = "Keyer settings invalid; retained last-good settings"
            } ?: migrationBaseline.also { status = "Keyer settings invalid; retained migration baseline: ${error.message}" } }
        activeProfileId = profiles.firstOrNull { it.id.value == prefs.getString("active_profile", "general-cw") }?.id
            ?: profiles.first().id
        persist()
    }

    fun updateHotkeysEnabled(value: Boolean) {
        hotkeysEnabled = value
        prefs.edit().putBoolean("hotkeys_enabled", value).apply()
        if (value && !prefs.contains("show_strip")) updateShowStrip(true)
    }
    fun updateShowStrip(value: Boolean) { showStrip = value; prefs.edit().putBoolean("show_strip", value).apply() }
    fun updateFallbackToGeneral(value: Boolean) { fallbackToGeneral = value; prefs.edit().putBoolean("fallback_general", value).apply() }
    fun updateRepeatLimits(intervalSeconds: Int, maximumCycles: Int, maximumMinutes: Int) {
        repeatIntervalSeconds = intervalSeconds.coerceIn(2, 600)
        repeatMaximumCycles = maximumCycles.coerceIn(1, 50)
        repeatMaximumMinutes = maximumMinutes.coerceIn(1, 30)
        prefs.edit().putInt("repeat_interval_seconds", repeatIntervalSeconds).putInt("repeat_maximum_cycles", repeatMaximumCycles)
            .putInt("repeat_maximum_minutes", repeatMaximumMinutes).apply()
    }
    fun updateRepeatStopsOnInput(value: Boolean) { repeatStopsOnInput = value; prefs.edit().putBoolean("repeat_stops_on_input", value).apply() }
    fun activate(id: KeyerProfileId) { if (profiles.any { it.id == id }) { activeProfileId = id; prefs.edit().putString("active_profile", id.value).apply() } }
    fun activeProfile(): KeyerProfile = profiles.first { it.id == activeProfileId }

    fun bindingsForActive(): List<KeyerBinding> {
        val active = activeProfile()
        if (!fallbackToGeneral || active.role == KeyerOperatingRole.GENERAL) return active.bindings
        val general = profiles.firstOrNull { it.role == KeyerOperatingRole.GENERAL && it.mode == active.mode } ?: return active.bindings
        val overridden = active.bindings.mapTo(mutableSetOf(), KeyerBinding::chord)
        return active.bindings + general.bindings.filterNot { it.chord in overridden }
    }

    fun resolveMessage(id: String): KeyerMessageTemplate? {
        val active = activeProfile()
        return active.messages.firstOrNull { it.id == id }
            ?: if (fallbackToGeneral && active.role != KeyerOperatingRole.GENERAL)
                profiles.firstOrNull { it.role == KeyerOperatingRole.GENERAL && it.mode == active.mode }
                    ?.messages?.firstOrNull { it.id == id }
            else null
    }

    fun activeStripProfile(): KeyerProfile {
        val active = activeProfile()
        val bindings = bindingsForActive()
        val fallbackMessages = bindings.mapNotNull { it.action.messageId?.let(::resolveMessage) }
        return active.copy(messages = (active.messages + fallbackMessages).distinctBy(KeyerMessageTemplate::id), bindings = bindings)
    }

    fun renameProfile(id: KeyerProfileId, value: String): Boolean {
        val clean = value.trim().filterNot(Char::isISOControl).take(48)
        if (clean.isBlank()) return false
        val profile = profiles.firstOrNull { it.id == id } ?: return false
        return replaceProfile(profile.copy(name = clean))
    }

    fun moveProfile(id: KeyerProfileId, offset: Int): Boolean {
        val from = profiles.indexOfFirst { it.id == id }
        val to = (from + offset).coerceIn(0, profiles.lastIndex)
        if (from < 0 || from == to) return false
        profiles = profiles.toMutableList().apply { add(to, removeAt(from)) }
        persist(); return true
    }

    fun replaceProfile(profile: KeyerProfile): Boolean {
        if (profile.messages.size > 12 || duplicateChord(profile.bindings) != null) return false
        profiles = profiles.map { if (it.id == profile.id) profile else it }
        persist(); return true
    }

    fun resetProfile(id: KeyerProfileId, confirmed: Boolean): Boolean {
        if (!confirmed) return false
        val defaults = migrationBaseline.firstOrNull { it.id == id } ?: return false
        return replaceProfile(defaults)
    }

    fun importText(text: String): Boolean = runCatching {
        val decoded = decode(text)
        require(decoded.isNotEmpty())
        profiles = decoded; if (profiles.none { it.id == activeProfileId }) activeProfileId = profiles.first().id
        lastGood = text; persist(); status = "Keyer settings imported"; true
    }.getOrElse { status = "Malformed keyer settings rejected; last-good settings retained"; false }

    fun exportText(): String = encode(profiles)

    private fun persist() {
        val encoded = encode(profiles)
        if (prefs.edit().putString(KEYER_DOCUMENT, encoded).putString(KEYER_LAST_GOOD, encoded).commit()) lastGood = encoded
    }

    private fun migrate(cwLabels: List<String>, cwTexts: List<String>, voiceLabels: List<String>): List<KeyerProfile> {
        val cw = KeyerProfile(KeyerProfileId("general-cw"), "General CW", KeyerOperatingRole.GENERAL, KeyerMode.CW,
            List(6) { index -> KeyerMessageTemplate("cw-$index", cwLabels.getOrNull(index).orEmpty(), KeyerMode.CW, cwTexts.getOrNull(index).orEmpty()) })
        val voice = KeyerProfile(KeyerProfileId("general-voice"), "General Voice", KeyerOperatingRole.GENERAL, KeyerMode.VOICE,
            List(6) { index -> KeyerMessageTemplate("voice-$index", voiceLabels.getOrNull(index).orEmpty(), KeyerMode.VOICE,
                voicePlan = VoiceMacroPlan(listOf(index))) })
        fun empty(id: String, name: String, role: KeyerOperatingRole, mode: KeyerMode) = KeyerProfile(KeyerProfileId(id), name, role, mode, emptyList())
        return listOf(cw, voice,
            empty("portable-run-cw", "Portable Run", KeyerOperatingRole.PORTABLE_RUN, KeyerMode.CW),
            empty("portable-search-cw", "Portable Search", KeyerOperatingRole.PORTABLE_SEARCH, KeyerMode.CW),
            empty("contest-run-cw", "Contest Run", KeyerOperatingRole.CONTEST_RUN, KeyerMode.CW),
            empty("contest-sandp-cw", "Contest Search-and-Pounce", KeyerOperatingRole.CONTEST_S_AND_P, KeyerMode.CW))
    }

    private fun encode(items: List<KeyerProfile>): String = JSONObject().put("schema", KEYER_SCHEMA).put("profiles", JSONArray().apply {
        items.forEach { profile -> put(JSONObject()
            .put("id", profile.id.value).put("name", profile.name).put("role", profile.role.name).put("mode", profile.mode.name)
            .put("messages", JSONArray().apply { profile.messages.forEach { message -> put(JSONObject()
                .put("id", message.id).put("label", message.label).put("mode", message.mode.name).put("template", message.template)
                .put("voice_slots", JSONArray(message.voicePlan?.slotIds ?: emptyList<Int>()))
                .put("silence_ms", message.voicePlan?.interClipSilenceMillis ?: 80)) } })
            .put("bindings", JSONArray().apply { profile.bindings.forEach { binding -> put(JSONObject()
                .put("function", binding.chord.function).put("shift", binding.chord.shift).put("ctrl", binding.chord.ctrl).put("alt", binding.chord.alt)
                .put("message_id", binding.action.messageId ?: "")) } })) }
    }).toString()

    private fun decode(text: String): List<KeyerProfile> {
        val root = JSONObject(text)
        require(root.getInt("schema") in 1..KEYER_SCHEMA)
        val rows = root.getJSONArray("profiles")
        return List(rows.length()) { index -> rows.getJSONObject(index).let { profile ->
            val mode = KeyerMode.valueOf(profile.getString("mode"))
            val messagesJson = profile.getJSONArray("messages")
            val messages = List(messagesJson.length()) { messageIndex -> messagesJson.getJSONObject(messageIndex).let { message ->
                val slots = message.optJSONArray("voice_slots")?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty()
                KeyerMessageTemplate(message.getString("id"), message.getString("label"), KeyerMode.valueOf(message.getString("mode")),
                    message.optString("template"), slots.takeIf(List<Int>::isNotEmpty)?.let { VoiceMacroPlan(it, message.optInt("silence_ms", 80)) })
            } }
            val bindingsJson = profile.optJSONArray("bindings") ?: JSONArray()
            val bindings = List(bindingsJson.length()) { bindingIndex -> bindingsJson.getJSONObject(bindingIndex).let { binding ->
                KeyerBinding(KeyChord(binding.getInt("function"), binding.optBoolean("shift"), binding.optBoolean("ctrl"), binding.optBoolean("alt")),
                    KeyerAction.SendMessage(binding.getString("message_id")))
            } }
            require(messages.size <= 12 && duplicateChord(bindings) == null)
            KeyerProfile(KeyerProfileId(profile.getString("id")), profile.getString("name"), KeyerOperatingRole.valueOf(profile.getString("role")), mode, messages, bindings)
        } }
    }

    private fun duplicateChord(bindings: List<KeyerBinding>): KeyChord? = bindings.groupBy(KeyerBinding::chord).entries.firstOrNull { it.value.size > 1 }?.key
}
