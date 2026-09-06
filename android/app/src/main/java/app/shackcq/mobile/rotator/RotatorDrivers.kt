package app.shackcq.mobile.rotator

import java.time.Instant

interface RotatorDriver {
    val profile: RotatorDeviceProfile
    val capabilities: RotatorCapabilitySnapshot
    suspend fun connect(readOnlyProbe: Boolean = false): RotatorStateSnapshot
    suspend fun poll(generation: Long): RotatorStateSnapshot
    suspend fun move(azimuthDeg: Double, elevationDeg: Double?, generation: Long): Boolean
    suspend fun stop(generation: Long): Boolean
    suspend fun park(generation: Long): Boolean
    suspend fun close()
}

class NativeRotatorDriver(
    override val profile: RotatorDeviceProfile,
    private val protocol: RotatorProtocol,
    private val transportFactory: suspend () -> RotatorTransport,
    override val capabilities: RotatorCapabilitySnapshot,
    private val now: () -> Instant = { Instant.now() },
) : RotatorDriver {
    private var transport: RotatorTransport? = null
    private var last = blankState()

    override suspend fun connect(readOnlyProbe: Boolean): RotatorStateSnapshot {
        val candidate = transportFactory()
        candidate.open()
        transport = candidate
        return runCatching { poll(last.generation + 1) }.getOrElse {
            close()
            last = blankState().copy(lastSanitizedError = "read-only connection probe failed")
            last
        }
    }

    override suspend fun poll(generation: Long): RotatorStateSnapshot {
        val active = transport ?: return blankState().copy(generation = generation)
        val command = protocol.queryPosition()
        val rule = when (protocol.kind) {
            RotatorProtocolKind.SPID_ROT1 -> RotatorResponseRule.Fixed(5)
            RotatorProtocolKind.SPID_ROT2 -> RotatorResponseRule.Fixed(12)
            else -> RotatorResponseRule.Line(if (protocol.kind == RotatorProtocolKind.EASYCOMM) '\n'.code.toByte() else '\r'.code.toByte())
        }
        val position = protocol.parsePosition(active.transact(command, rule))
        if (generation < last.generation) return last
        last = blankState().copy(connected = true, ready = true, azimuthDeg = position.azimuthDeg,
            elevationDeg = position.elevationDeg, movement = RotatorMovementState.UNKNOWN,
            lastUpdate = now(), generation = generation)
        return last
    }

    override suspend fun move(azimuthDeg: Double, elevationDeg: Double?, generation: Long): Boolean {
        require(profile.limits.contains(azimuthDeg, elevationDeg))
        val active = transport ?: return false
        for (command in protocol.setPosition(azimuthDeg, elevationDeg)) {
            active.transact(command, if (command.expectsResponse) RotatorResponseRule.Line() else RotatorResponseRule.None)
        }
        last = last.copy(targetAzimuthDeg = azimuthDeg, targetElevationDeg = elevationDeg,
            movement = RotatorMovementState.UNKNOWN, generation = maxOf(generation, last.generation))
        return true
    }

    override suspend fun stop(generation: Long): Boolean {
        val active = transport ?: return false
        val command = protocol.stop()
        active.transact(command, if (command.expectsResponse) RotatorResponseRule.Line() else RotatorResponseRule.None)
        last = last.copy(movement = RotatorMovementState.STOPPING, generation = maxOf(generation, last.generation))
        return true
    }

    override suspend fun park(generation: Long): Boolean {
        val active = transport ?: return false
        val command = protocol.park() ?: return profile.parkAzimuthDeg?.let { move(it, profile.parkElevationDeg, generation) } ?: false
        active.transact(command, if (command.expectsResponse) RotatorResponseRule.Line() else RotatorResponseRule.None)
        last = last.copy(movement = RotatorMovementState.PARKING, generation = maxOf(generation, last.generation))
        return true
    }

    override suspend fun close() { transport?.close(); transport = null; last = blankState() }
    private fun blankState() = RotatorStateSnapshot(profile.id, profile.name, profile.backend, profile.protocol,
        profile.transport, limits = profile.limits)
}

