package app.shackcq.mobile.radio.hamlib

import org.json.JSONArray
import org.json.JSONObject

data class HamlibLibrary(
    val version: String,
    val sourceDigest: String,
    val licence: String,
    val backendCount: Int,
)

data class HamlibFrequencyRange(
    val transmit: Boolean,
    val startHz: Long,
    val endHz: Long,
    val lowMilliwatts: Int,
    val highMilliwatts: Int,
)

data class HamlibFilter(val modeMask: Long, val widthHz: Int)

data class HamlibCapabilitySnapshot(
    val modes: Set<String>,
    val vfos: Set<String>,
    val ranges: List<HamlibFrequencyRange>,
    val filters: List<HamlibFilter>,
    val readableLevels: Set<String>,
    val writableLevels: Set<String>,
    val readableFunctions: Set<String>,
    val writableFunctions: Set<String>,
    val readableParameters: Set<String>,
    val writableParameters: Set<String>,
    val targetableVfo: Int,
    val maxRitHz: Int,
    val maxXitHz: Int,
    val maxIfShiftHz: Int,
    val pttType: Int,
)

data class HamlibModelDescriptor(
    val id: Int,
    val manufacturer: String,
    val model: String,
    val backend: String,
    val backendId: Int,
    val driverVersion: String,
    val status: String,
    val portType: String,
    val serialRateMin: Int,
    val serialRateMax: Int,
    val serialDataBits: Int,
    val serialStopBits: Int,
    val serialParity: Int,
    val serialHandshake: Int,
    val timeoutMs: Int,
    val retry: Int,
    val capabilities: HamlibCapabilitySnapshot,
) {
    val label: String get() = "$manufacturer $model"
}

data class HamlibRadioSnapshot(
    val modelId: Int,
    val vfo: String,
    val txVfo: String,
    val frequencyHz: Long,
    val frequencyAHz: Long,
    val frequencyBHz: Long,
    val mode: String,
    val passbandHz: Int,
    val split: Boolean,
    val ritHz: Int,
    val xitHz: Int,
    val ptt: Boolean,
    val levels: Map<String, Double>,
)

data class HamlibDiagnostics(
    val connected: Boolean = false,
    val generation: Long = 0,
    val modelId: Int? = null,
    val transport: String? = null,
    val lastStatus: Int = 0,
    val lastError: String? = null,
    val pollCount: Long = 0,
)

