package app.shackcq.mobile.contest

object InitialContestRulePacks {
    private val hf = setOf(ContestBand.B160, ContestBand.B80, ContestBand.B40, ContestBand.B20, ContestBand.B15, ContestBand.B10)
    private val sourceDigests = mapOf(
        "https://cqww.com/rules.htm" to "a018cd6734604d37f7452927cc28c1fbc80098a52f8e5373bcdce8fb139f08c4",
        "https://cqwpx.com/rules/2026_cqwpx_rules.pdf" to "38dead919408c47483dfdde6a9bd3a9a5e2ae4a54f24b6beba7b4aa2a7f1f624",
        "https://www.arrl.org/arrl-dx" to "6d9fd23c937c34361e1bc3b841a614bcd5728f77841d91858475d1ef0544d3c8",
        "https://www.arrl.org/iaru-hf-world-championship" to "37c00aab938f29e5d50b521bf2cfa9f96d1dafd312bab40e5c22c7e176bf3362",
        "https://www.arrl.org/files/file/Field-Day/2026/2026-Field-Day-Rules.pdf" to "9d89311f7911fd65a62348c587bd8718e03e7e0ea41e4ec451a035dbb19e5197",
        "https://cq160.com/rules/rules_cq160_2026.pdf" to "442b5cfeec04538cc7c4af01364968a558214e2f1e2c955455e54567e52fa45c",
        "https://www.oceaniadxcontest.com/rules" to "6f352037e6422db86bc050f6f98531ae9e6706cda5e6223d3a5c5b1bf9056711",
        "https://wwrof.org/cabrillo/" to "4ca7212eaacdf35a29a10034e8fdd047b3546475a939288ed4c9675600a53a56",
    )
    private fun source(url: String, edition: String) = ContestOfficialSource(url, edition, "2026-08-22", sourceDigests.getValue(url))
    private fun pack(
        id: String, adif: String, cabrillo: String, name: String, mode: ContestMode, family: ContestRuleFamily,
        source: ContestOfficialSource, sent: List<ContestExchangeField>, received: List<ContestExchangeField>,
        dupe: ContestDupeScope, multipliers: Set<ContestMultiplierType>, bands: Set<ContestBand> = hf,
        regions: Set<ContestEntryRegion> = setOf(ContestEntryRegion.WORLDWIDE), serial: Boolean = false,
        ambiguities: List<String> = emptyList(), allowedModes: Set<ContestMode> = if (mode == ContestMode.MIXED) setOf(ContestMode.CW, ContestMode.SSB) else setOf(mode),
    ) = ContestRulePack(ContestDefinition(ContestDefinitionId(id), adif, cabrillo, name, mode, ContestRuleVersion("1.0.0"), family,
        listOf(source), regions, bands, allowedModes,
        sent, received, dupe, multipliers, serial, ambiguities), listOf("$id-exchange", "$id-score", "$id-cabrillo"))

    private val cqWw = source("https://cqww.com/rules.htm", "2025 current published rules; 2026 edition pending")
    private val cqWpx = source("https://cqwpx.com/rules/2026_cqwpx_rules.pdf", "2026")
    private val arrlDx = source("https://www.arrl.org/arrl-dx", "2026 event rules")
    private val iaru = source("https://www.arrl.org/iaru-hf-world-championship", "2026")
    private val fieldDay = source("https://www.arrl.org/files/file/Field-Day/2026/2026-Field-Day-Rules.pdf", "2026 revised 2026-03-01")
    private val cq160 = source("https://cq160.com/rules/rules_cq160_2026.pdf", "2026")
    private val oceania = source("https://www.oceaniadxcontest.com/rules", "2026 sponsor page; downloadable rule PDF unresolved")
    private val rstZone = listOf(ContestExchangeField.RST, ContestExchangeField.CQ_ZONE)
    private val rstSerial = listOf(ContestExchangeField.RST, ContestExchangeField.SERIAL)

