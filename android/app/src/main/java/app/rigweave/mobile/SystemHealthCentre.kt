package app.rigweave.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import app.rigweave.mobile.dxchaser.DxChaserReadOnlySnapshot
import app.rigweave.mobile.keyer.KeyerQueueSnapshot
import app.rigweave.mobile.keyer.KeyerQueueState

enum class HealthState { HEALTHY, ATTENTION, DEGRADED, PAUSED, UNAVAILABLE }

data class SystemHealthCard(
    val id: String,
    val title: String,
    val state: HealthState,
    val summary: String,
    val counts: Map<String, Long> = emptyMap(),
    val sourceAgeSeconds: Long? = null,
    val safeActions: List<String> = emptyList(),
)

data class SystemHealthSnapshot(
    val generatedAt: Long,
    val schemas: Map<String, Int>,
    val databaseBytes: Map<String, Long>,
    val cards: List<SystemHealthCard>,
    val operatingGeneration: Long,
)

internal fun buildSystemHealthSnapshot(
    context: Context,
    operatingContext: OperatingContextSnapshot,
    qso: StabilitySnapshot?,
    wavelogStatus: String,
    wavelogPending: Int,
    syncAttention: Int,
    clusterStatus: String,
    neuralStatus: String,
    groupsStatus: String,
    groupsMessages: Int,
    groupsDrafts: Int,
    digiMode: String,
    digiRxActive: Boolean,
    digiStatus: String,
    keyer: KeyerQueueSnapshot = KeyerQueueSnapshot(),
    contest: ContestReadOnlySnapshot = ContestReadOnlySnapshot(),
    chaser: DxChaserReadOnlySnapshot = DxChaserReadOnlySnapshot(),
): SystemHealthSnapshot {
    fun databaseBytes(name: String) = listOf("", "-wal", "-shm").sumOf { suffix ->
        context.getDatabasePath(name + suffix).takeIf { it.isFile }?.length() ?: 0L
    }
    val bytes = mapOf(
        "rigweave.sqlite" to (qso?.databaseBytes ?: databaseBytes("rigweave.sqlite")),
        "neural-dx.sqlite" to databaseBytes("neural-dx.sqlite"),
        "rigweave-digi.sqlite" to databaseBytes("rigweave-digi.sqlite"),
        "rigweave-groupsio.sqlite" to databaseBytes("rigweave-groupsio.sqlite"),
        "rigweave-contest.sqlite" to databaseBytes("rigweave-contest.sqlite"),
        "rigweave-dxchaser.sqlite" to databaseBytes("rigweave-dxchaser.sqlite"),
    )
    val projection = qso?.projection
    val cards = listOf(
        SystemHealthCard("storage", "Storage", if (projection?.state == ProjectionState.READY) HealthState.HEALTHY else HealthState.ATTENTION,
            "QSO schema 17 · projection contract 6 · Neural 5 · Digi 2 · Groups.io 2",
            mapOf("qso_canonical" to (projection?.canonicalRows?.toLong() ?: 0), "qso_projected" to (projection?.projectionRows?.toLong() ?: 0)),
            safeActions = listOf("verify projection", "repair/rebuild projection")),
        SystemHealthCard("wavelog", "Wavelog", if (wavelogPending + syncAttention == 0) HealthState.HEALTHY else HealthState.ATTENTION,
            wavelogStatus.take(180), mapOf("outbox" to wavelogPending.toLong(), "attention" to syncAttention.toLong()),
            safeActions = listOf("retry/reconcile", "pause/resume binding", "open exact conflict/outbox")),
        SystemHealthCard("providers", "Providers", if (operatingContext.networkAvailable.value) HealthState.HEALTHY else HealthState.DEGRADED,
            "Cluster ${clusterStatus.take(80)} · Neural ${neuralStatus.take(80)}",
            safeActions = listOf("refresh", "clear re-fetchable cache", "open source attribution")),
        SystemHealthCard("groupsio", "Groups.io", if (groupsDrafts == 0) HealthState.HEALTHY else HealthState.ATTENTION,
            groupsStatus.take(180), mapOf("cached_messages" to groupsMessages.toLong(), "drafts_attention" to groupsDrafts.toLong()),
            safeActions = listOf("open exact draft/outbox", "resume archive")),
        SystemHealthCard("radio_digi", "Radio / Digi", if (operatingContext.connected.value) HealthState.HEALTHY else HealthState.UNAVAILABLE,
            "${operatingContext.radioModel.value} · $digiMode · ${if (digiRxActive) "RX active" else "RX stopped"} · ${digiStatus.take(120)}",
            safeActions = listOf("open Digi diagnostics")),
        SystemHealthCard("keyer", "Keyer", if (keyer.state == KeyerQueueState.FAILED) HealthState.ATTENTION else HealthState.HEALTHY,
            "Queue ${keyer.state} · pending ${keyer.pendingCount}", mapOf("pending" to keyer.pendingCount.toLong()),
            safeActions = listOf("open Keyer settings", "stop Keyer")),
        SystemHealthCard("contest", "Contest", contest.activeSession?.let { if (it.state.name == "RUNNING") HealthState.HEALTHY else HealthState.PAUSED } ?: HealthState.UNAVAILABLE,
            "Schema 1 · session ${contest.activeSession?.state ?: "NONE"} · score ${contest.activeSession?.score?.status ?: "NONE"}",
            mapOf("claims" to contest.claims.size.toLong()), safeActions = listOf("open Contest", "verify/rebuild derived score", "stop Contest")),
        SystemHealthCard("n1mm", "N1MM", if (contest.n1mmArmed) HealthState.HEALTHY else HealthState.PAUSED,
            "${if (contest.n1mmEnabled) "enabled" else "disabled"} · ${if (contest.n1mmArmed) "armed" else "not armed"} · sanitized status only",
            mapOf("peers" to contest.n1mmPeers.toLong(), "claims" to contest.claims.size.toLong()),
            safeActions = listOf("open Contest network", "stop N1MM", "retry reviewed item")),
        SystemHealthCard("dx_chaser", "DX Chaser", if (chaser.session.state.name in setOf("FAILED")) HealthState.ATTENTION else HealthState.PAUSED,
            "Schema 1 · ${chaser.session.mode} · ${chaser.session.state} · target ${if (chaser.currentTarget == null) "none" else "selected"}",
            mapOf("candidates" to chaser.rankedCandidates.size.toLong(), "cooldowns" to chaser.cooldowns.size.toLong()),
            safeActions = listOf("open DX Chaser", "compact Chaser history", "stop Chaser")),
    )
    return SystemHealthSnapshot(System.currentTimeMillis(), mapOf("qso" to 16, "projection" to 5, "neural" to 5, "digi" to 2, "groupsio" to 2,
        "contest" to 1, "dx_chaser" to 1),
        bytes, cards, operatingContext.generation)
}

