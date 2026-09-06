package app.shackcq.mobile.rotator

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class RotatorProtocolsAndProfilesTest {
    private val serialId = "0123456789abcdef0123456789abcdef"
    private val id1 = "11111111-1111-1111-1111-111111111111"
    private val id2 = "22222222-2222-2222-2222-222222222222"

    @Test fun profileSchemaParsesSerialAndTcp() {
        val serial = RotatorDeviceProfile(id1, "Serial", RotatorBackend.NATIVE, RotatorProtocolKind.GS232,
            RotatorTransportKind.SERIAL, serial = SerialSettings(serialId))
        val tcp = RotatorDeviceProfile(id2, "TCP", RotatorBackend.NATIVE, RotatorProtocolKind.DCU_ROTOREZ,
            RotatorTransportKind.TCP, tcp = TcpSettings("192.0.2.10", 23, lanOptIn = true))
        val encoded = RotatorSettingsCodec.encode(RotatorSettingsDocument(profiles = listOf(serial, tcp)), includeLanEndpoints = true)
        val decoded = RotatorSettingsCodec.decode(encoded)
        assertEquals(2, decoded.profiles.size); assertEquals(9600, decoded.profiles[0].serial?.baud); assertEquals(23, decoded.profiles[1].tcp?.port)
        assertFalse(encoded.contains("automation")); assertFalse(encoded.contains("tracking"))
    }

    @Test fun unsupportedProfileIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RotatorDeviceProfile(id1, "Broken", RotatorBackend.NATIVE, RotatorProtocolKind.GS232,
                RotatorTransportKind.SERIAL, tcp = TcpSettings("localhost", 1, lanOptIn = true))
        }
    }

    @Test fun gs232PositionParsingIsBounded() {
        val protocol = Gs232Protocol(true)
        assertEquals(123.0, protocol.parsePosition("AZ=123 EL=045\r".toByteArray()).azimuthDeg, 0.0)
        assertThrows(IllegalArgumentException::class.java) { protocol.parsePosition(ByteArray(MAX_ROTATOR_RESPONSE_BYTES + 1) { '1'.code.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { protocol.parsePosition("noise 123 more".toByteArray()) }
    }

    @Test fun gs232AzElCommandFormatting() {
        assertEquals("W123 045\r", Gs232Protocol(true).setPosition(123.9, 45.8).single().bytes.toString(StandardCharsets.US_ASCII))
        assertEquals("M359\r", Gs232Protocol(false).setPosition(359.0).single().bytes.toString(StandardCharsets.US_ASCII))
    }

    @Test fun dcuRotorEzCommandSequence() {
        val commands = DcuRotorEzProtocol().setPosition(7.0)
        assertEquals(listOf("AP1007;", "AM1;"), commands.map { it.bytes.toString(StandardCharsets.US_ASCII) })
        assertFalse(commands.first().physicalMotion); assertTrue(commands.last().physicalMotion)
        assertEquals("AS1;", DcuRotorEzProtocol().stop().bytes.toString(StandardCharsets.US_ASCII))
    }

    @Test fun easyCommPositionParsing() {
        val position = EasyCommProtocol(EasyCommVersion.II).parsePosition("AZ123.4 EL-05.5\n".toByteArray())
        assertEquals(123.4, position.azimuthDeg, 0.001); assertEquals(-5.5, position.elevationDeg!!, 0.001)
    }

    @Test fun spidFramingMatchesReviewedFixtureAndHasNoCrcField() {
        val bytes = SpidProtocol(false).setPosition(123.0).single().bytes
        assertArrayEquals(byteArrayOf(0x57, 0x34, 0x38, 0x33, 0x30, 0, 0, 0, 0, 0, 0, 0x2f, 0x20), bytes)
        val parsed = SpidProtocol(true).parsePosition(byteArrayOf(0x57, 3, 7, 2, 5, 2, 3, 9, 4, 0, 2, 0x20))
        assertEquals(12.5, parsed.azimuthDeg, 0.0); assertEquals(34.0, parsed.elevationDeg!!, 0.0)
    }

    @Test fun rotctldRequiresExactRprt() {
        val response = RotctldProtocolCodec.parse("get_pos: \nAzimuth: 12.5\nElevation: 4.0\nRPRT 0\n".toByteArray())
        assertEquals(0, response.code); assertEquals("12.5", response.values["azimuth"])
        assertThrows(IllegalArgumentException::class.java) { RotctldProtocolCodec.parse("12.5\n4.0\n".toByteArray()) }
    }
}