class HamlibModelRegistry private constructor(
    val library: HamlibLibrary,
    val models: List<HamlibModelDescriptor>,
) {
    private val byId = models.associateBy { it.id }

    fun find(id: Int): HamlibModelDescriptor? = byId[id]

    fun search(query: String): List<HamlibModelDescriptor> {
        val needle = query.trim().lowercase()
        return if (needle.isEmpty()) models else models.filter {
            it.label.lowercase().contains(needle) ||
                it.backend.lowercase().contains(needle) || it.id.toString() == needle
        }
    }

    companion object {
        const val MAX_MODELS = 2048
        const val MAX_CAPABILITIES = 64
        const val MAX_REGISTRY_BYTES = 8 * 1024 * 1024

        fun parse(libraryJson: String, modelsJson: String): HamlibModelRegistry {
            require(libraryJson.length <= 16_384 && modelsJson.length <= MAX_REGISTRY_BYTES) {
                "Hamlib registry response exceeds bound"
            }
            val libraryObject = JSONObject(libraryJson)
            val root = JSONObject(modelsJson)
            val array = root.getJSONArray("models")
            require(array.length() <= MAX_MODELS) { "Hamlib model response exceeds bound" }
            val models = buildList(array.length()) {
                for (index in 0 until array.length()) add(parseModel(array.getJSONObject(index)))
            }
            require(root.getInt("modelCount") == models.size) { "Hamlib model count mismatch" }
            val library = HamlibLibrary(
                version = libraryObject.getString("version").take(64),
                sourceDigest = libraryObject.getString("sourceDigest").take(128),
                licence = libraryObject.getString("licence").take(256),
                backendCount = libraryObject.getInt("backendCount"),
            )
            require(root.getString("sourceDigest") == library.sourceDigest) { "Hamlib source mismatch" }
            return HamlibModelRegistry(library, models)
        }

        private fun parseModel(value: JSONObject): HamlibModelDescriptor {
            fun names(key: String): Set<String> = value.getJSONArray(key).strings()
            val ranges = value.getJSONArray("ranges").objects().take(128).map {
                HamlibFrequencyRange(
                    transmit = it.getBoolean("tx"),
                    startHz = it.getLong("startHz"), endHz = it.getLong("endHz"),
                    lowMilliwatts = it.getInt("lowMilliwatts"),
                    highMilliwatts = it.getInt("highMilliwatts"),
                )
            }.toList()
            val filters = value.getJSONArray("filters").objects().take(MAX_CAPABILITIES).map {
                HamlibFilter(it.getLong("modeMask"), it.getInt("widthHz"))
            }.toList()
            return HamlibModelDescriptor(
                id = value.getInt("id"), manufacturer = value.text("manufacturer"),
                model = value.text("model"), backend = value.text("backend"),
                backendId = value.getInt("backendId"), driverVersion = value.text("driverVersion"),
                status = value.text("status"), portType = value.text("portType"),
                serialRateMin = value.getInt("serialRateMin"), serialRateMax = value.getInt("serialRateMax"),
                serialDataBits = value.getInt("serialDataBits"), serialStopBits = value.getInt("serialStopBits"),
                serialParity = value.getInt("serialParity"), serialHandshake = value.getInt("serialHandshake"),
                timeoutMs = value.getInt("timeoutMs"), retry = value.getInt("retry"),
                capabilities = HamlibCapabilitySnapshot(
                    modes = names("modes"), vfos = names("vfos"), ranges = ranges, filters = filters,
                    readableLevels = names("getLevels"), writableLevels = names("setLevels"),
                    readableFunctions = names("getFunctions"), writableFunctions = names("setFunctions"),
                    readableParameters = names("getParameters"), writableParameters = names("setParameters"),
                    targetableVfo = value.getInt("targetableVfo"), maxRitHz = value.getInt("maxRitHz"),
                    maxXitHz = value.getInt("maxXitHz"), maxIfShiftHz = value.getInt("maxIfShiftHz"),
                    pttType = value.getInt("pttType"),
                ),
            )
        }

        fun parseSnapshot(json: String): HamlibRadioSnapshot {
            val value = JSONObject(json)
            require(value.getBoolean("ok")) { value.optString("error", "Hamlib poll failed") }
            val levels = value.getJSONObject("levels")
            val levelMap = levels.keys().asSequence().take(MAX_CAPABILITIES)
                .associateWith { levels.getDouble(it) }
            return HamlibRadioSnapshot(
                modelId = value.getInt("modelId"), vfo = value.text("vfo"), txVfo = value.text("txVfo"),
                frequencyHz = value.getLong("frequencyHz"), frequencyAHz = value.getLong("frequencyAHz"),
                frequencyBHz = value.getLong("frequencyBHz"), mode = value.text("mode"),
                passbandHz = value.getInt("passbandHz"), split = value.getBoolean("split"),
                ritHz = value.getInt("ritHz"), xitHz = value.getInt("xitHz"),
                ptt = value.getBoolean("ptt"), levels = levelMap,
            )
        }

        private fun JSONObject.text(key: String) = getString(key).take(256)
        private fun JSONArray.strings(): Set<String> = buildSet {
            val count = length().coerceAtMost(MAX_CAPABILITIES)
            for (index in 0 until count) add(getString(index).take(64))
        }
        private fun JSONArray.objects(): Sequence<JSONObject> =
            (0 until length()).asSequence().map(::getJSONObject)
    }
}
