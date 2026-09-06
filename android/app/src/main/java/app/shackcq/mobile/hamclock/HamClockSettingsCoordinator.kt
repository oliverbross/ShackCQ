package app.shackcq.mobile.hamclock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Application-scoped authority for the single persisted HamClock settings document. */
internal class HamClockSettingsCoordinator(context: Context) {
    private val store = HamClockSettingsStore(context.applicationContext)
    var document by mutableStateOf(store.snapshot())
        private set

    fun updateSettings(transform: (HamClockUserSettings) -> HamClockUserSettings) {
        document = store.updateSettings(transform)
    }

    fun setPanel(value: HamClockPanelPreference) { document = store.setPanel(value) }
    fun setMapLayer(value: HamClockMapLayerPreference) { document = store.setMapLayer(value) }
    fun resetPanel(id: String) { document = store.resetPanel(id) }
    fun resetLayout() { document = store.resetLayout() }
    fun saveProfile(name: String, replaceProfileId: String? = null) {
        store.saveProfile(name, replaceProfileId)
        document = store.snapshot()
    }
    fun applyProfile(id: String) { document = store.applyProfile(id) }
    fun renameProfile(id: String, name: String) { store.renameProfile(id, name); document = store.snapshot() }
    fun deleteProfile(id: String) { document = store.deleteProfile(id) }
    fun clearActiveProfile() { document = store.clearActiveProfile() }
    fun exportJson(pretty: Boolean = false): String = store.exportJson(pretty)
    fun importJson(json: String): HamClockImportResult = store.importJson(json).also { document = store.snapshot() }
    fun reset() { document = store.reset() }
}
