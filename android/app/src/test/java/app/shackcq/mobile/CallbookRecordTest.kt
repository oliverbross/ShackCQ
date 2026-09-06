package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class CallbookRecordTest {
    @Test fun mapsQrzProfileFieldsAndMergesProviderFallback() {
        val qrz = callbookRecordFromFields(mapOf(
            "call" to "OM0AAO", "fname" to "Viliam", "name" to "Petrik", "image" to "https://example.test/photo.jpg",
            "born" to "1980", "addr1" to "Ivanovce 371", "addr2" to "Ivanovce", "country" to "Slovak Republic",
            "dxcc" to "504", "cqzone" to "15", "lotw" to "1"), "OM0AAO", "QRZ")
        val ham = callbookRecordFromFields(mapOf("callsign" to "OM0AAO", "grid" to "JN98", "email" to "om0aao@example.test"),
            "OM0AAO", "HamQTH")
        val merged = mergeCallbookRecords(qrz, ham)

        assertEquals("Viliam Petrik", merged.name)
        assertEquals("https://example.test/photo.jpg", merged.imageUrl)
        assertEquals("1980", merged.born)
        assertEquals("JN98", merged.grid)
        assertEquals("om0aao@example.test", merged.email)
        assertEquals("QRZ.COM", merged.source)
    }
}
