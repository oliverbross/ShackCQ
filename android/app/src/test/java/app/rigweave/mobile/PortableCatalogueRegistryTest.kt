package app.rigweave.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableCatalogueRegistryTest {
    @Test fun officialWwffCsvHeaderParsesNameEntityDxccRegionAndCoordinates() {
        val csv = """reference,status,name,program,dxcc,state,county,continent,iota,iaruLocator,latitude,longitude,IUCNcat,validFrom,validTo,notes,lastMod,changeLog,reviewFlag,specialFlags,website,country,region,dxccEnum,qsoCount,lastAct
            |OMFF-0001,active,Test Protected Landscape,OMFF,504,BA,Bratislava,EU,,JN88TQ,48.15,17.11,,,,,,,,,https://wwff.co/directory/,Slovakia,Bratislava,504,12,2026-08-01
        """.trimMargin()

        val row = parsePortableCatalogueCsv(PortableCatalogueProgram.WWFF, csv).single()

        assertEquals("OMFF-0001", row.reference)
        assertEquals("Test Protected Landscape", row.name)
        assertEquals("Slovakia", row.entity)
        assertEquals("504", row.dxcc)
        assertEquals("Bratislava", row.region)
        assertEquals(48.15, row.latitudeMin!!, 0.00001)
        assertEquals(17.11, row.longitudeMin!!, 0.00001)
        assertTrue(row.officialUrl.startsWith("https://"))
    }

    @Test fun quotedWwffNamesAndMissingCoordinatesRemainSearchableWithoutFabricatedGeometry() {
        val csv = "reference,name,country,latitude,longitude\n" +
            "KFF-1234,\"Marsh, Lake and Woods\",United States,,"

        val row = parsePortableCatalogueCsv(PortableCatalogueProgram.WWFF, csv).single()

        assertEquals("Marsh, Lake and Woods", row.name)
        assertEquals("United States", row.entity)
        assertEquals(null, row.latitudeMin)
        assertEquals(null, row.longitudeMin)
    }
}
