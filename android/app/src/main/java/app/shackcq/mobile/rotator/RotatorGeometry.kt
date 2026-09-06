package app.shackcq.mobile.rotator

import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude in -90.0..90.0 && longitude in -180.0..180.0) }
}
data class BearingResult(val shortPathDeg: Double, val longPathDeg: Double, val distanceKm: Double)
data class PlannedHeading(
    val accepted: Boolean,
    val azimuthDeg: Double? = null,
    val elevationDeg: Double? = null,
    val usedBidirectionalAlternative: Boolean = false,
    val usedFlipOver: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val reason: String,
)

object RotatorGeometry {
    fun normalize360(value: Double): Double {
        require(value.isFinite())
        val result = value % 360.0
        return if (result < 0.0) result + 360.0 else result
    }

    fun angularDistance(a: Double, b: Double): Double = abs(((normalize360(b) - normalize360(a) + 540.0) % 360.0) - 180.0)

    fun maidenhead(grid: String): GeoPoint {
        val value = grid.trim().uppercase()
        require(value.length in setOf(2, 4, 6, 8) && value.length % 2 == 0)
        require(value[0] in 'A'..'R' && value[1] in 'A'..'R')
        var lon = (value[0] - 'A') * 20.0 - 180.0
        var lat = (value[1] - 'A') * 10.0 - 90.0
        var lonSize = 20.0
        var latSize = 10.0
        if (value.length >= 4) {
            require(value[2].isDigit() && value[3].isDigit())
            lonSize = 2.0; latSize = 1.0
            lon += (value[2] - '0') * lonSize
            lat += (value[3] - '0') * latSize
        }
        if (value.length >= 6) {
            require(value[4] in 'A'..'X' && value[5] in 'A'..'X')
            lonSize = 2.0 / 24.0; latSize = 1.0 / 24.0
            lon += (value[4] - 'A') * lonSize
            lat += (value[5] - 'A') * latSize
        }
        if (value.length >= 8) {
            require(value[6].isDigit() && value[7].isDigit())
            lonSize /= 10.0; latSize /= 10.0
            lon += (value[6] - '0') * lonSize
            lat += (value[7] - '0') * latSize
        }
        return GeoPoint(lat + latSize / 2.0, lon + lonSize / 2.0)
    }

    fun bearing(from: GeoPoint, to: GeoPoint): BearingResult {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        val short = normalize360(Math.toDegrees(atan2(y, x)))
        val central = 2.0 * asin(sqrt(sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)).coerceIn(0.0, 1.0))
        return BearingResult(short, normalize360(short + 180.0), 6_371.0088 * central)
    }

    fun equivalentHeadings(bearingDeg: Double, limits: RotatorLimits): List<Double> {
        val canonical = normalize360(bearingDeg)
        val minK = floor((limits.azMin - canonical) / 360.0).toInt() - 1
        val maxK = ceil((limits.azMax - canonical) / 360.0).toInt() + 1
        return (minK..maxK).map { canonical + 360.0 * it }.filter { it in limits.azMin..limits.azMax }.distinct().sorted()
    }

    fun plan(
        currentAzimuthDeg: Double,
        requestedBearingDeg: Double,
        requestedElevationDeg: Double?,
        limits: RotatorLimits,
        forbidden: List<ForbiddenSector>,
        offsetDeg: Double,
        offsetOwner: HeadingOffsetOwner,
        bidirectional: Boolean,
        flipAllowed: Boolean,
        flipCapability: Boolean,
    ): PlannedHeading {
        val appliedOffset = if (offsetOwner == HeadingOffsetOwner.ROTATOR_CONTROLLER) 0.0 else offsetDeg
        val candidates = buildList {
            equivalentHeadings(requestedBearingDeg + appliedOffset, limits).forEach { add(Triple(it, requestedElevationDeg, false)) }
            if (bidirectional) equivalentHeadings(requestedBearingDeg + appliedOffset + 180.0, limits).forEach { add(Triple(it, requestedElevationDeg, true)) }
            if (requestedElevationDeg != null && flipAllowed && flipCapability) {
                equivalentHeadings(requestedBearingDeg + appliedOffset + 180.0, limits).forEach { add(Triple(it, 180.0 - requestedElevationDeg, false)) }
            }
        }.filter { limits.contains(it.first, it.second) }
            .sortedBy { abs(it.first - currentAzimuthDeg) }
        if (candidates.isEmpty()) return PlannedHeading(false, reason = "target outside configured limits")
        var reviewCandidate: Triple<Double, Double?, Boolean>? = null
        for (candidate in candidates) {
            val crossings = forbidden.filter { crossesSector(currentAzimuthDeg, candidate.first, it) }
            if (crossings.isEmpty()) return PlannedHeading(true, candidate.first, candidate.second, candidate.third,
                requestedElevationDeg != null && candidate.second != requestedElevationDeg, false, "safe path")
            if (crossings.all { it.policy == ForbiddenSectorPolicy.REQUIRE_CONFIRMATION } && reviewCandidate == null) reviewCandidate = candidate
        }
        return reviewCandidate?.let {
            PlannedHeading(false, it.first, it.second, it.third, requestedElevationDeg != null && it.second != requestedElevationDeg, true, "forbidden sector requires operator confirmation")
        } ?: PlannedHeading(false, reason = "path crosses a rejected forbidden sector")
    }

    fun crossesSector(from: Double, to: Double, sector: ForbiddenSector): Boolean {
        if (abs(to - from) > 360.0) return true
        val steps = max(1, ceil(abs(to - from)).toInt())
        return (0..steps).any { i ->
            val point = normalize360(from + (to - from) * i / steps)
            val start = normalize360(sector.startDeg); val end = normalize360(sector.endDeg)
            if (start <= end) point in start..end else point >= start || point <= end
        }
    }
}
