// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

internal enum class ProvenanceClass { INCORPORATED, BEHAVIOURAL_REFERENCE, DATA_SERVICE }

internal data class ProvenanceEntry(
    val name: String,
    val maintainers: String,
    val purpose: String,
    val licence: String,
    val pin: String,
    val sourceUrl: String,
    val classification: ProvenanceClass,
)

/** Bounded in-product view of the authoritative NOTICE/provenance manifests. */
internal val shackCQProvenance = listOf(
    ProvenanceEntry("Hamlib 4.7.2", "Hamlib contributors", "Embedded radio and rotator backends", "LGPL-2.1-or-later, file notices retained", "40f63488fe0b", "https://github.com/Hamlib/Hamlib", ProvenanceClass.INCORPORATED),
    ProvenanceEntry("dnwrnr/sgp4", "Dan Warner and contributors", "Native satellite orbit engine", "Apache-2.0", "661e057a5d36", "https://github.com/dnwrnr/sgp4", ProvenanceClass.INCORPORATED),
    ProvenanceEntry("MapLibre Native Android", "MapLibre contributors", "Native map rendering", "BSD-2-Clause", "13.0.2", "https://github.com/maplibre/maplibre-native", ProvenanceClass.INCORPORATED),
    ProvenanceEntry("Natural Earth 1:110m land", "Natural Earth contributors", "Offline land outlines for the orthographic RF globe", "Public domain", "simplified 2026-08-30", "https://www.naturalearthdata.com/downloads/110m-physical-vectors/", ProvenanceClass.INCORPORATED),
    ProvenanceEntry("OpenHamClock", "OpenHamClock contributors", "Behavioural reference for the native Home dashboard", "MIT; no source or assets copied", "d4a50eaaa61d", "https://github.com/accius/openhamclock", ProvenanceClass.BEHAVIOURAL_REFERENCE),
    ProvenanceEntry("Wavelog", "Wavelog contributors", "Behavioural reference and optional HTTPS service interoperability", "MIT; no PHP, HTML or JavaScript copied", "3.1.0@af3256140bd0", "https://github.com/wavelog/wavelog", ProvenanceClass.BEHAVIOURAL_REFERENCE),
    ProvenanceEntry("Nexus", "KD9TAW", "Reviewed Digi behavioural reference", "GPL-3.0-only; no current-review source copied", "57d11fd55f09", "https://github.com/kd9taw/Nexus", ProvenanceClass.BEHAVIOURAL_REFERENCE),
    ProvenanceEntry("Neural-DX-Watcher", "F1SMV", "Behavioural reference for Neural DX", "No permission grant found; no source or assets bundled", "fe3cba8ed9c0", "https://github.com/F1SMV/Neural-DX-Watcher", ProvenanceClass.BEHAVIOURAL_REFERENCE),
    ProvenanceEntry("OpenStreetMap / OpenFreeMap", "OSM and OpenFreeMap contributors", "Map data and styles", "ODbL attribution and provider terms", "runtime provider", "https://www.openstreetmap.org/copyright", ProvenanceClass.DATA_SERVICE),
    ProvenanceEntry("POTA / SOTA / WWFF", "Programme maintainers and contributors", "Portable spots, catalogues and official handoffs", "Provider data terms and attribution", "runtime official sources", "https://wwff.co/", ProvenanceClass.DATA_SERVICE),
    ProvenanceEntry("NOAA SWPC / NASA SDO / SatNOGS", "Agency and community contributors", "Space weather, imagery and satellite catalogue data", "Provider terms; SatNOGS CC-BY-SA-4.0", "runtime official sources", "https://www.swpc.noaa.gov/", ProvenanceClass.DATA_SERVICE),
)
