package app.shackcq.mobile.rotator

import java.security.MessageDigest
import java.time.Instant

class RotatorProfileStore(initial: RotatorSettingsDocument = RotatorSettingsDocument()) {
    @Volatile private var document = initial
    fun snapshot(): RotatorSettingsDocument = document
    @Synchronized fun replace(imported: RotatorSettingsDocument) { document = imported.restoredSafe() }
    @Synchronized fun upsert(profile: RotatorDeviceProfile) {
        val profiles = document.profiles.filterNot { it.id == profile.id } + profile
        document = document.copy(profiles = profiles)
    }
    @Synchronized fun delete(profileId: String) {
        document = document.copy(
            profiles = document.profiles.filterNot { it.id == profileId },
            bandAssignments = document.bandAssignments.filterNot { it.rotatorProfileId == profileId },
        )
    }
}

fun interface RotatorDriverFactory { suspend fun create(profile: RotatorDeviceProfile): RotatorDriver }
class RotatorBackendRegistry {
    private val factories = mutableMapOf<RotatorBackend, RotatorDriverFactory>()
    fun register(backend: RotatorBackend, factory: RotatorDriverFactory) { factories[backend] = factory }
    suspend fun create(profile: RotatorDeviceProfile): RotatorDriver =
        factories[profile.backend]?.create(profile) ?: error("backend unavailable: ${profile.backend}")
}

