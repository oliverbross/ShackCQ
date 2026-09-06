package app.shackcq.mobile

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class EqProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("shackcq-eq-profiles", Context.MODE_PRIVATE)
    val profiles = mutableStateListOf<EqProfile>()

    init { refresh() }

    fun refresh() {
        profiles.clear(); profiles += builtIns(); profiles += loadUserProfiles()
    }

    fun save(profile: EqProfile) {
        val users = loadUserProfiles().toMutableList()
        val index = users.indexOfFirst { it.id == profile.id }
        if (index >= 0) users[index] = profile.copy(updatedAt = Instant.now()) else users += profile
        persist(users); refresh()
    }

    fun create(name: String, snapshot: EqSnapshot, curve: EqCurve, intent: EqIntent?, audioChain: String, notes: String): EqProfile {
        require(name.trim().isNotEmpty()) { "Profile name is required" }
        return EqProfile(UUID.randomUUID().toString(), name.trim(), snapshot.path, snapshot.context, curve,
            audioChain.trim(), intent, notes.trim(), radioModel = snapshot.model, firmware = snapshot.firmware,
            inputDevice = audioChain.trim()).also(::save)
    }

    fun delete(id: String) { persist(loadUserProfiles().filterNot { it.id == id }); refresh() }

    private fun builtIns() = listOf(
        EqProfile("builtin-flat-rx", "Flat — RX", EqPath.RX, EqContext.RX_VOICE, EqCurve.FLAT, notes = "Local flat starting point"),
        EqProfile("builtin-flat-tx", "Flat — TX", EqPath.TX, EqContext.TX_SSB, EqCurve.FLAT, notes = "Local flat starting point"),
        EqProfile("builtin-clear-ssb", "Conservative clear SSB", EqPath.TX, EqContext.TX_SSB,
            EqCurve.of(listOf(-4, -3, -1, 0, 1, 2, 2, 1)), intent = EqIntent.CLEAR_SSB,
            notes = "Generic starting example; judge by matched A/B and transmitted audio"),
    )

    private fun loadUserProfiles(): List<EqProfile> = runCatching {
        val rows = JSONArray(prefs.getString("profiles", "[]"))
        List(rows.length()) { index -> decode(rows.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun persist(values: List<EqProfile>) {
        val rows = JSONArray(); values.forEach { rows.put(encode(it)) }
        prefs.edit().putString("profiles", rows.toString()).apply()
    }

    private fun encode(value: EqProfile) = JSONObject().put("id", value.id).put("name", value.name)
        .put("path", value.path.name).put("context", value.context.name).put("curve", JSONArray(value.curve.values))
        .put("audio_chain", value.audioChain).put("intent", value.intent?.name ?: "").put("notes", value.notes)
        .put("created", value.createdAt.toEpochMilli()).put("updated", value.updatedAt.toEpochMilli())
        .put("model", value.radioModel).put("firmware", value.firmware ?: "").put("input", value.inputDevice)

    private fun decode(row: JSONObject) = EqProfile(row.getString("id"), row.getString("name"),
        EqPath.valueOf(row.getString("path")), EqContext.valueOf(row.getString("context")),
        EqCurve.of(List(row.getJSONArray("curve").length()) { row.getJSONArray("curve").getInt(it) }),
        row.optString("audio_chain"), row.optString("intent").takeIf(String::isNotBlank)?.let(EqIntent::valueOf),
        row.optString("notes"), Instant.ofEpochMilli(row.getLong("created")), Instant.ofEpochMilli(row.getLong("updated")),
        row.optString("model"), row.optString("firmware").takeIf(String::isNotBlank), row.optString("input"))
}