object SanitizedSupportBundle {
    fun build(snapshot: SystemHealthSnapshot, upstreamPins: Map<String, String>, packageSummary: Map<String, Long>): ByteArray {
        val root = JSONObject()
            .put("format", "RIGWEAVE_SANITIZED_SUPPORT_V1")
            .put("generated_at", snapshot.generatedAt)
            .put("build", JSONObject().put("version", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE))
            .put("schemas", JSONObject(snapshot.schemas))
            .put("database_bytes", JSONObject(snapshot.databaseBytes))
            .put("operating_generation", snapshot.operatingGeneration)
            .put("upstream_pins", JSONObject(upstreamPins))
            .put("package_bytes", JSONObject(packageSummary))
            .put("cards", JSONArray().apply { snapshot.cards.forEach { card -> put(JSONObject()
                .put("id", card.id).put("state", card.state.name).put("summary", sanitize(card.summary))
                .put("counts", JSONObject(card.counts)).put("source_age_seconds", card.sourceAgeSeconds)
                .put("safe_actions", JSONArray(card.safeActions))) } })
            .put("privacy", "No credentials, callsigns, QSO payloads/comments, Groups.io messages, Digi transcripts, provider bodies, or private paths")
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("rigweave-support.json"))
                zip.write(root.toString(2).toByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }
    }

    private fun sanitize(value: String) = value.replace(Regex("(?i)(token|api[_ -]?key|password|secret)\\s*[:=]\\s*\\S+"), "$1=[redacted]")
        .replace(Regex("/[^\\s]+"), "[private-path]").take(240)
}
