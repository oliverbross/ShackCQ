package app.shackcq.mobile

import android.database.sqlite.SQLiteDatabase
import app.shackcq.mobile.hamclock.HamClockBandHistoricalRow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

internal data class IntelligenceFilterSql(val where:String,val args:List<String>)
internal fun progressDeletedSqlClause(includeDeleted: Boolean): String? =
    if (includeDeleted) null else "p.sync_state NOT IN ('TOMBSTONE','REMOTE_DELETED')"
internal data class CompactNeedsState(
    val workedDxcc:Set<String>,val confirmedDxcc:Set<String>,val dxccBands:Set<String>,val dxccModes:Set<String>,
    val cqZones:Set<String>,val ituZones:Set<String>,val portable:PortableProgress,val satellites:Set<String>,val confirmedSatellites:Set<String>,
)
private data class ProgressAggregateSummary(
    val total:Int,val uniqueCalls:Int,val paper:Int,val lotw:Int,val eqsl:Int,val qrz:Int,val clublog:Int,val dcl:Int,
    val dxccCoverage:Int,val cqCoverage:Int,val ituCoverage:Int,val stateCoverage:Int,val gridCoverage:Int,
    val distanceCoverage:Int,val powerCoverage:Int,val stationCoverage:Int,val portableCoverage:Int,
    val countries:Int,val grids:Int,val longestDistanceKm:Double?,val qrpQsos:Int,val activeDays:Int,
    val averageDistanceKm:Double?,val qrpDxcc:Int,val gridsConfirmed:Int,
)
private data class SatelliteAggregateSummary(
    val qsos:Int,val satellites:Int,val grids:Int,val confirmed:Int,val uniqueCalls:Int,val ownGrids:Int,
)

internal class LogIntelligenceRepository(private val database:QsoDatabase){
    private val db:SQLiteDatabase get()=database.readableDatabase
    private var cachedKey: Any? = null
    private var cachedSnapshot = ProgressSnapshot()
    private var cachedNeedsState: CompactNeedsState? = null