class RotatorPlatformController(
    private val store: RotatorProfileStore,
    private val registry: RotatorBackendRegistry,
    private val sharedPhysicalAuthority: RotatorPhysicalAuthorityPort? = null,
    private val clock: () -> Instant = { Instant.now() },
) : RotatorReadOnlyPort, RotatorActionPort {
    private val drivers = mutableMapOf<String, RotatorDriver>()
    private val stateByProfile = mutableMapOf<String, RotatorStateSnapshot>()
    private val physicalAuthorities = mutableMapOf<String, String>()
    private val events = ArrayDeque<RotatorDiagnosticEvent>()
    private var session = RotatorAutomationSession()
    private var commandCount = 0L
    private var responseCount = 0L
    private var timeoutCount = 0L
    private var errorCount = 0L
    private var lastCommandAt: Instant? = null

    override fun states(): List<RotatorStateSnapshot> = synchronized(this) { stateByProfile.values.toList() }
    fun automationSession(): RotatorAutomationSession = session

    @Synchronized fun armAutomation() { session = RotatorAutomationSession(armed = true, armedAt = clock()); event("automation", "armed for this foreground session") }
    @Synchronized fun disarmAutomation(reason: String) { session = session.cleared(); event("automation", reason) }
    @Synchronized fun onBackground() { disarmAutomation("background cleared arm and satellite tracking") }
    @Synchronized fun onOperatingContextChanged() { disarmAutomation("operating context changed") }

    suspend fun connect(profileId: String, readOnlyProbe: Boolean = false): RotatorStateSnapshot {
        val profile = requireNotNull(store.snapshot().profiles.firstOrNull { it.id == profileId })
        val identity = physicalIdentity(profile)
        synchronized(this) {
            val activeProfile = drivers.keys.firstOrNull()
            require(activeProfile == null || activeProfile == profileId) { "another rotator profile already owns movement authority" }
            require(profileId !in drivers) { "rotator profile is already connected" }
            val owner = physicalAuthorities[identity]
            require(owner == null || owner == profileId) { "physical device already has a movement authority" }
        }
        val ownerTag = "rotator:$profileId"
        require(sharedPhysicalAuthority?.acquire(identity, ownerTag) != false) { "physical device is owned by another backend" }
        val driver = try { registry.create(profile) } catch (failure: Exception) {
            sharedPhysicalAuthority?.release(identity, ownerTag)
            throw failure
        }
        return try {
            val state = driver.connect(readOnlyProbe)
            synchronized(this) {
                drivers[profileId] = driver; stateByProfile[profileId] = state
                physicalAuthorities[identity] = profileId; responseCount++; event("connection", "profile connected read-only=$readOnlyProbe")
                if (!state.connected) session = session.cleared()
            }
            state
        } catch (failure: Exception) {
            sharedPhysicalAuthority?.release(identity, ownerTag)
            synchronized(this) { errorCount++; session = session.cleared(); event("connection", "connection failed") }
            throw failure
        }
    }

    suspend fun poll(profileId: String, generation: Long): RotatorStateSnapshot {
        val driver = synchronized(this) { drivers[profileId] } ?: error("profile disconnected")
        val state = driver.poll(generation)
        synchronized(this) {
            val previous = stateByProfile[profileId]
            if (previous == null || state.generation >= previous.generation) stateByProfile[profileId] = state
            responseCount++
        }
        return synchronized(this) { stateByProfile.getValue(profileId) }
    }

    override suspend fun submit(profileId: String, action: RotatorAction, azimuthDeg: Double?, elevationDeg: Double?): Boolean {
        val driver = synchronized(this) { drivers[profileId] } ?: return false
        val generation = synchronized(this) { (stateByProfile[profileId]?.generation ?: 0L) + 1 }
        if (action in setOf(RotatorAction.MOVE_ABSOLUTE, RotatorAction.JOG, RotatorAction.SELECT_PRESET, RotatorAction.PARK)) {
            synchronized(this) { disarmAutomation("manual physical action") }
        }
        val result = try {
            when (action) {
                RotatorAction.MOVE_ABSOLUTE -> driver.move(requireNotNull(azimuthDeg), elevationDeg, generation)
                RotatorAction.STOP -> driver.stop(generation)
                RotatorAction.PARK -> driver.park(generation)
                RotatorAction.DISCONNECT -> { disconnect(profileId); true }
                RotatorAction.SET_AUTOMATION_ARMED -> { armAutomation(); true }
                else -> false
            }
        } catch (_: Exception) { synchronized(this) { errorCount++ }; false }
        synchronized(this) {
            commandCount++; lastCommandAt = clock()
            event(action.name, if (result) "command accepted" else "command rejected or state unknown")
        }
        return result
    }

    suspend fun disconnect(profileId: String) {
        val driver = synchronized(this) { drivers.remove(profileId) } ?: return
        val profile = store.snapshot().profiles.firstOrNull { it.id == profileId }
        driver.close()
        synchronized(this) {
            physicalAuthorities.entries.removeAll { it.value == profileId }
            stateByProfile[profileId] = stateByProfile[profileId]?.copy(connected = false, ready = false,
                movement = RotatorMovementState.UNKNOWN, lastUpdate = clock()) ?: return@synchronized
            session = session.cleared(); event("connection", "profile disconnected; motion state unknown")
        }
        profile?.let { sharedPhysicalAuthority?.release(physicalIdentity(it), "rotator:$profileId") }
    }

    override fun health(): RotatorHealthSnapshot = synchronized(this) {
        val settings = store.snapshot(); val active = stateByProfile.values.firstOrNull { it.connected }
        RotatorHealthSnapshot(settings.profiles.size, stateByProfile.values.count { it.connected }, active?.profileId,
            active?.backend, active?.protocol, active?.isFresh(clock(), 5_000) == true,
            active?.movement ?: RotatorMovementState.UNKNOWN,
            settings.bandAssignments.firstOrNull { it.rotatorProfileId == active?.profileId }?.policy,
            session.armed, session.satelliteSessionActive, lastCommandAt?.let { clock().toEpochMilli() - it.toEpochMilli() },
            timeoutCount, errorCount, digest(RotatorSettingsCodec.encode(settings, false)))
    }

    fun diagnostics(): RotatorDiagnosticsSnapshot = synchronized(this) {
        RotatorDiagnosticsSnapshot(null, commandCount, responseCount, timeoutCount, "typed-capabilities",
            digest(RotatorSettingsCodec.encode(store.snapshot(), false)), events.toList())
    }

    private fun physicalIdentity(profile: RotatorDeviceProfile): String = when (profile.transport) {
        RotatorTransportKind.SERIAL -> "serial:${profile.serial?.stableIdentityHash}"
        RotatorTransportKind.TCP, RotatorTransportKind.ROTCTLD -> "tcp:${profile.tcp?.host?.lowercase()}:${profile.tcp?.port}"
        RotatorTransportKind.EMBEDDED_HAMLIB -> profile.hamlibSerial?.let { "serial:${it.stableIdentityHash}" }
            ?: profile.hamlibTcp?.let { "tcp:${it.host.lowercase()}:${it.port}" }
            ?: "hamlib:${profile.hamlibModelId}:${profile.id}"
    }
    private fun event(state: String, detail: String) {
        events.addLast(RotatorDiagnosticEvent(clock(), state.take(40), detail.take(160)))
        while (events.size > MAX_ROTATOR_DIAGNOSTIC_EVENTS) events.removeFirst()
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
}
