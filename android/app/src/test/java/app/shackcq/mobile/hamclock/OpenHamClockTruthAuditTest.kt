package app.shackcq.mobile.hamclock

import app.shackcq.mobile.parseHamClockPropagation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OpenHamClockTruthAuditTest {
    private val root: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "docs/hamclock/upstream.json").isFile }
    }

    @Test fun parityLedgerContainsEveryPinnedPanelAndLayer() {
        val ledger = File(root, "docs/hamclock/PARITY.md").readText()
        val panels = "world-map,map-list-view,de-location,dx-location,analog-clock,solar,solar-image,solar-indices,solar-xray,lunar,propagation,propagation-chart,propagation-bars,band-conditions,band-health,band-activity,ibp,dx-cluster,psk-reporter,dxpeditions,pota,wwff,sota,wwbota,aprs,rotator,contests,ambient,rig-control,on-air,id-timer,keybindings,meshtastic,meshcom,digital-modes,winlink".split(',')
        val layers = "n3fjp_logged_qsos,wxradar,owm-clouds,citylights,earthquakes,wildfires,floods,tornado-warnings,aurora,wspr,grayline,lightning,rbn,contest_qsos,great-circle,voacap-heatmap,muf-map,satellites,meshtastic,active-users,ibp,winlink-gateways,aircraft,atc-sectors".split(',')
        panels.forEach { assertTrue("missing panel $it", ledger.contains("| `$it` /")) }
        layers.forEach { assertTrue("missing layer $it", ledger.contains("| `$it` /")) }
        assertEquals(36, panels.size)
        assertEquals(24, layers.size)
    }

    @Test fun stableAndPreviewPinsRemainSeparate() {
        val manifest = JSONObject(File(root, "docs/hamclock/upstream.json").readText())
        val stable = manifest.getJSONObject("stable")
        val preview = manifest.getJSONObject("preview")
        assertEquals("main", stable.getString("branch"))
        assertEquals("Staging", preview.getString("branch"))
        assertNotEquals(stable.getString("sha"), preview.getString("sha"))
    }

    @Test fun divergentPreviewRecordsRealMergeBase() {
        val preview = JSONObject(File(root, "docs/hamclock/upstream.json").readText()).getJSONObject("preview")
        assertEquals("diverged", preview.getString("relationship_to_stable"))
        assertEquals("1f3d8f77eba8627e36162b78a417d4c235ef2436", preview.getString("merge_base_with_stable"))
        assertEquals(36, preview.getInt("ahead_by"))
        assertEquals(51, preview.getInt("behind_by"))
    }

    @Test fun watcherDetectsPackageLicenceSecurityAndMalformedMetadata() {
        val process = ProcessBuilder(
            "python3", "-m", "unittest", "scripts/test_check_openhamclock_upstream.py"
        )
            .directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        assertTrue(output, output.contains("OK"))
    }

    @Test fun versionOneSettingsDecodeMigratesSafely() {
        val decoded = HamClockSettingsCodec.decode(
            """{"schema":"${HamClockSettingsCodec.SCHEMA}","version":1,"settings":{"panels":[{"id":"station","visible":true,"order":0,"column":0}]},"profiles":[]}"""
        )
        assertEquals(HamClockSettingsCodec.CURRENT_VERSION, decoded.version)
        assertTrue(decoded.settings.panels.any { it.id == HamClockPanelId.MAP })
        assertEquals(decoded, HamClockSettingsCodec.decode(HamClockSettingsCodec.encode(decoded)))
    }

    @Test fun unsupportedSettingsCannotClaimActiveConsumption() {
        val planned = listOf("portable.enabledPrograms")
        planned.forEach { key ->
            assertEquals(key, HamClockSettingAvailability.PLANNED_NOT_AVAILABLE, hamClockSettingAvailability[key])
        }
        listOf(
            "panels.rowSpan", "panels.columnSpan", "panels.collapsed", "map.basemap", "map.followStation",
            "map.centerLatitude", "map.centerLongitude", "map.zoom", "map.layers.visible", "map.layers.opacity",
            "dxTarget.locked", "dxTarget.source", "display.timeZoneMode", "display.hourFormat",
            "display.unitSystem", "display.lowDataMode", "cluster.enabled", "cluster.windowMinutes",
            "cluster.refreshSeconds", "cluster.maximumSpots", "cluster.filter", "pskReporter.enabled",
            "pskReporter.direction", "pskReporter.windowMinutes", "pskReporter.refreshSeconds",
            "pskReporter.maximumReports", "pskReporter.filter",
        ).forEach { key ->
            assertEquals(key, HamClockSettingAvailability.ACTIVE, hamClockSettingAvailability[key])
        }
        listOf("display.density", "display.immersive").forEach { key ->
            assertEquals(key, HamClockSettingAvailability.PARTIAL, hamClockSettingAvailability[key])
        }
    }

    @Test fun keyedCoalescerSharesResultAndPreservesIndependentRequests() {
        val coalescer = HamClockInFlightCoalescer()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sharedMarker = Any()
        val observersReady = CountDownLatch(6)
        val observersStart = CountDownLatch(1)
        val observers = Executors.newFixedThreadPool(6)
        val results = (1..6).map {
            observers.submit<Any> {
                observersReady.countDown()
                observersStart.await(2, TimeUnit.SECONDS)
                coalescer.run("contests") {
                    calls.incrementAndGet(); entered.countDown(); release.await(2, TimeUnit.SECONDS); sharedMarker
                }
            }
        }
        assertTrue(observersReady.await(2, TimeUnit.SECONDS))
        observersStart.countDown()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        release.countDown()
        results.forEach { assertSame(sharedMarker, it.get(2, TimeUnit.SECONDS)) }
        assertEquals(1, calls.get())
        val keysEntered = CountDownLatch(2)
        val keysRelease = CountDownLatch(1)
        val a = observers.submit<Int> { coalescer.run("a") { keysEntered.countDown(); keysRelease.await(); 1 } }
        val b = observers.submit<Int> { coalescer.run("b") { keysEntered.countDown(); keysRelease.await(); 2 } }
        assertTrue("different keys were serialized", keysEntered.await(2, TimeUnit.SECONDS))
        keysRelease.countDown()
        assertEquals(3, a.get() + b.get())
        observers.shutdownNow()
    }

    @Test fun coalescerClearsFailureAndPropagationRejectsMalformedSchema() {
        val coalescer = HamClockInFlightCoalescer()
        val calls = AtomicInteger()
        runCatching { coalescer.run("failure") { calls.incrementAndGet(); error("boom") } }
        assertEquals(42, coalescer.run("failure") { calls.incrementAndGet(); 42 })
        assertEquals(2, calls.get())
        assertEquals(0, coalescer.activeRequestCount())
        val rejecting = HamClockInFlightCoalescer(
            Executor { throw RejectedExecutionException("rejected") }
        )
        repeat(2) {
            val failure = runCatching { rejecting.run("rejected") { 1 } }.exceptionOrNull()
            assertTrue(failure is RejectedExecutionException)
            assertEquals("rejected", failure?.message)
            assertEquals(0, rejecting.activeRequestCount())
        }
        try {
            parseHamClockPropagation("""{"currentBands":[{"band":"20m","freq":14.1,"reliability":101,"snr":"1dB","status":"GOOD"}]}""", 1)
            fail("out-of-range reliability was accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(false)
        }
    }
}
