// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.bandmap

import android.database.sqlite.SQLiteDatabase
import app.shackcq.mobile.ProjectionState
import app.shackcq.mobile.QsoDatabase
import app.shackcq.mobile.QsoProjectionStore
import java.util.Locale

private const val BAND_MAP_NEED_DIMENSION_LIMIT = 20_000

internal fun QsoDatabase.bandMapNeedsSnapshot(stationProfileId: String, stationCallsign: String): BandMapNeedsSnapshot {
    val profile = stationProfileId.trim()
    val call = stationCallsign.trim().uppercase(Locale.US)
    val (scope, args) = when {
        profile.isNotBlank() -> "station_profile_id=?" to arrayOf(profile)
        call.isNotBlank() -> "station_callsign_norm=?" to arrayOf(call)
        else -> "1=0" to emptyArray()
    }
    val db = readableDatabase
    val truncated = mutableSetOf<String>()
    fun values(column: String, confirmedOnly: Boolean = false): Set<String> {
        val confirmation = if (confirmedOnly) " AND (paper_received=1 OR lotw_received=1 OR eqsl_received=1 OR qrz_received=1 OR clublog_received=1 OR dcl_received=1)" else ""
        val rows = db.rawQuery("SELECT DISTINCT $column FROM qso_projection WHERE $scope AND is_valid=1 AND $column<>''$confirmation ORDER BY $column LIMIT ${BAND_MAP_NEED_DIMENSION_LIMIT + 1}", args)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0).uppercase(Locale.US)) } }
        if (rows.size > BAND_MAP_NEED_DIMENSION_LIMIT) truncated += column
        return rows.take(BAND_MAP_NEED_DIMENSION_LIMIT).toSet()
    }
    fun pairs(first: String, second: String, confirmedOnly: Boolean = false): Set<String> {
        val confirmation = if (confirmedOnly) " AND (paper_received=1 OR lotw_received=1 OR eqsl_received=1 OR qrz_received=1 OR clublog_received=1 OR dcl_received=1)" else ""
        val rows = db.rawQuery("SELECT DISTINCT $first,$second FROM qso_projection WHERE $scope AND is_valid=1 AND $first<>'' AND $second<>''$confirmation ORDER BY $first,$second LIMIT ${BAND_MAP_NEED_DIMENSION_LIMIT + 1}", args)
            .use { cursor -> buildList { while (cursor.moveToNext()) add("${cursor.getString(0).uppercase(Locale.US)}|${cursor.getString(1).uppercase(Locale.US)}") } }
        if (rows.size > BAND_MAP_NEED_DIMENSION_LIMIT) truncated += "$first+$second"
        return rows.take(BAND_MAP_NEED_DIMENSION_LIMIT).toSet()
    }
    val portable = portableReferences(db, scope, args).also { if (it.second) truncated += "portable_reference" }.first
    val health = projectionHealth()
    return BandMapNeedsSnapshot(
        stationKey = profile.ifBlank { call }, projectionVersion = QsoProjectionStore.VERSION, generation = changeToken(),
        complete = health.state == ProjectionState.READY,
        workedEntities = values("dxcc"), confirmedEntities = values("dxcc", true),
        workedBands = values("band_norm"), confirmedBands = values("band_norm", true),
        workedModes = values("mode_family"), confirmedModes = values("mode_family", true),
        workedBandModes = pairs("band_norm", "mode_family"), confirmedBandModes = pairs("band_norm", "mode_family", true),
        workedGrids = values("grid_norm"), workedCqZones = values("cq_zone"), workedItuZones = values("itu_zone"),
        workedWpxPrefixes = values("wpx_prefix"), workedPortableReferences = portable, truncatedDimensions = truncated,
    )
}

private fun portableReferences(db: SQLiteDatabase, scope: String, args: Array<String>): Pair<Set<String>, Boolean> {
    val rows = db.rawQuery("SELECT DISTINCT r.program||':'||r.reference_norm FROM qso_reference r JOIN qso_projection p ON p.qso_id=r.qso_id WHERE $scope AND p.is_valid=1 AND r.direction='WORKED' ORDER BY 1 LIMIT ${BAND_MAP_NEED_DIMENSION_LIMIT + 1}", args)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0).uppercase(Locale.US)) } }
    return rows.take(BAND_MAP_NEED_DIMENSION_LIMIT).toSet() to (rows.size > BAND_MAP_NEED_DIMENSION_LIMIT)
}