    val all: List<ContestRulePack> = listOf(
        pack("cq-ww-cw", "CQ-WW-CW", "CQ-WW-CW", "CQ World Wide DX — CW", ContestMode.CW, ContestRuleFamily.CQ_WW, cqWw, rstZone, rstZone,
            ContestDupeScope.ONCE_PER_BAND, setOf(ContestMultiplierType.CQ_ZONE, ContestMultiplierType.DXCC), ambiguities=listOf("Confirm the 2026 sponsor edition before a 2026 live session")),
        pack("cq-ww-ssb", "CQ-WW-SSB", "CQ-WW-SSB", "CQ World Wide DX — SSB", ContestMode.SSB, ContestRuleFamily.CQ_WW, cqWw, rstZone, rstZone,
            ContestDupeScope.ONCE_PER_BAND, setOf(ContestMultiplierType.CQ_ZONE, ContestMultiplierType.DXCC), ambiguities=listOf("Confirm the 2026 sponsor edition before a 2026 live session")),
        pack("cq-wpx-cw", "CQ-WPX-CW", "CQ-WPX-CW", "CQ WPX — CW", ContestMode.CW, ContestRuleFamily.CQ_WPX, cqWpx, rstSerial, rstSerial,
            ContestDupeScope.ONCE_PER_BAND, setOf(ContestMultiplierType.PREFIX), serial=true),
        pack("cq-wpx-ssb", "CQ-WPX-SSB", "CQ-WPX-SSB", "CQ WPX — SSB", ContestMode.SSB, ContestRuleFamily.CQ_WPX, cqWpx, rstSerial, rstSerial,
            ContestDupeScope.ONCE_PER_BAND, setOf(ContestMultiplierType.PREFIX), serial=true),
        pack("arrl-dx-cw", "ARRL-DX-CW", "ARRL-DX-CW", "ARRL International DX — CW", ContestMode.CW, ContestRuleFamily.ARRL_DX, arrlDx,
            listOf(ContestExchangeField.RST, ContestExchangeField.POWER), listOf(ContestExchangeField.RST, ContestExchangeField.STATE_PROVINCE),
            ContestDupeScope.ONCE_PER_BAND, setOf(ContestMultiplierType.DXCC, ContestMultiplierType.STATE_PROVINCE), regions=setOf(ContestEntryRegion.WVE, ContestEntryRegion.DX)),
        pack("arrl-dx-ssb", "ARRL-DX-SSB", "ARRL-DX-SSB", "ARRL International DX — SSB", ContestMode.SSB, ContestRuleFamily.ARRL_DX, arrlDx,
            listOf(ContestExchangeField.RST, ContestExchangeField.POWER), listOf(ContestExchangeField.RST, ContestExchangeField.STATE_PROVINCE),
            ContestDupeScope.ONCE_PER_BAND, setOf(ContestMultiplierType.DXCC, ContestMultiplierType.STATE_PROVINCE), regions=setOf(ContestEntryRegion.WVE, ContestEntryRegion.DX)),
        pack("iaru-hf", "IARU-HF", "IARU-HF", "IARU HF World Championship", ContestMode.MIXED, ContestRuleFamily.IARU_HF, iaru,
            listOf(ContestExchangeField.RST, ContestExchangeField.ITU_ZONE), listOf(ContestExchangeField.RST, ContestExchangeField.ITU_ZONE, ContestExchangeField.HQ_ABBREVIATION),
            ContestDupeScope.ONCE_PER_BAND_MODE, setOf(ContestMultiplierType.ITU_ZONE, ContestMultiplierType.HQ_SOCIETY)),
        pack("arrl-field-day", "ARRL-FIELD-DAY", "ARRL-FD", "ARRL Field Day", ContestMode.MIXED, ContestRuleFamily.ARRL_FIELD_DAY, fieldDay,
            listOf(ContestExchangeField.CLASS, ContestExchangeField.ARRL_SECTION), listOf(ContestExchangeField.CLASS, ContestExchangeField.ARRL_SECTION),
            ContestDupeScope.ONCE_PER_BAND_MODE, setOf(ContestMultiplierType.ARRL_SECTION), bands=hf + setOf(ContestBand.B6, ContestBand.B2), regions=setOf(ContestEntryRegion.IARU_REGION_2), allowedModes=setOf(ContestMode.CW,ContestMode.SSB,ContestMode.DIGITAL)),
        pack("cq-160-cw", "CQ-160-CW", "CQ-160-CW", "CQ 160-Meter — CW", ContestMode.CW, ContestRuleFamily.CQ_160, cq160,
            rstZone, listOf(ContestExchangeField.RST, ContestExchangeField.STATE_PROVINCE, ContestExchangeField.CQ_ZONE), ContestDupeScope.ONCE_PER_CONTEST,
            setOf(ContestMultiplierType.STATE_PROVINCE, ContestMultiplierType.DXCC), bands=setOf(ContestBand.B160)),
        pack("cq-160-ssb", "CQ-160-SSB", "CQ-160-SSB", "CQ 160-Meter — SSB", ContestMode.SSB, ContestRuleFamily.CQ_160, cq160,
            rstZone, listOf(ContestExchangeField.RST, ContestExchangeField.STATE_PROVINCE, ContestExchangeField.CQ_ZONE), ContestDupeScope.ONCE_PER_CONTEST,
            setOf(ContestMultiplierType.STATE_PROVINCE, ContestMultiplierType.DXCC), bands=setOf(ContestBand.B160)),
        pack("oceania-dx-cw", "OCEANIA-DX-CW", "OCEANIA-DX-CW", "Oceania DX — CW", ContestMode.CW, ContestRuleFamily.OCEANIA_DX, oceania,
            rstSerial, rstSerial, ContestDupeScope.ONCE_PER_BAND, setOf(ContestMultiplierType.PREFIX), serial=true,
            ambiguities=listOf("Sponsor rules download did not resolve during the 2026-08-22 audit; validate against the published 2026 PDF before live use")),
        pack("oceania-dx-ssb", "OCEANIA-DX-SSB", "OCEANIA-DX-SSB", "Oceania DX — SSB", ContestMode.SSB, ContestRuleFamily.OCEANIA_DX, oceania,
            rstSerial, rstSerial, ContestDupeScope.ONCE_PER_BAND, setOf(ContestMultiplierType.PREFIX), serial=true,
            ambiguities=listOf("Sponsor rules download did not resolve during the 2026-08-22 audit; validate against the published 2026 PDF before live use")),
        pack("general-dx-serial", "SHACKCQ-GENERAL", "DX", "General DX/Serial Session (non-award)", ContestMode.MIXED, ContestRuleFamily.GENERAL_SERIAL,
            source("https://wwrof.org/cabrillo/", "Cabrillo V3 framework; non-award session"), rstSerial, rstSerial,
            ContestDupeScope.ONCE_PER_BAND_MODE, emptySet(), serial=true, allowedModes=setOf(ContestMode.CW,ContestMode.SSB,ContestMode.DIGITAL))
    )
}