class RemoteRotctldDriver(
    override val profile: RotatorDeviceProfile,
    private val transportFactory: suspend () -> RotatorTransport,
    override val capabilities: RotatorCapabilitySnapshot,
    private val now: () -> Instant = { Instant.now() },
) : RotatorDriver {
    private var transport: RotatorTransport? = null
    private var last = blankState()
    override suspend fun connect(readOnlyProbe: Boolean): RotatorStateSnapshot {
        val candidate = transportFactory(); candidate.open(); transport = candidate
        return poll(last.generation + 1)
    }
    override suspend fun poll(generation: Long): RotatorStateSnapshot {
        val response = exchange(RotctldProtocolCodec.getPosition())
        if (response.code != 0) throw IllegalStateException("rotctld error ${response.code}")
        val az = response.values["azimuth"]?.toDoubleOrNull()
            ?: response.lines.firstOrNull { it.toDoubleOrNull() != null }?.toDouble()
            ?: throw IllegalArgumentException("rotctld position missing")
        val el = response.values["elevation"]?.toDoubleOrNull()
            ?: response.lines.dropWhile { it.toDoubleOrNull() == null }.drop(1).firstOrNull { it.toDoubleOrNull() != null }?.toDouble()
        if (generation >= last.generation) last = blankState().copy(connected = true, ready = true, azimuthDeg = az,
            elevationDeg = el, movement = RotatorMovementState.UNKNOWN, lastUpdate = now(), generation = generation)
        return last
    }
    override suspend fun move(azimuthDeg: Double, elevationDeg: Double?, generation: Long): Boolean {
        require(profile.limits.contains(azimuthDeg, elevationDeg))
        val response = exchange(RotctldProtocolCodec.setPosition(azimuthDeg, elevationDeg ?: 0.0))
        if (response.code == 0) last = last.copy(targetAzimuthDeg = azimuthDeg, targetElevationDeg = elevationDeg,
            movement = RotatorMovementState.UNKNOWN, generation = maxOf(generation, last.generation))
        return response.code == 0
    }
    override suspend fun stop(generation: Long): Boolean {
        val response = exchange(RotctldProtocolCodec.stop())
        if (response.code == 0) last = last.copy(movement = RotatorMovementState.STOPPING, generation = maxOf(generation, last.generation))
        return response.code == 0
    }
    override suspend fun park(generation: Long): Boolean {
        val response = exchange(RotctldProtocolCodec.park())
        if (response.code == 0) last = last.copy(movement = RotatorMovementState.PARKING, generation = maxOf(generation, last.generation))
        return response.code == 0
    }
    private suspend fun exchange(command: RotatorWireCommand): RotctldProtocolCodec.Response {
        val active = transport ?: throw IllegalStateException("rotctld disconnected")
        return RotctldProtocolCodec.parse(active.transact(command, RotatorResponseRule.UntilRprt()))
    }
    override suspend fun close() { transport?.close(); transport = null; last = blankState() }
    private fun blankState() = RotatorStateSnapshot(profile.id, profile.name, profile.backend, profile.protocol,
        profile.transport, limits = profile.limits)
}

class EmbeddedHamlibRotatorDriver(
    override val profile: RotatorDeviceProfile,
    private val hamlib: RotatorHamlibPort,
    override val capabilities: RotatorCapabilitySnapshot,
) : RotatorDriver {
    private var session: RotatorHamlibSession? = null
    override suspend fun connect(readOnlyProbe: Boolean): RotatorStateSnapshot {
        val opened = hamlib.open(profile, readOnlyProbe); session = opened; return hamlib.poll(opened)
    }
    override suspend fun poll(generation: Long): RotatorStateSnapshot = hamlib.poll(requireNotNull(session))
    override suspend fun move(azimuthDeg: Double, elevationDeg: Double?, generation: Long) = hamlib.setPosition(requireNotNull(session), azimuthDeg, elevationDeg)
    override suspend fun stop(generation: Long) = hamlib.stop(requireNotNull(session))
    override suspend fun park(generation: Long) = hamlib.park(requireNotNull(session))
    override suspend fun close() { session?.let { hamlib.close(it) }; session = null }
}
