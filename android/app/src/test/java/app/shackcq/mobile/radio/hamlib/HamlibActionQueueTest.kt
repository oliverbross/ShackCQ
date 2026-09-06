package app.shackcq.mobile.radio.hamlib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class HamlibActionQueueTest {
    @Test fun safeSettersExposePerControlCoalescingKeys() {
        assertEquals("frequency:VFOA", HamlibAction.SetFrequency(1).coalescingKey)
        assertEquals("level:AF", HamlibAction.SetLevel("AF", .5).coalescingKey)
    }
    @Test fun normalAndEdgeActionsKeepDistinctDangerClasses() {
        val action = HamlibAction.SetFunction("NB", true); assertNull(action.coalescingKey); assertEquals(HamlibActionDanger.EDGE, action.danger)
        assertEquals(HamlibActionDanger.NORMAL, HamlibAction.SetVfo("VFOB").danger)
    }
    @Test fun pttIsTransmit() { assertEquals(HamlibActionDanger.TRANSMIT, HamlibAction.SetPtt(true).danger) }
    @Test fun tuneIsTransmit() { assertEquals(HamlibActionDanger.TRANSMIT, HamlibAction.Tune.danger) }
    @Test fun edgeActionsPreserveOrder() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<HamlibAction>())
        val queue = HamlibCommandQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { action, _ -> seen += action }
        val first = HamlibAction.SetFunction("NB", true); val second = HamlibAction.SetFunction("NB", false)
        queue.submit(first, 1); queue.submit(second, 1); delay(100)
        assertEquals(listOf(first, second), seen)
    }
    @Test fun generationTravelsWithCommand() = runBlocking {
        var seen = -1L
        val queue = HamlibCommandQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { _, generation -> seen = generation }
        queue.submit(HamlibAction.Tune, 44); delay(100); assertEquals(44, seen)
    }
    @Test fun slidersKeepLatestPendingValue() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<HamlibAction>())
        val queue = HamlibCommandQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { action, _ -> seen += action; delay(30) }
        repeat(20) { queue.submit(HamlibAction.SetLevel("AF", it / 20.0), 1) }
        delay(250); assertEquals(.95, (seen.last() as HamlibAction.SetLevel).value, .001); assertTrue(seen.size < 20)
    }
}
