package app.shackcq.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class AdvancedLogbookBenchmarkTest {
    @Test fun deterministicHundredThousandRowPagingBenchmark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "advanced-logbook-benchmark.sqlite"
        context.deleteDatabase(name)
        val database = QsoDatabase(context, name)
        database.writableDatabase.beginTransaction()
        try {
            val insert = database.writableDatabase.compileStatement(
                "INSERT INTO qso(id,callsign,frequency_hz,mode,rst_sent,rst_received,created_at,name,qth,notes,country,details_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
            repeat(100_000) { index ->
                insert.clearBindings(); insert.bindString(1, "fixture-$index"); insert.bindString(2, "T$index")
                insert.bindLong(3, 14_000_000L + index); insert.bindString(4, if (index % 2 == 0) "CW" else "SSB")
                insert.bindString(5, "59"); insert.bindString(6, "59"); insert.bindLong(7, 1_700_000_000L + index)
                insert.bindString(8, "Name $index"); insert.bindString(9, "QTH ${index % 100}"); insert.bindString(10, "")
                insert.bindString(11, if (index % 2 == 0) "Slovakia" else "Australia")
                insert.bindString(12, "{\"band\":\"20m\",\"dxcc\":\"${index % 340}\",\"stationProfileId\":\"bench\"}")
                insert.executeInsert()
            }
            database.writableDatabase.setTransactionSuccessful()
        } finally { database.writableDatabase.endTransaction() }

        lateinit var page: QsoPage
        val elapsed = measureTimeMillis { page = database.page(0, 50, LogbookFilter(callsign = "T99999", sort = LogbookSort.CALLSIGN), "bench") }
        assertEquals(1, page.total); assertEquals("T99999", page.rows.single().callsign)
        assertTrue("Indexed 100k-row page query took ${elapsed}ms", elapsed < 10_000)
        database.close(); context.deleteDatabase(name)
    }
}
