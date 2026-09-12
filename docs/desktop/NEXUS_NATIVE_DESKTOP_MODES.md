# Nexus-native desktop mode matrix

| Mode | Selected source | RX | Encoder | Package | Validation / remaining gate |
|---|---|---:|---:|---:|---|
| FT8 | Nexus `crates/ft8` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | upstream off-air fixture and null-sink encoder; physical audio/CAT pending, RF not authorized |
| FT4 | Nexus `crates/ft4` + `libtempo` | yes | null/file only | macOS/Windows/Linux source | upstream off-air fixture and null-sink encoder; physical audio/CAT pending, RF not authorized |
| FST4/FST4W | present upstream | no | no | unavailable | not wired in this bounded adapter |
| Q65 | present upstream | no | no | unavailable | not wired in this bounded adapter |
| JT65 | present upstream | no | no | unavailable | not wired in this bounded adapter |
| MSK144 | present upstream | no | no | unavailable | not wired in this bounded adapter |
| WSPR | present upstream | no | no | withheld | owner review of project-wide licence grant required |
| RTTY/PSK/SSTV/classic CW | separate upstream paths | no | no | unavailable | outside the FT8/FT4 adapter; no substitution advertised |
| DeepCW | AGPL model absent | no | no | unavailable | model licence/resource acceptance required |

Every capability is returned by runtime identity; an unavailable mode fails
explicitly. `SHACKCQ_DIGI_TX_ENABLED=false` is compiled into the runtime.
Generated waveforms are observable only as a digest through the null sink in
tests. There is no audio-output command.
