package app.shackcq.mobile.hamclock

import android.content.Context
import android.content.SharedPreferences
import java.time.Clock
import java.util.UUID

/**
 * App-private, versioned persistence for HamClock presentation preferences.
 *
 * Station, radio, cluster-provider and account credentials deliberately do not appear in this
 * model. Those remain owned by ShackCQ's existing settings/controllers.
 */
class HamClockSettingsStore private constructor(
    private val persistence: HamClockDocumentPersistence,
    private val clock: Clock,
    private val newId: () -> String,
) {
    @Volatile private var document: HamClockSettingsDocument = load()

    constructor(context: Context) : this(
        SharedPreferencesHamClockPersistence(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        ),
        Clock.systemUTC(),
        { UUID.randomUUID().toString() },
    )

    internal constructor(
        persistence: HamClockDocumentPersistence,
        clock: Clock,
        newId: () -> String,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(persistence, clock, newId)

    fun snapshot(): HamClockSettingsDocument = document
    fun settings(): HamClockUserSettings = document.settings
    fun profiles(): List<HamClockNamedProfile> = document.profiles

    @Synchronized
    fun updateSettings(transform: (HamClockUserSettings) -> HamClockUserSettings): HamClockSettingsDocument =
        persist(document.copy(settings = transform(document.settings), activeProfileId = null))

    @Synchronized
    fun replaceSettings(settings: HamClockUserSettings): HamClockSettingsDocument =
        persist(document.copy(settings = settings, activeProfileId = null))

    @Synchronized
    fun setPanel(preference: HamClockPanelPreference): HamClockSettingsDocument = updateSettings { settings ->
        settings.copy(panels = settings.panels.filterNot { it.id == preference.id } + preference)
    }

    @Synchronized
    fun removePanel(id: String): HamClockSettingsDocument = updateSettings { settings ->
        settings.copy(panels = settings.panels.filterNot { it.id == id })
    }

    @Synchronized
    fun resetPanel(id: String): HamClockSettingsDocument {
        val default = defaultHamClockPanels().firstOrNull { it.id == id }
            ?: return removePanel(id)
        return setPanel(default)
    }

    @Synchronized
    fun resetLayout(): HamClockSettingsDocument = updateSettings { settings ->
        settings.copy(panels = defaultHamClockPanels())
    }

    @Synchronized
    fun setMapLayer(preference: HamClockMapLayerPreference): HamClockSettingsDocument = updateSettings { settings ->
        settings.copy(map = settings.map.copy(layers = settings.map.layers.filterNot { it.id == preference.id } + preference))
    }

    /** Creates a named snapshot, or deliberately overwrites the selected profile. */
    @Synchronized
    fun saveProfile(name: String, replaceProfileId: String? = null): HamClockNamedProfile {
        val cleanName = name.trim().take(64)
        require(cleanName.isNotBlank()) { "Profile name is required" }
        val now = clock.millis()
        val existing = replaceProfileId?.let { id -> document.profiles.firstOrNull { it.id == id } }
        require(replaceProfileId == null || existing != null) { "Unknown HamClock profile: $replaceProfileId" }
        require(existing != null || document.profiles.size < HamClockSettingsCodec.MAX_PROFILES) { "Too many HamClock profiles" }
        val profile = HamClockNamedProfile(
            id = existing?.id ?: newId(),
            name = cleanName,
            settings = document.settings,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        val profiles = document.profiles.filterNot { it.id == profile.id } + profile
        persist(document.copy(profiles = profiles, activeProfileId = profile.id))
        return document.profiles.first { it.id == profile.id }
    }

    @Synchronized
    fun applyProfile(id: String): HamClockSettingsDocument {
        val profile = document.profiles.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown HamClock profile: $id")
        return persist(document.copy(settings = profile.settings, activeProfileId = profile.id))
    }

    @Synchronized
    fun clearActiveProfile(): HamClockSettingsDocument =
        persist(document.copy(activeProfileId = null))

    @Synchronized
    fun renameProfile(id: String, name: String): HamClockNamedProfile {
        val cleanName = name.trim().take(64)
        require(cleanName.isNotBlank()) { "Profile name is required" }
        val existing = document.profiles.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown HamClock profile: $id")
        val renamed = existing.copy(name = cleanName, updatedAtMillis = clock.millis())
        persist(document.copy(profiles = document.profiles.map { if (it.id == id) renamed else it }))
        return document.profiles.first { it.id == id }
    }

    @Synchronized
    fun deleteProfile(id: String): HamClockSettingsDocument {
        require(document.profiles.any { it.id == id }) { "Unknown HamClock profile: $id" }
        return persist(document.copy(
            profiles = document.profiles.filterNot { it.id == id },
            activeProfileId = document.activeProfileId?.takeUnless { it == id },
        ))
    }

    /** Standalone, credential-free JSON suitable for embedding in ShackCQ recovery JSON. */
    fun exportJson(pretty: Boolean = false): String =
        HamClockSettingsCodec.encode(document, if (pretty) 2 else 0)

    /** Validates an import without mutating app state. */
    fun reviewImport(json: String): HamClockImportResult = HamClockSettingsCodec.decode(json).result()

    /** Atomically replaces the document after the entire import has validated. */
    @Synchronized
    fun importJson(json: String): HamClockImportResult {
        val imported = HamClockSettingsCodec.decode(json)
        persist(imported)
        return document.result()
    }

    @Synchronized
    fun reset(): HamClockSettingsDocument = persist(HamClockSettingsDocument())

    private fun load(): HamClockSettingsDocument {
        persistence.read()?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { HamClockSettingsCodec.decode(raw) }.getOrNull()?.let { decoded ->
                persistence.write(HamClockSettingsCodec.encode(decoded))
                return decoded
            }
        }
        val migrated = migrateLegacy(persistence)
        persistence.write(HamClockSettingsCodec.encode(migrated))
        return migrated
    }

    private fun persist(value: HamClockSettingsDocument): HamClockSettingsDocument {
        val normalized = HamClockSettingsCodec.normalize(value)
        check(persistence.write(HamClockSettingsCodec.encode(normalized))) { "Unable to persist HamClock settings" }
        document = normalized
        return normalized
    }

    private fun HamClockSettingsDocument.result() = HamClockImportResult(version, profiles.size, activeProfileId)

    companion object {
        const val PREFERENCES_NAME = "shackcq-hamclock-layout"
        const val DOCUMENT_KEY = "settings_document_v1"

        private fun migrateLegacy(persistence: HamClockDocumentPersistence): HamClockSettingsDocument {
            val panelKeys = mapOf(
                HamClockPanelId.STATION to "station", HamClockPanelId.WEATHER to "weather",
                HamClockPanelId.BAND_ACTIVITY to "band_activity", HamClockPanelId.PSK_REPORTER to "signal",
                HamClockPanelId.DX_EXPEDITIONS to "dxpeditions", HamClockPanelId.DX_CLUSTER to "cluster",
                HamClockPanelId.SOLAR to "solar", HamClockPanelId.DX_TARGET to "target",
                HamClockPanelId.VOACAP to "voacap", HamClockPanelId.PORTABLE to "portable",
                HamClockPanelId.SATELLITES to "satellites",
            )
            val defaults = HamClockUserSettings()
            val panels = defaults.panels.map { panel ->
                panelKeys[panel.id]?.let(persistence::legacyBoolean)?.let { panel.copy(visible = it) } ?: panel
            }
            val legacyLayers = mapOf(
                HamClockMapLayerId.DX_PATHS to "map_paths", HamClockMapLayerId.GRAYLINE to "map_greyline",
                HamClockMapLayerId.SUN to "map_greyline", HamClockMapLayerId.PSK_REPORTER to "map_psk",
                HamClockMapLayerId.PORTABLE to "map_portable", HamClockMapLayerId.SATELLITES to "map_satellites",
            )
            val layers = defaults.map.layers.map { layer ->
                legacyLayers[layer.id]?.let(persistence::legacyBoolean)?.let { layer.copy(visible = it) } ?: layer
            }
            return HamClockSettingsDocument(settings = defaults.copy(panels = panels, map = defaults.map.copy(layers = layers)))
        }
    }
}

internal interface HamClockDocumentPersistence {
    fun read(): String?
    fun write(value: String): Boolean
    fun legacyBoolean(key: String): Boolean?
}

private class SharedPreferencesHamClockPersistence(private val preferences: SharedPreferences) : HamClockDocumentPersistence {
    override fun read(): String? = preferences.getString(HamClockSettingsStore.DOCUMENT_KEY, null)
    override fun write(value: String): Boolean = preferences.edit()
        .putString(HamClockSettingsStore.DOCUMENT_KEY, value).commit()
    override fun legacyBoolean(key: String): Boolean? =
        if (preferences.contains(key)) preferences.getBoolean(key, false) else null
}
