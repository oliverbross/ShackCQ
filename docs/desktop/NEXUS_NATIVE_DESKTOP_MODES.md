# Nexus-native desktop mode matrix

| Mode | Selected source | RX | Encoder | Package | Validation / remaining gate |
|---|---|---:|---:|---:|---|
| FT8 | Nexus `crates/ft8` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | upstream off-air fixture and null-sink encoder; physical audio/CAT pending, RF not authorized |
| FT4 | Nexus `crates/ft4` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | upstream off-air fixture and null-sink encoder; physical audio/CAT pending, RF not authorized |
| FT2 | Nexus `crates/ft2` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | synthetic Nexus encoder-to-production-adapter proof; no off-air fixture claim |
| FST4 | Nexus `crates/fst4` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | 15/30/60/120/300 s periods; synthetic Nexus encoder-to-production-adapter proof |
| FST4W | Nexus `crates/fst4` + `libtempo`, `wspr=true` | yes | null/file only | macOS/Windows/Linux source | distinct 120/300/900/1800 s beacon periods; synthetic 120 s production-adapter proof; no QSO/auto-sequence path |
| Q65 | Nexus `crates/q65` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | A-E submodes at 15/30/60/120/300 s; synthetic Nexus encoder-to-production-adapter proof |
| JT65 | Nexus `crates/jt65` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | A/B/C submodes; synthetic Nexus encoder-to-production-adapter proof |
| MSK144 | Nexus `crates/msk144` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | 5/10/15/30 s periods; synthetic Nexus encoder-to-production-adapter proof |
| WSPR | Nexus `crates/wspr` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | distinct 120 s beacon mode; synthetic codec-to-production-adapter proof; no QSO/auto-sequence path; licence caveat below |
| RTTY/PSK/SSTV/classic CW | separate upstream paths | no | no | unavailable | outside the FT8/FT4 adapter; no substitution advertised |
| DeepCW | AGPL model absent | no | no | unavailable | model licence/resource acceptance required |

Every capability is returned by runtime identity; an unavailable mode fails
explicitly. `SHACKCQ_DIGI_TX_ENABLED=false` is compiled into the runtime.
Generated waveforms are observable only as a digest through the null sink in
tests. There is no audio-output command.

The pinned Nexus workspace and WSPR crate are `GPL-3.0-only`, ship the complete
WSJT-X-derived source subset, and identify the upstream WSJT-X project-wide GPL-3
grant. Nexus's `NOTICE` also records the narrower provenance caveat: vendored
`wsprd/fano.c` and `wsprd/jelinek.c` have copyright notices but no per-file
licence grant, so their GPL-3 status is the Nexus maintainer's documented
inference from WSJT-X's project-wide licence and build. ShackCQ preserves that
notice and corresponding source; it does not present the inference as a
per-file grant. Removing WSPR remains the remedy if contrary ownership evidence
appears.