    fun fastSnapshot(filters:ProgressFilters,goals:List<ProgressGoal>,syncAttention:Int):ProgressSnapshot {
        val spec=filterSql(filters);val args=spec.args.toTypedArray();val where=spec.where
        val summary=aggregateSummary(where,args)
        val dxccMap=db.rawQuery(
            "SELECT dxcc,COUNT(*),SUM(CASE WHEN paper_received=1 OR lotw_received=1 THEN 1 ELSE 0 END) FROM qso_projection p WHERE $where AND dxcc<>'' GROUP BY dxcc",
            args,
        ).use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),ProgressCount(c.getInt(1),c.getInt(2))) } }
        val dxcc=ProgressCount(dxccMap.size,dxccMap.count{it.value.confirmed>0})
        val confirmations=linkedMapOf("Paper QSL" to summary.paper,"LoTW" to summary.lotw,"eQSL" to summary.eqsl,
            "QRZ" to summary.qrz,"Club Log" to summary.clublog,"DCL" to summary.dcl)
        val coverage=linkedMapOf("DXCC" to summary.dxccCoverage,"CQ zone" to summary.cqCoverage,
            "ITU zone" to summary.ituCoverage,"U.S. state" to summary.stateCoverage,"Grid" to summary.gridCoverage,
            "Distance" to summary.distanceCoverage,"TX power" to summary.powerCoverage,
            "Station profile" to summary.stationCoverage,"Portable reference" to summary.portableCoverage)
            .mapValues{ProgressCoverage(it.value,summary.total)}
        val dxccAward=AwardEstimate(AwardKind.DXCC,dxcc,100,
            dxccMap.map { AwardUnit(it.key,it.key,it.value.worked,it.value.confirmed>0) },
            coverage=ProgressCoverage(summary.dxccCoverage,summary.total))
        val base=ProgressSnapshot(totalQsos=summary.total,uniqueCalls=summary.uniqueCalls,dxcc=dxcc,
            countries=summary.countries,grids=summary.grids,longestDistanceKm=summary.longestDistanceKm,
            qrpQsos=summary.qrpQsos,syncAttention=syncAttention,coverage=coverage,confirmations=confirmations,
            confirmationDetails=confirmations.mapValues{ConfirmationProgress(it.value,summary.total)},
            activeDays=summary.activeDays,averageDistanceKm=summary.averageDistanceKm,qrpDxcc=summary.qrpDxcc,
            gridsConfirmed=summary.gridsConfirmed,unconfirmedDxccCount=dxccMap.count{it.value.confirmed==0},
            awards=mapOf(AwardKind.DXCC to dxccAward))
        return base.copy(goals=goals.take(4).map{GoalProgress(it,metricValue(it.metric,base))})
    }

    fun snapshot(filters:ProgressFilters,goals:List<ProgressGoal>,dxSpots:List<AndroidDXSpot>,portableSpots:List<PortableSpot>,syncAttention:Int,
        ctyLookup:(String)->AndroidCtyRecord?={null}):ProgressSnapshot{
        val key = listOf(database.changeToken(), filters, goals, syncAttention)
        cachedNeedsState?.takeIf { key == cachedKey }?.let { needsState ->
            return cachedSnapshot.copy(needs = buildNeeds(needsState, dxSpots, portableSpots, ctyLookup))
        }
        val spec=filterSql(filters);val args=spec.args.toTypedArray();val where=spec.where
        fun grouped(column:String,limit:Int=1000): Map<String, Int> = db.rawQuery("SELECT $column,COUNT(*) FROM qso_projection p WHERE $where AND $column<>'' GROUP BY $column ORDER BY COUNT(*) DESC LIMIT $limit",args).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}
        fun chronologicalGrouped(column:String,limit:Int=1000,extra:String=""): Map<String,Int> = db.rawQuery(
            "SELECT $column,COUNT(*) FROM qso_projection p WHERE $where AND $column<>'' $extra GROUP BY $column ORDER BY $column DESC LIMIT $limit",
            args,
        ).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getInt(1)) }.asReversed().toMap(linkedMapOf()) }
        fun progress(column:String): Map<String, ProgressCount> = db.rawQuery("SELECT $column,COUNT(*),SUM(CASE WHEN paper_received=1 OR lotw_received=1 THEN 1 ELSE 0 END) FROM qso_projection p WHERE $where AND $column<>'' GROUP BY $column",args).use{c->buildMap{while(c.moveToNext())put(c.getString(0),ProgressCount(c.getInt(1),c.getInt(2)))}}
        val summary=aggregateSummary(where,args);val total=summary.total;val uniqueCalls=summary.uniqueCalls
        val dxccMap=progress("dxcc");val dxcc=ProgressCount(dxccMap.size,dxccMap.count{it.value.confirmed>0})
        val stateMap=progress("state_norm").filterKeys(canonicalUsStates::contains);val cqMap=progress("cq_zone");val ituMap=progress("itu_zone")
        val confirmations=linkedMapOf("Paper QSL" to summary.paper,"LoTW" to summary.lotw,"eQSL" to summary.eqsl,"QRZ" to summary.qrz,"Club Log" to summary.clublog,"DCL" to summary.dcl)
        val confirmationDetails=confirmations.mapValues{ConfirmationProgress(it.value,total)}
        val bands=grouped("band_norm");val modes=grouped("mode_family");val submodes=grouped("COALESCE(NULLIF(submode_norm,''),mode_norm)")
        val operators=grouped("operator_norm");val years=chronologicalGrouped("CAST(utc_year AS TEXT)",100,"AND utc_year>0")
        val months=recentMonths(where,args)
        val stationProfiles=grouped("station_profile_id");val stationCallsigns=grouped("station_callsign_norm");val radios=grouped("radio_model_norm")
        val continents=progress("continent");val geography=dxccMap.entries.sortedByDescending{it.value.worked}.map{GeographyProgress(it.key,it.key,it.value)}
        val portable=portable(where,args)
        val satelliteWhere="($where) AND (satellite_name<>'' OR propagation_mode='SAT' OR band_norm='SAT')"
        val satelliteSummary=satelliteSummary(satelliteWhere,args)
        fun satelliteGrouped(column:String,limit:Int=100):Map<String,Int> = db.rawQuery("SELECT $column,COUNT(*) FROM qso_projection p WHERE $satelliteWhere AND $column<>'' GROUP BY $column ORDER BY COUNT(*) DESC LIMIT $limit",args).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}
        val satelliteMatrix=db.rawQuery("SELECT satellite_name,COUNT(*),COUNT(DISTINCT CASE WHEN paper_received=1 OR lotw_received=1 THEN NULLIF(grid_norm,'') END) FROM qso_projection p WHERE $satelliteWhere AND satellite_name<>'' GROUP BY satellite_name ORDER BY COUNT(*) DESC LIMIT 100",args).use{c->buildMap{while(c.moveToNext())put(c.getString(0),ProgressCount(c.getInt(1),c.getInt(2)))}}
        val satellite=SatelliteAnalytics(
            qsos=satelliteSummary.qsos,satellites=satelliteSummary.satellites,
            grids=satelliteSummary.grids,confirmed=satelliteSummary.confirmed,
            bySatellite=satelliteGrouped("satellite_name"),byMode=satelliteGrouped("satellite_mode"),
            uniqueCalls=satelliteSummary.uniqueCalls,ownGrids=satelliteSummary.ownGrids,
            byBand=satelliteGrouped("band_norm"),workedConfirmed=satelliteMatrix,
            recentActivity=db.rawQuery("SELECT utc_day,COUNT(*) FROM qso_projection p WHERE $satelliteWhere AND utc_day<>'' GROUP BY utc_day ORDER BY utc_day DESC LIMIT 30",args).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}},
        )
        val antennas=db.rawQuery("SELECT antenna_path_norm,COUNT(*),SUM(CASE WHEN paper_received=1 OR lotw_received=1 THEN 1 ELSE 0 END),AVG(NULLIF(distance_km,0)),MAX(distance_km) FROM qso_projection p WHERE $where AND antenna_path_norm<>'' GROUP BY antenna_path_norm ORDER BY COUNT(*) DESC LIMIT 50",args).use{c->buildList{while(c.moveToNext())add(AntennaAnalytics(c.getString(0),c.getInt(1),c.getInt(2),c.getDouble(3).takeUnless{c.isNull(3)},c.getDouble(4).takeUnless{c.isNull(4)}))}}
        val activity=activity(where,args,filters.period)
        val heatmap=db.rawQuery("SELECT CAST(strftime('%w',created_at,'unixepoch') AS INTEGER),CAST(strftime('%H',created_at,'unixepoch') AS INTEGER),COUNT(*) FROM qso_projection p WHERE $where GROUP BY 1,2",args).use{c->buildList{while(c.moveToNext())add(ProgressHeatCell((c.getInt(0)+6)%7,c.getInt(1),c.getInt(2)))}}
        val localHeatmap=db.rawQuery("SELECT CAST(strftime('%w',created_at,'unixepoch','localtime') AS INTEGER),CAST(strftime('%H',created_at,'unixepoch','localtime') AS INTEGER),COUNT(*) FROM qso_projection p WHERE $where GROUP BY 1,2",args).use{c->buildList{while(c.moveToNext())add(ProgressHeatCell((c.getInt(0)+6)%7,c.getInt(1),c.getInt(2)))}}
        var invalidContactGrids = 0
        val contacts=db.rawQuery("SELECT grid_norm,COUNT(*),GROUP_CONCAT(DISTINCT NULLIF(dxcc,'')),GROUP_CONCAT(DISTINCT NULLIF(country_norm,'')) FROM qso_projection p WHERE $where AND grid_norm<>'' GROUP BY grid_norm ORDER BY COUNT(*) DESC LIMIT 5000",args).use { cursor -> buildList {
            while(cursor.moveToNext()) {
                val grid=cursor.getString(0).filterNot(Char::isWhitespace).uppercase(Locale.US)
                val point=maidenheadCenter(grid)
                if(point==null) invalidContactGrids+=cursor.getInt(1) else add(ProgressContactPoint(
                    grid, point.latitude, point.longitude, cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(),
                ))
            }
        }.distinctBy(ProgressContactPoint::grid) }
        val bestDx=db.rawQuery("SELECT callsign_norm,country_norm,distance_km,band_norm,mode_norm FROM qso_projection p WHERE $where AND distance_km>0 ORDER BY distance_km DESC LIMIT 10",args).use{c->buildList{while(c.moveToNext())add(BestDxContact(c.getString(0),c.getString(1),c.getDouble(2),c.getString(3),c.getString(4)))}}
        val needsState=needsState(where,args,portable);val needs=buildNeeds(needsState,dxSpots,portableSpots,ctyLookup)
        val awards=awards(total,dxccMap,stateMap,cqMap,ituMap,continents,portable,where,args)
        val coverage=linkedMapOf("DXCC" to summary.dxccCoverage,"CQ zone" to summary.cqCoverage,"ITU zone" to summary.ituCoverage,"U.S. state" to summary.stateCoverage,"Grid" to summary.gridCoverage,"Distance" to summary.distanceCoverage,"TX power" to summary.powerCoverage,"Station profile" to summary.stationCoverage,"Portable reference" to summary.portableCoverage).mapValues{ProgressCoverage(it.value,total)}
        val base=ProgressSnapshot(totalQsos=total,uniqueCalls=uniqueCalls,dxcc=dxcc,countries=summary.countries,grids=summary.grids,longestDistanceKm=summary.longestDistanceKm,qrpQsos=summary.qrpQsos,syncAttention=syncAttention,coverage=coverage,activity=activity,bands=bands,modes=modes,heatmap=heatmap,distance=distanceBuckets(where,args),contacts=contacts,needs=needs,states=ProgressCount(stateMap.size,stateMap.count{it.value.confirmed>0}),zones=ProgressCount(cqMap.size,cqMap.count{it.value.confirmed>0}),dxccByMode=dxccBreakdown("mode_family",where,args),dxccByBand=dxccBreakdown("band_norm",where,args),qrpDxcc=summary.qrpDxcc,portable=portable,operators=operators,years=years,months=months,confirmations=confirmations,satellite=satellite,antennas=antennas,activeDays=summary.activeDays,averageDistanceKm=summary.averageDistanceKm,unconfirmedDxccCount=dxccMap.count{it.value.confirmed==0},continents=continents,cqZones=cqMap,ituZones=ituMap,gridsConfirmed=summary.gridsConfirmed,geography=geography,bestDx=bestDx,submodes=submodes,recentDays=recentDays(where,args),localHeatmap=localHeatmap,confirmationDetails=confirmationDetails,stationProfiles=stationProfiles,stationCallsigns=stationCallsigns,radios=radios,awards=awards,invalidContactGrids=invalidContactGrids,detailed=true)
        val result = base.copy(goals=goals.take(4).map{GoalProgress(it,metricValue(it.metric,base))})
        cachedKey = key
        cachedNeedsState = needsState
        cachedSnapshot = result.copy(needs = emptyList())
        return result
    }

    fun stationProfiles(): List<String> = db.rawQuery("SELECT DISTINCT station_profile_id FROM qso_projection WHERE station_profile_id<>'' ORDER BY 1",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}

    private fun aggregateSummary(where:String,args:Array<String>):ProgressAggregateSummary = db.rawQuery(
        """SELECT COUNT(*),COUNT(DISTINCT NULLIF(callsign_norm,'')),
        COALESCE(SUM(paper_received),0),COALESCE(SUM(lotw_received),0),COALESCE(SUM(eqsl_received),0),
        COALESCE(SUM(qrz_received),0),COALESCE(SUM(clublog_received),0),COALESCE(SUM(dcl_received),0),
        COALESCE(SUM(dxcc<>''),0),COALESCE(SUM(cq_zone<>''),0),COALESCE(SUM(itu_zone<>''),0),
        COALESCE(SUM(state_norm<>''),0),COALESCE(SUM(grid_norm<>''),0),COALESCE(SUM(distance_km>0),0),
        COALESCE(SUM(tx_power_w>0),0),COALESCE(SUM(station_profile_id<>'' OR station_callsign_norm<>''),0),
        COALESCE(SUM(pota_ref_norm<>'' OR sota_ref_norm<>'' OR wwff_ref_norm<>'' OR iota_norm<>''),0),
        COUNT(DISTINCT CASE WHEN dxcc<>'' AND country_norm<>'' THEN dxcc END),COUNT(DISTINCT NULLIF(grid_norm,'')),MAX(distance_km),
        COALESCE(SUM(tx_power_w BETWEEN 1 AND 5),0),COUNT(DISTINCT utc_day),AVG(NULLIF(distance_km,0)),
        COUNT(DISTINCT CASE WHEN tx_power_w BETWEEN 1 AND 5 THEN NULLIF(dxcc,'') END),
        COUNT(DISTINCT CASE WHEN paper_received=1 OR lotw_received=1 THEN NULLIF(grid_norm,'') END)
        FROM qso_projection p WHERE $where""".trimIndent(),args).use { c ->
        c.moveToFirst()
        ProgressAggregateSummary(c.getInt(0),c.getInt(1),c.getInt(2),c.getInt(3),c.getInt(4),c.getInt(5),c.getInt(6),c.getInt(7),
            c.getInt(8),c.getInt(9),c.getInt(10),c.getInt(11),c.getInt(12),c.getInt(13),c.getInt(14),c.getInt(15),c.getInt(16),
            c.getInt(17),c.getInt(18),c.getDouble(19).takeUnless { c.isNull(19) },c.getInt(20),c.getInt(21),
            c.getDouble(22).takeUnless { c.isNull(22) },c.getInt(23),c.getInt(24))
    }

    private fun satelliteSummary(where:String,args:Array<String>):SatelliteAggregateSummary = db.rawQuery(
        """SELECT COUNT(*),COUNT(DISTINCT NULLIF(satellite_name,'')),COUNT(DISTINCT NULLIF(grid_norm,'')),
        COUNT(DISTINCT CASE WHEN paper_received=1 OR lotw_received=1 THEN NULLIF(grid_norm,'') END),
        COUNT(DISTINCT NULLIF(callsign_norm,'')),COUNT(DISTINCT NULLIF(my_grid_norm,''))
        FROM qso_projection p WHERE $where""".trimIndent(),args).use { c ->
        c.moveToFirst();SatelliteAggregateSummary(c.getInt(0),c.getInt(1),c.getInt(2),c.getInt(3),c.getInt(4),c.getInt(5))
    }

    /** Compact projection-only historical aggregate; no canonical QSO decode or full-row materialisation. */
    fun bandHistory(stationProfileId: String?, stationCall: String, nowEpoch: Long = Instant.now().epochSecond): List<HamClockBandHistoricalRow> {
        val start = nowEpoch - 365L * 86_400L
        val clauses = mutableListOf("created_at>=?", "sync_state NOT IN ('TOMBSTONE','REMOTE_DELETED')", "band_norm<>''")
        val args = mutableListOf(start.toString())
        if (!stationProfileId.isNullOrBlank()) { clauses += "station_profile_id=?"; args += stationProfileId }
        else if (stationCall.isNotBlank()) { clauses += "station_callsign_norm=?"; args += stationCall.trim().uppercase(Locale.US) }
        val hour = Instant.ofEpochSecond(nowEpoch).atZone(ZoneOffset.UTC).hour
        val comparableHours = setOf((hour + 23) % 24, hour, (hour + 1) % 24).joinToString(",")
        val sql = """SELECT band_norm,mode_family,COUNT(*),COUNT(DISTINCT NULLIF(callsign_norm,'')),
            SUM(CASE WHEN paper_received=1 OR lotw_received=1 THEN 1 ELSE 0 END),
            SUM(CASE WHEN CAST(strftime('%H',created_at,'unixepoch') AS INTEGER) IN ($comparableHours) THEN 1 ELSE 0 END)
            FROM qso_projection WHERE ${clauses.joinToString(" AND ")} GROUP BY band_norm,mode_family
            ORDER BY band_norm,mode_family LIMIT 128""".trimIndent()
        return db.rawQuery(sql, args.toTypedArray()).use { cursor -> buildList {
            while (cursor.moveToNext()) add(HamClockBandHistoricalRow(cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                cursor.getInt(3), cursor.getInt(4), cursor.getInt(5), start, nowEpoch))
        } }
    }
    fun stationCallsigns(): List<String> = db.rawQuery("SELECT DISTINCT station_callsign_norm FROM qso_projection WHERE station_callsign_norm<>'' ORDER BY 1",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    fun operators(): List<String> = db.rawQuery("SELECT DISTINCT operator_norm FROM qso_projection WHERE operator_norm<>'' ORDER BY 1",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    fun submodes(): List<String> = db.rawQuery("SELECT DISTINCT COALESCE(NULLIF(submode_norm,''),mode_norm) FROM qso_projection WHERE mode_norm<>'' ORDER BY 1",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}

    private fun filterSql(f: ProgressFilters): IntelligenceFilterSql {
        val clauses = mutableListOf("1=1")
        val args = mutableListOf<String>()
        fun addIn(expression: String, values: Collection<String>) {
            if (values.isEmpty()) return
            clauses += "$expression IN (${values.joinToString(",") { "?" }})"
            args += values
        }
        if (!f.allStations && f.stationProfileId.isNotBlank()) {
            clauses += "p.station_profile_id=?"
            args += f.stationProfileId
        }
        if (f.stationCallsign.isNotBlank()) {
            clauses += "p.station_callsign_norm=?"
            args += norm(f.stationCallsign)
        }
        val bounds = progressPeriodBounds(f.period)
        bounds.fromEpoch?.let { clauses += "p.created_at>=?"; args += it.toString() }
        bounds.toEpochExclusive?.let { clauses += "p.created_at<?"; args += it.toString() }
        addIn("p.band_norm", f.selectedBands().map(::norm))
        addIn("p.mode_family", f.selectedModeFamilies().map { it.name })
        addIn("COALESCE(NULLIF(p.submode_norm,''),p.mode_norm)", f.selectedSubmodes().map(::norm))
        addIn("p.operator_norm", f.selectedOperators().map(::norm))
        val confirmationClauses = f.selectedConfirmationSources().mapNotNull { source -> when (source.uppercase()) {
            "PAPER", "QSL" -> "p.paper_received=1"
            "LOTW" -> "p.lotw_received=1"
            "EQSL" -> "p.eqsl_received=1"
            "QRZ" -> "p.qrz_received=1"
            "CLUBLOG" -> "p.clublog_received=1"
            "DCL" -> "p.dcl_received=1"
            "AWARD" -> "(p.paper_received=1 OR p.lotw_received=1)"
            "UNCONFIRMED" -> "(p.paper_received=0 AND p.lotw_received=0)"
            else -> null
        } }
        if (confirmationClauses.isNotEmpty()) clauses += confirmationClauses.joinToString(" OR ", "(", ")")
        val programmes = f.selectedPortablePrograms().map(::norm)
        if (programmes.isNotEmpty()) {
            clauses += "EXISTS(SELECT 1 FROM qso_reference r WHERE r.qso_id=p.qso_id AND r.program IN (${programmes.joinToString(",") { "?" }}))"
            args += programmes
        }
        progressDeletedSqlClause(f.includeDeleted)?.let(clauses::add)
        if (!f.includeConflicted) clauses += "p.sync_state<>'CONFLICT'"
        return IntelligenceFilterSql(clauses.joinToString(" AND "), args)
    }
    private fun portable(where: String, args: Array<String>): PortableProgress {
        fun refs(direction: String, program: String): Set<String> = db.rawQuery(
            "SELECT DISTINCT r.reference_norm FROM qso_reference r JOIN qso_projection p ON p.qso_id=r.qso_id WHERE $where AND r.direction=? AND r.program=?",
            args + arrayOf(direction, program),
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val hunted = refs("WORKED", "POTA")
        val activated = refs("MY", "POTA")
        val p2pQsos = db.rawQuery(
            "SELECT COUNT(DISTINCT p.qso_id) FROM qso_projection p JOIN qso_reference a ON a.qso_id=p.qso_id AND a.program='POTA' AND a.direction='MY' JOIN qso_reference w ON w.qso_id=p.qso_id AND w.program='POTA' AND w.direction='WORKED' WHERE $where",
            args,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val successfulActivations = db.rawQuery(
            "SELECT COUNT(*) FROM (SELECT activation_session_id FROM qso_projection p WHERE $where AND activation_program='POTA' AND activation_session_id<>'' GROUP BY activation_session_id HAVING COUNT(*)>=10)",
            args,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return PortableProgress(
            potaHunted = hunted,
            potaActivated = activated,
            sotaHunted = refs("WORKED", "SOTA"),
            wwffHunted = refs("WORKED", "WWFF"),
            p2pQsos = p2pQsos,
            successfulActivations = successfulActivations,
            nextPotaMilestone = listOf(10,20,30,40,50,75,100,200,500,1000).firstOrNull { it > hunted.size } ?: hunted.size,
            portableByBand = groupPortable("band_norm", where, args),
            portableByMode = groupPortable("mode_family", where, args),
        )
    }
    private fun groupPortable(column:String,where:String,args:Array<String>)=db.rawQuery("SELECT p.$column,COUNT(DISTINCT p.qso_id) FROM qso_projection p JOIN qso_reference r ON r.qso_id=p.qso_id WHERE $where AND p.$column<>'' GROUP BY p.$column",args).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}
    private fun needsState(where:String,args:Array<String>,portable:PortableProgress)=CompactNeedsState(set("dxcc",where,args),set("CASE WHEN paper_received=1 OR lotw_received=1 THEN dxcc ELSE '' END",where,args),pairs("dxcc||'|'||band_norm",where,args),pairs("dxcc||'|'||mode_family",where,args),set("cq_zone",where,args),set("itu_zone",where,args),portable,set("satellite_name",where,args),set("CASE WHEN paper_received=1 OR lotw_received=1 THEN satellite_name ELSE '' END",where,args))
    private fun set(expr:String,where:String,args:Array<String>)=db.rawQuery("SELECT DISTINCT $expr FROM qso_projection p WHERE $where AND ($expr)<>''",args).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    private fun pairs(expr:String,where:String,args:Array<String>)=set(expr,where,args)
    private fun buildNeeds(local:CompactNeedsState,dx:List<AndroidDXSpot>,portable:List<PortableSpot>,cty:(String)->AndroidCtyRecord?):List<ProgressNeed>{val rows=mutableListOf<ProgressNeed>();dx.forEach{s->val r=cty(s.callsign);val code=r?.dxcc.orEmpty().uppercase();if(code.isBlank())return@forEach;val reasons=buildList{if(code !in local.workedDxcc)add("NEEDED DXCC");else{if(code !in local.confirmedDxcc)add("UNCONFIRMED DXCC");if("$code|${s.band.uppercase()}" !in local.dxccBands)add("NEEDED DXCC · ${s.band}");if("$code|${progressModeFamily(s.mode)}" !in local.dxccModes)add("NEEDED DXCC · ${progressModeFamily(s.mode)}")}};if(reasons.isNotEmpty())rows+=ProgressNeed("dx:${s.id}",s.callsign,"${r?.country.orEmpty()} · ${s.band} · ${s.mode}",reasons,reasons.size*20+s.score.coerceIn(0,20),NeedTarget.DX,dxSpot=s)};portable.filter{it.activeAt(Instant.now().epochSecond)}.forEach{s->val reasons=s.references.mapNotNull{r->when(r.program){PortableProgram.POTA->"NEW POTA REFERENCE".takeIf{normalizePotaReference(r.code)!in local.portable.potaHunted};PortableProgram.SOTA->"NEW SOTA SUMMIT".takeIf{normalizeSotaReference(r.code)!in local.portable.sotaHunted};PortableProgram.WWFF->"NEW WWFF REFERENCE".takeIf{normalizeWwffReference(r.code)!in local.portable.wwffHunted}}}.distinct();if(reasons.isNotEmpty())rows+=ProgressNeed("portable:${s.id}",s.callsign,"${s.primary.code} · ${s.band} · ${s.mode}",reasons,reasons.size*20,NeedTarget.PORTABLE,portableSpot=s)};return rows.sortedByDescending(ProgressNeed::priority)}
    private fun awards(
        total: Int,
        dxcc: Map<String, ProgressCount>,
        states: Map<String, ProgressCount>,
        cq: Map<String, ProgressCount>,
        itu: Map<String, ProgressCount>,
        continents: Map<String, ProgressCount>,
        portable: PortableProgress,
        where: String,
        args: Array<String>,
    ): Map<AwardKind, AwardEstimate> {
        fun estimate(
            kind: AwardKind,
            rows: Map<String, ProgressCount>,
            target: Int? = null,
            warning: String = "",
        ) = AwardEstimate(
            kind,
            ProgressCount(rows.size, rows.count { it.value.confirmed > 0 }),
            target,
            rows.map { AwardUnit(it.key, it.key, it.value.worked, it.value.confirmed > 0) },
            coverage = ProgressCoverage(total, total),
            warning = warning,
        )
        val iota = progressMap("iota_norm", where, args)
        val sota = progressMap("sota_ref_norm", where, args)
        val wwff = progressMap("wwff_ref_norm", where, args)
        val pota = portable.potaHunted.associateWith { ProgressCount(1, 0) }
        return mapOf(
            AwardKind.DXCC to estimate(AwardKind.DXCC, dxcc, 100),
            AwardKind.WAZ to estimate(AwardKind.WAZ, cq, 40),
            AwardKind.ITU to estimate(AwardKind.ITU, itu, 90),
            AwardKind.WAC to estimate(AwardKind.WAC, continents, 6),
            AwardKind.WAS to estimate(AwardKind.WAS, states, 50),
            AwardKind.WPX to AwardEstimate(AwardKind.WPX, ProgressCount(0, 0), warning = "Prefix estimate is calculated on demand from Advanced Logbook."),
            AwardKind.IOTA to estimate(AwardKind.IOTA, iota, warning = "No worldwide IOTA denominator is bundled."),
            AwardKind.POTA to estimate(AwardKind.POTA, pota, warning = "Local hunted references; verify official programme credit in POTA."),
            AwardKind.SOTA to estimate(AwardKind.SOTA, sota, warning = "Official points and validity are not claimed."),
            AwardKind.WWFF to estimate(AwardKind.WWFF, wwff, warning = "No worldwide WWFF denominator is bundled."),
            AwardKind.QRP to AwardEstimate(AwardKind.QRP, ProgressCount(0, 0), warning = "Based only on QSOs with recorded transmit power from 1–5 W."),
        )
    }
    private fun progressMap(column:String,where:String,args:Array<String>)=db.rawQuery("SELECT $column,COUNT(*),SUM(CASE WHEN paper_received=1 OR lotw_received=1 THEN 1 ELSE 0 END) FROM qso_projection p WHERE $where AND $column<>'' GROUP BY $column",args).use{c->buildMap{while(c.moveToNext())put(c.getString(0),ProgressCount(c.getInt(1),c.getInt(2)))}}
    private fun dxccBreakdown(column:String,where:String,args:Array<String>)=db.rawQuery("SELECT $column,COUNT(DISTINCT NULLIF(dxcc,'')),COUNT(DISTINCT CASE WHEN paper_received=1 OR lotw_received=1 THEN NULLIF(dxcc,'') END) FROM qso_projection p WHERE $where AND $column<>'' GROUP BY $column",args).use{c->buildMap{while(c.moveToNext())put(c.getString(0),ProgressCount(c.getInt(1),c.getInt(2)))}}
    private fun activity(where:String,args:Array<String>,period:ProgressPeriod):List<ProgressBucket>{val column=if(period in setOf(ProgressPeriod.DAYS_30,ProgressPeriod.DAYS_90))"utc_day" else "utc_month";return db.rawQuery("SELECT $column,COUNT(*) FROM qso_projection p WHERE $where AND $column<>'' GROUP BY $column ORDER BY $column",args).use{c->buildList{while(c.moveToNext())add(ProgressBucket(c.getString(0),c.getInt(1)))}}}
    private fun recentMonths(where:String,args:Array<String>,count:Int=18):Map<String,Int>{
        val first=YearMonth.now(ZoneOffset.UTC).minusMonths((count-1).toLong())
        val found=db.rawQuery("SELECT utc_month,COUNT(*) FROM qso_projection p WHERE $where AND utc_month>=? GROUP BY utc_month",args+first.toString()).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}
        return (0 until count).associateTo(linkedMapOf()){offset->first.plusMonths(offset.toLong()).toString() to (found[first.plusMonths(offset.toLong()).toString()]?:0)}
    }
    private fun recentDays(where:String,args:Array<String>,count:Int=30):Map<String,Int>{
        val first=LocalDate.now(ZoneOffset.UTC).minusDays((count-1).toLong())
        val found=db.rawQuery("SELECT utc_day,COUNT(*) FROM qso_projection p WHERE $where AND utc_day>=? GROUP BY utc_day",args+first.toString()).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getInt(1))}}
        return (0 until count).associateTo(linkedMapOf()){offset->first.plusDays(offset.toLong()).toString() to (found[first.plusDays(offset.toLong()).toString()]?:0)}
    }
    private fun distanceBuckets(where:String,args:Array<String>):List<ProgressBucket>{val labels=listOf("<500","500–2k","2k–5k","5k–10k","10k+");val values=IntArray(5);db.rawQuery("SELECT CASE WHEN distance_km<500 THEN 0 WHEN distance_km<2000 THEN 1 WHEN distance_km<5000 THEN 2 WHEN distance_km<10000 THEN 3 ELSE 4 END,COUNT(*) FROM qso_projection p WHERE $where AND distance_km>0 GROUP BY 1",args).use{c->while(c.moveToNext())values[c.getInt(0)]=c.getInt(1)};return labels.mapIndexed{i,l->ProgressBucket(l,values[i])}}
    private fun norm(value:String)=value.trim().uppercase(Locale.US)
}
