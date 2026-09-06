package app.shackcq.mobile.radio.hamlib

import org.junit.Assert.*
import org.junit.Test

class HamlibModelRegistryTest {
    private fun registry() = HamlibModelRegistry.parse(LIBRARY_JSON, MODELS_JSON)

    @Test fun parsesExactLibraryIdentity() {
        assertEquals("4.7.2", registry().library.version)
        assertEquals(DIGEST, registry().library.sourceDigest)
        assertEquals(37, registry().library.backendCount)
    }
    @Test fun parsesBoundedUniqueModelCatalogue() {
        assertEquals(1, registry().models.size)
        assertEquals(registry().models.size, registry().models.map { it.id }.distinct().size)
    }
    @Test fun indexesByStableId() { assertEquals("Dummy", registry().find(1)?.model) }
    @Test fun searchesManufacturerModelAndId() {
        assertEquals(1, registry().search("hamlib").size)
        assertEquals(1, registry().search("dummy").size)
        assertEquals(1, registry().search("1").size)
    }
    @Test fun reportsNoUnknownModel() { assertNull(registry().find(999999)) }
    @Test fun parsesModesFiltersRangesAndLevels() {
        val capabilities = registry().models.single().capabilities
        assertTrue(capabilities.modes.contains("USB")); assertEquals(2400, capabilities.filters.single().widthHz)
        assertEquals(1000, capabilities.ranges.single().startHz); assertTrue(capabilities.readableLevels.contains("STRENGTH"))
        assertTrue(capabilities.writableLevels.contains("AF"))
    }
    @Test fun rejectsDigestMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            HamlibModelRegistry.parse(LIBRARY_JSON.replace(DIGEST, "bad"), MODELS_JSON)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HamlibModelRegistry.parse(LIBRARY_JSON, "x".repeat(HamlibModelRegistry.MAX_REGISTRY_BYTES + 1))
        }
    }

    companion object {
        const val DIGEST = "ae1fcf2dbc80ea0786ea8f047b09399c3f7737d1930442f61a031708ed33e88f"
        const val LIBRARY_JSON = """{"version":"4.7.2","sourceDigest":"$DIGEST","licence":"LGPL-2.1-or-later","backendCount":37}"""
        const val MODELS_JSON = """{"schema":1,"version":"4.7.2","sourceDigest":"$DIGEST","backendCount":37,"modelCount":1,"models":[{"id":1,"manufacturer":"Hamlib","model":"Dummy","backend":"backend-0","backendId":0,"driverVersion":"20260101.0","status":"Stable","portType":"NONE","serialRateMin":300,"serialRateMax":3000000,"serialDataBits":8,"serialStopBits":1,"serialParity":0,"serialHandshake":0,"timeoutMs":1000,"retry":0,"pttType":0,"targetableVfo":0,"maxRitHz":0,"maxXitHz":0,"maxIfShiftHz":0,"modes":["USB"],"vfos":["VFOA"],"ranges":[{"tx":false,"startHz":1000,"endHz":1000000000,"lowMilliwatts":0,"highMilliwatts":0,"vfoMask":1,"modeMask":1}],"filters":[{"modeMask":1,"widthHz":2400}],"getLevels":["STRENGTH"],"setLevels":["AF"],"getFunctions":[],"setFunctions":[],"getParameters":[],"setParameters":[]}]}"""
    }
}
