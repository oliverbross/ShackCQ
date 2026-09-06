package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeLifecycleContractTest {
    private class FakeFeatureNativeApi : FeatureNativeApi {
        val created = AtomicInteger()
        val destroyed = AtomicInteger()
        val ctyLoads = AtomicInteger()
        val workedRows = AtomicInteger()
        val clusterLines = AtomicInteger()
        val snapshots = AtomicInteger()
        val watchlists = AtomicInteger()

        override fun create() = created.incrementAndGet().toLong()
        override fun destroy(handle: Long) { destroyed.incrementAndGet() }
        override fun setWatchlist(handle: Long, value: String) { watchlists.incrementAndGet() }
        override fun loadCty(handle: Long, text: String) = true.also { ctyLoads.incrementAndGet() }
        override fun ingestClusterLine(handle: Long, value: String, epoch: Long) =
            true.also { clusterLines.incrementAndGet() }
        override fun snapshot(handle: Long, epoch: Long) = "{}".also { snapshots.incrementAndGet() }
        override fun beginWorkedSync(handle: Long) = true
        override fun addWorkedQso(handle: Long, row: WorkedLogQso) = true.also { workedRows.incrementAndGet() }
        override fun endWorkedSync(handle: Long) = true
        override fun setSolar(handle: Long, flux: Float, aIndex: Float, kpIndex: Float, epoch: Long) = Unit
    }

    @Test fun delayedCtyCompletionAfterCloseCannotEnterNativeOrPublish() {
        val api = FakeFeatureNativeApi()
        val session = FeatureNativeSession(api)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        val worker = Thread {
            release.await()
            if (session.synchronizeWorkedLog("cty", emptyList(), true)) published.incrementAndGet()
        }.apply { start() }

        session.close()
        release.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertEquals(0, api.ctyLoads.get())
        assertEquals(0, published.get())
        assertEquals(1, api.destroyed.get())
    }

    @Test fun delayedWorkedSyncAfterCloseCannotEnterNativeOrPublish() {
        val api = FakeFeatureNativeApi()
        val session = FeatureNativeSession(api)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        val row = WorkedLogQso("VK9AA", "VK9", "20m", "CW", "", 1L, false)
        val worker = Thread {
            release.await()
            if (session.synchronizeWorkedLog(null, listOf(row), true)) published.incrementAndGet()
        }.apply { start() }

        session.close()
        release.countDown()
        worker.join(2_000)

        assertEquals(0, api.workedRows.get())
        assertEquals(0, published.get())
    }

    @Test fun delayedClusterInputAfterCloseCannotEnterNativeOrPublish() {
        val api = FakeFeatureNativeApi()
        val session = FeatureNativeSession(api)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        val worker = Thread {
            release.await()
            if (session.ingestClusterLine("DX de TEST", 1L)) published.incrementAndGet()
        }.apply { start() }

        session.close()
        release.countDown()
        worker.join(2_000)

        assertEquals(0, api.clusterLines.get())
        assertEquals(0, published.get())
    }

    @Test fun closeIsIdempotentAndRejectsEveryLaterHandleCall() {
        val destroyed = AtomicInteger()
        val owner = NativeHandleOwner(17L) { destroyed.incrementAndGet() }
        owner.close()
        owner.close()

        assertEquals(NativeOwnerState.CLOSED, owner.state())
        assertEquals(1, destroyed.get())
        assertNull(owner.withHandle { it })
    }

    @Test fun concurrentCloseWaitsForTheActiveNativeCallAndDestroysOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = AtomicInteger()
        val owner = NativeHandleOwner(23L) { destroyed.incrementAndGet() }
        val caller = Thread {
            owner.withHandle {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = Thread(owner::close).apply { start() }

        assertEquals(0, destroyed.get())
        release.countDown()
        caller.join(2_000)
        closer.join(2_000)

        assertFalse(caller.isAlive)
        assertFalse(closer.isAlive)
        assertEquals(1, destroyed.get())
    }

    @Test fun oneThousandFeatureCreateCloseCyclesReturnToBaseline() {
        val api = FakeFeatureNativeApi()
        repeat(1_000) { FeatureNativeSession(api).close() }
        assertEquals(1_000, api.created.get())
        assertEquals(api.created.get(), api.destroyed.get())
    }

    @Test fun fiveHundredDigiStyleReconfigurationsRetireEveryPreviousHandle() {
        val destroyed = AtomicInteger()
        val owner = NativeHandleOwner(destroyHandle = { destroyed.incrementAndGet() })
        repeat(500) { cycle ->
            assertTrue(owner.install((cycle + 1).toLong()) != null)
        }
        owner.close()
        assertEquals(500, destroyed.get())
    }

    @Test fun fiveHundredFlexStyleConnectCloseCyclesReleaseEverySession() {
        val destroyed = AtomicInteger()
        repeat(500) { cycle ->
            NativeHandleOwner((cycle + 1).toLong()) { destroyed.incrementAndGet() }.close()
        }
        assertEquals(500, destroyed.get())
    }

    @Test fun fiveHundredSatelliteBatchesRejectEveryCancelledGeneration() {
        val lifecycle = LifecycleGeneration()
        repeat(500) {
            val batch = lifecycle.next()
            lifecycle.retire()
            assertFalse(lifecycle.isCurrent(batch))
        }
        lifecycle.close()
    }

    @Test fun oneHundredMapStyleAndCameraCallbacksAreRetiredOnDispose() {
        repeat(100) {
            val lifecycle = LifecycleGeneration()
            val style = lifecycle.next()
            val camera = lifecycle.current()
            lifecycle.close()
            assertFalse(lifecycle.isCurrent(style))
            assertFalse(lifecycle.isCurrent(camera))
        }
    }

    @Test fun configurationImportCannotResurrectAClosedOwner() {
        val destroyed = AtomicInteger()
        val owner = NativeHandleOwner(41L) { destroyed.incrementAndGet() }
        owner.close()
        assertNull(owner.install(42L))
        assertNull(owner.withHandle { it })
        assertEquals(2, destroyed.get())
    }

    @Test fun mainThreadStyleCloseIsBoundedWhenNoNativeCallIsActive() {
        val owner = NativeHandleOwner(43L) {}
        val started = System.nanoTime()
        owner.close()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("close took ${elapsedMillis}ms", elapsedMillis < 500)
    }

    @Test fun multipleOwnerCloseOrderCompletesWithoutDeadlockOrDoubleRelease() {
        val releases = AtomicInteger()
        val owners = (1L..6L).map { NativeHandleOwner(it) { releases.incrementAndGet() } }
        val threads = owners.reversed().map { owner -> Thread(owner::close).apply { start() } }
        threads.forEach { it.join(2_000) }
        assertTrue(threads.none(Thread::isAlive))
        owners.forEach { it.close() }
        assertEquals(owners.size, releases.get())
    }

    @Test fun routeOrObserverChangeRejectsAStalePublication() {
        val generation = LifecycleGeneration()
        val captured = generation.next()
        generation.retire()
        assertFalse(generation.isCurrent(captured))
        assertTrue(generation.isCurrent(generation.current()))
        generation.close()
        assertFalse(generation.isCurrent(generation.current()))
    }
}
