package app.shackcq.mobile.dxchaser

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class DxChaserSettingsDocument(
    val version: Int = 1,
    val featureVisible: Boolean = true,
    val featureEnabled: Boolean = true,
    val defaultOperatingMode: DxChaserMode = DxChaserMode.ASSIST,
    val profile: DxChaserProfile = DxChaserProfile.BALANCED,
    val selectedModes: Set<String> = setOf("FT8", "FT4"),
    val selectedBands: Set<String> = setOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m"),
    val minimumSnr: Int = -24,
    val localDecodeTtlSeconds: Long = 120,
    val repeatDecodeCap: Int = 6,
    val normalAttemptLimit: Int = 3,
    val scarceAttemptLimit: Int = 6,
    val atnoAttemptLimit: Int = 10,
    val preEngagementTimeoutSeconds: Long = 120,
    val engagedTimeoutSeconds: Long = 300,
    val sessionTimeoutSeconds: Long = 1_800,
    val preemptionEnabled: Boolean = true,
    val preemptionHysteresisPercent: Int = 25,
    val minimumTargetDwellSeconds: Long = 30,
    val recentAttemptCooldownSeconds: Long = 300,
    val completedQsoCooldownSeconds: Long = 1_800,
    val crossBandRecommendations: Boolean = true,
    val crossBandSourceAgreementThreshold: Int = 2,
    val crossBandReviewCooldownSeconds: Long = 120,
    val currentEvidenceContribution: Boolean = true,
    val empiricalOutlookContribution: Boolean = true,
    val rarityContribution: Boolean = true,
    val dryRunJournal: Boolean = true,
    val attemptRetentionDays: Int = 30,
    val sessionRetentionDays: Int = 180,
) {
    fun clamped() = copy(
        minimumSnr = minimumSnr.coerceIn(-30, 5),
        localDecodeTtlSeconds = localDecodeTtlSeconds.coerceIn(15, 600),
        repeatDecodeCap = repeatDecodeCap.coerceIn(1, 12),
        normalAttemptLimit = normalAttemptLimit.coerceIn(1, 10),
        scarceAttemptLimit = scarceAttemptLimit.coerceIn(1, 12),
        atnoAttemptLimit = atnoAttemptLimit.coerceIn(1, 20),
        preEngagementTimeoutSeconds = preEngagementTimeoutSeconds.coerceIn(10, 600),
        engagedTimeoutSeconds = engagedTimeoutSeconds.coerceIn(30, 600),
        sessionTimeoutSeconds = sessionTimeoutSeconds.coerceIn(60, 7_200),
        preemptionHysteresisPercent = preemptionHysteresisPercent.coerceIn(0, 100),
        minimumTargetDwellSeconds = minimumTargetDwellSeconds.coerceIn(0, 300),
        recentAttemptCooldownSeconds = recentAttemptCooldownSeconds.coerceIn(30, 3_600),
        completedQsoCooldownSeconds = completedQsoCooldownSeconds.coerceIn(60, 86_400),
        crossBandSourceAgreementThreshold = crossBandSourceAgreementThreshold.coerceIn(2, 5),
        crossBandReviewCooldownSeconds = crossBandReviewCooldownSeconds.coerceIn(30, 3_600),
        attemptRetentionDays = attemptRetentionDays.coerceIn(1, 90),
        sessionRetentionDays = sessionRetentionDays.coerceIn(7, 365),
        selectedModes = selectedModes.intersect(setOf("FT8", "FT4")).ifEmpty { setOf("FT8", "FT4") },
        selectedBands = selectedBands.filter { Regex("^(160|80|60|40|30|20|17|15|12|10|6|4|2)m$").matches(it) }.toSet()
            .ifEmpty { setOf("20m") },
    )

    fun toJson(): String = JSONObject().apply {
        put("version", 1); put("featureVisible", featureVisible); put("featureEnabled", featureEnabled)
        put("defaultOperatingMode", defaultOperatingMode.name); put("profile", profile.name)
        put("selectedModes", JSONArray(selectedModes.sorted())); put("selectedBands", JSONArray(selectedBands.sorted()))
        put("minimumSnr", minimumSnr); put("localDecodeTtlSeconds", localDecodeTtlSeconds); put("repeatDecodeCap", repeatDecodeCap)
        put("normalAttemptLimit", normalAttemptLimit); put("scarceAttemptLimit", scarceAttemptLimit); put("atnoAttemptLimit", atnoAttemptLimit)
        put("preEngagementTimeoutSeconds", preEngagementTimeoutSeconds); put("engagedTimeoutSeconds", engagedTimeoutSeconds)
        put("sessionTimeoutSeconds", sessionTimeoutSeconds); put("preemptionEnabled", preemptionEnabled)
        put("preemptionHysteresisPercent", preemptionHysteresisPercent); put("minimumTargetDwellSeconds", minimumTargetDwellSeconds)
        put("recentAttemptCooldownSeconds", recentAttemptCooldownSeconds); put("completedQsoCooldownSeconds", completedQsoCooldownSeconds)
        put("crossBandRecommendations", crossBandRecommendations); put("crossBandSourceAgreementThreshold", crossBandSourceAgreementThreshold)
        put("crossBandReviewCooldownSeconds", crossBandReviewCooldownSeconds); put("currentEvidenceContribution", currentEvidenceContribution)
        put("empiricalOutlookContribution", empiricalOutlookContribution); put("rarityContribution", rarityContribution)
        put("dryRunJournal", dryRunJournal); put("attemptRetentionDays", attemptRetentionDays); put("sessionRetentionDays", sessionRetentionDays)
    }.toString()

    companion object {
        fun parse(value: String?): DxChaserSettingsDocument {
            if (value.isNullOrBlank()) return DxChaserSettingsDocument()
            return runCatching {
                val json = JSONObject(value)
                require(json.optInt("version", 1) == 1)
                fun strings(name: String, fallback: Set<String>) = json.optJSONArray(name)?.let { rows ->
                    (0 until rows.length()).mapNotNull { rows.optString(it).takeIf(String::isNotBlank) }.toSet()
                } ?: fallback
                DxChaserSettingsDocument(
                    featureVisible = json.optBoolean("featureVisible", true), featureEnabled = json.optBoolean("featureEnabled", true),
                    defaultOperatingMode = enumValueOrDefault(json.optString("defaultOperatingMode"), DxChaserMode.ASSIST),
                    profile = enumValueOrDefault(json.optString("profile"), DxChaserProfile.BALANCED),
                    selectedModes = strings("selectedModes", setOf("FT8", "FT4")),
                    selectedBands = strings("selectedBands", DxChaserSettingsDocument().selectedBands),
                    minimumSnr = json.optInt("minimumSnr", -24), localDecodeTtlSeconds = json.optLong("localDecodeTtlSeconds", 120),
                    repeatDecodeCap = json.optInt("repeatDecodeCap", 6), normalAttemptLimit = json.optInt("normalAttemptLimit", 3),
                    scarceAttemptLimit = json.optInt("scarceAttemptLimit", 6), atnoAttemptLimit = json.optInt("atnoAttemptLimit", 10),
                    preEngagementTimeoutSeconds = json.optLong("preEngagementTimeoutSeconds", 120),
                    engagedTimeoutSeconds = json.optLong("engagedTimeoutSeconds", 300), sessionTimeoutSeconds = json.optLong("sessionTimeoutSeconds", 1_800),
                    preemptionEnabled = json.optBoolean("preemptionEnabled", true),
                    preemptionHysteresisPercent = json.optInt("preemptionHysteresisPercent", 25),
                    minimumTargetDwellSeconds = json.optLong("minimumTargetDwellSeconds", 30),
                    recentAttemptCooldownSeconds = json.optLong("recentAttemptCooldownSeconds", 300),
                    completedQsoCooldownSeconds = json.optLong("completedQsoCooldownSeconds", 1_800),
                    crossBandRecommendations = json.optBoolean("crossBandRecommendations", true),
                    crossBandSourceAgreementThreshold = json.optInt("crossBandSourceAgreementThreshold", 2),
                    crossBandReviewCooldownSeconds = json.optLong("crossBandReviewCooldownSeconds", 120),
                    currentEvidenceContribution = json.optBoolean("currentEvidenceContribution", true),
                    empiricalOutlookContribution = json.optBoolean("empiricalOutlookContribution", true),
                    rarityContribution = json.optBoolean("rarityContribution", true),
                    dryRunJournal = json.optBoolean("dryRunJournal", true), attemptRetentionDays = json.optInt("attemptRetentionDays", 30),
                    sessionRetentionDays = json.optInt("sessionRetentionDays", 180),
                ).clamped()
            }.getOrDefault(DxChaserSettingsDocument())
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T) =
            enumValues<T>().firstOrNull { it.name == value } ?: fallback
    }
}

