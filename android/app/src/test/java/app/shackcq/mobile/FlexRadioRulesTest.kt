package app.shackcq.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FlexRadioRulesTest {
    @Test fun flexSessionRegistersAsAnOwningGuiBeforeSubscriptions() {
        val id = UUID.fromString("12345678-1234-4234-8234-123456789abc")
        assertEquals(
            listOf("name ShackCQ", "client program ShackCQ", "client gui $id", "client station ShackCQ"),
            FlexCommands.sessionIdentity(id),
        )
    }

    @Test fun ownedPanadapterCreationUsesTheGuiPanCommand() {
        assertEquals("display pan create freq=14.074000 x=1024 y=700", FlexCommands.createPanafall(14_074_000))
        assertNull(FlexCommands.createPanafall(0))
    }

    @Test fun realPanadapterCapacityFailureIsExplained() {
        assertEquals("No foundation receiver is available for a new panadapter", FlexCommands.responseFailure(0x50000009))
    }

    @Test fun manualLanTargetRequiresAValidIpv4AddressAndUsesTheFlexPort() {
        val target = manualFlexDiscovery(" 192.168.0.199 ")
        assertEquals("192.168.0.199", target?.ip)
        assertEquals(4992, target?.port)
        assertNull(manualFlexDiscovery("192.168.0"))
        assertNull(manualFlexDiscovery("192.168.0.256"))
        assertNull(manualFlexDiscovery("radio.example.test"))
    }

    @Test fun selectedFlexSliceMapsToSharedRadioState() {
        // Android's platform JSONObject is a stub in host-side unit tests; exercise the
        // radio-state mapping with the same decoded value used on-device.
        val snapshot = FlexSnapshot(connected = true, handle = 10940, version = "3.8", model = "FLEX-8400",
            nickname = "Remote", callsign = "OM0RX", serial = "123", firmware = "3.8",
            clients = listOf(FlexClient(256, "id", "SmartSDR", "Shack", connected = true, gui = true)),
            slices = listOf(FlexSlice(1, "B", inUse = true, active = true, tx = false, clientHandle = 256,
                frequencyHz = 14_074_000, mode = "DIGU", filterLowHz = 300, filterHighHz = 3000, rxAntenna = "ANT1")))
        val state = snapshot.toRadioState(1)
        assertTrue(state.connected)
        assertEquals("FLEX-8400", state.model)
        assertEquals(14_074_000, state.frequencyHz)
        assertEquals("DIGU", state.mode)
        assertEquals(2700, state.bandwidthHz)
        assertFalse(state.transmitting)
    }

    @Test fun absentOrTxSliceNeverBecomesControllableRadioState() {
        val tx = FlexSnapshot(connected = true, handle = 1, slices = listOf(FlexSlice(0, "A", true, true, true, 1, 7_100_000, "LSB", 100, 2800, "ANT1")))
        assertFalse(tx.toRadioState(0).connected)
        assertEquals(0, tx.toRadioState(null).frequencyHz)
    }

    @Test fun liveCommandChannelDoesNotRequireAnotherGuiStationOrSlice() {
        assertTrue(FlexSnapshot(connected = true, handle = 0x1234).hasCommandChannel)
        assertFalse(FlexSnapshot(connected = true).hasCommandChannel)
        assertFalse(FlexSnapshot(handle = 0x1234).hasCommandChannel)
    }

    @Test fun authUsesOfficialImplicitFlowAndValidatesExactRedirectState() {
        val config = SmartLinkConfig("issued-client", "frtest.auth0.com", "https://frtest.auth0.com/mobile", "smartlink.flexradio.com:443")
        val url = SmartLinkAuth.authorizationUrl(config, "expected-state")
        assertTrue(url.contains("response_type=token"))
        assertTrue(url.contains("client_id=issued-client"))
        assertFalse(url.contains("code_challenge"))
        val valid = "https://frtest.auth0.com/mobile#id_token=a.b.c&refresh_token=refresh&state=expected-state"
        assertEquals("refresh", SmartLinkAuth.validateRedirectString(config, "expected-state", valid)?.refreshToken)
        assertNull(SmartLinkAuth.validateRedirectString(config, "expected-state", "https://evil.invalid/mobile#id_token=a.b.c&refresh_token=refresh&state=expected-state"))
    }

    @Test fun brokerEnforcesOfficialRegistrationFirstAndBoundedConnect() {
        val broker = SmartLinkBrokerProtocol()
        assertNull(broker.connect("123"))
        assertEquals("application register name=ShackCQ platform=Android token=secret", broker.registration("secret"))
        assertEquals("application connect serial=123 hole_punch_port=0", broker.connect("123"))
        broker.ready()
        assertEquals(BrokerStage.READY, broker.stage)
    }

    @Test fun wanValidationIsFirstBoundedDirectMessage() {
        assertEquals("wan validate handle=abc-123\n", wanValidationFirst("abc-123"))
        assertNull(wanValidationFirst("bad handle with spaces"))
        assertNull(wanValidationFirst("0x0"))
    }

    @Test fun brokerParsersRequireRealIdentityAndBrokerSelectedEndpoint() {
        val radios = parseSmartLinkRadios(
            "radio list serial=8400-1 model=FLEX-8400 nickname=Remote_Shack callsign=OM0RX status=Available public_ip=203.0.113.45 public_tls_port=4992 public_upnp_tls_port=-1|" +
                "serial=8400-2 model=FLEX-8400 nickname=Backup status=In_Use public_ip=203.0.113.46 public_tls_port=-1 public_upnp_tls_port=443",
        )
        assertEquals(2, radios.size)
        assertEquals("Remote Shack", radios.first().nickname)
        assertEquals(4992, radios.first().tlsPort)
        assertEquals(443, radios.last().tlsPort)
        val endpoint = parseWanEndpoint("radio connect_ready serial=8400-1 handle=0xABCD", radios.first())
        assertEquals("203.0.113.45", endpoint?.host)
        assertEquals(4992, endpoint?.port)
        assertNull(parseWanEndpoint("radio connect_ready serial=other handle=0xABCD", radios.first()))
    }

    @Test fun brokerParserAcceptsStationPilotFieldAliasesAndRecordPrefixes() {
        val radios = parseSmartLinkRadios(
            "  radio list radio_name=Home_Radio callsign=OM0RX serial=8400-1 model=FLEX-8400 status=Available tls_port=4992|" +
                "name=Backup station=OM0RX serial=8400-2 model=FLEX-6400 status=Available upnp_tls_port=443  ",
        )
        assertEquals(2, radios.size)
        assertEquals("Home Radio", radios.first().nickname)
        assertEquals("OM0RX", radios.last().callsign)
        assertEquals(443, radios.last().tlsPort)
    }
}