data class DxChaserRarityImport(
    val sourceLabel: String,
    val sourceDate: String,
    val digest: String,
    val rows: List<DxChaserRarity>,
)

object DxChaserRarityParser {
    const val MAX_BYTES = 256 * 1024
    const val MAX_ROWS = 1_000

    fun parseJson(bytes: ByteArray, today: LocalDate = LocalDate.now()): DxChaserRarityImport {
        require(bytes.size <= MAX_BYTES) { "Rarity import exceeds $MAX_BYTES bytes" }
        val text = bytes.toString(Charsets.UTF_8).trim()
        require(text.startsWith("{") && !text.contains("<html", ignoreCase = true)) { "Rarity import must be JSON" }
        val json = JSONObject(text)
        require(json.optInt("formatVersion") == 1) { "Unsupported rarity format version" }
        val source = json.optString("sourceLabel").trim()
        require(source.isNotBlank() && !source.contains("password", true) && !source.contains("token", true)) { "Invalid source label" }
        val date = try { LocalDate.parse(json.getString("sourceDate")) } catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid source date") }
        require(!date.isAfter(today.plusDays(1))) { "Future source date" }
        val array = json.getJSONArray("entities")
        require(array.length() in 1..MAX_ROWS) { "Invalid rarity row count" }
        val seen = mutableSetOf<String>()
        val rows = (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val entity = row.getString("entityIdentifier").trim().uppercase()
            val rank = row.optInt("rank", 0).takeIf { it > 0 }
            val tier = row.optInt("tier", 0).takeIf { it > 0 }
            require(entity.matches(Regex("^[A-Z0-9-]{1,16}$")) && seen.add(entity)) { "Duplicate or invalid entity" }
            require((rank != null) xor (tier != null)) { "Each entity requires exactly one rank or tier" }
            require(rank == null || rank in 1..500) { "Invalid rarity rank" }
            require(tier == null || tier in 1..10) { "Invalid rarity tier" }
            DxChaserRarity(entity, rank, tier, DxChaserRarityOrigin.USER_IMPORTED, source, date.toString())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return DxChaserRarityImport(source, date.toString(), digest, rows.map { it.copy(digest = digest) })
    }
}
