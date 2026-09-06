# TCI Server Protocol Matrix v6

Reference: official ExpertSDR3 TCI repository, https://github.com/ExpertSDR3/TCI. The server is OFF, loopback-only, and read-only by default.

| Command | Classification | Notes |
|---|---|---|
| `protocol`, `start`, `ready` | SUPPORTED_READ | ShackCQ/TCI 1.9-compatible greeting subset |
| `vfo;` | SUPPORTED_READ | active receiver frequency |
| `modulation;` | SUPPORTED_READ | canonical mode |
| `trx;` | SUPPORTED_READ | authoritative observed PTT state |
| `vfo:<rx>,<ch>,<hz>` | SUPPORTED_SAFE_SET | short local bridge-writer arm required |
| `modulation:<rx>,<mode>` | SUPPORTED_SAFE_SET | validated mode + writer arm |
| `if`, `split_enable`, `rit`, `xit`, `rx_filter_band`, `rx_volume` setters | DIALECT_SPECIFIC | parsed/gated; unavailable owners return no success claim |
| `trx`, `tune`, `drive` setters | BLOCKED_BY_POLICY | no desktop physical TX authority in this candidate |
| TCI IQ/audio/spot exchange | UNAVAILABLE_PROTOCOL | native remote media is separate; no fabricated TCI stream |

Input is bounded to 4096 bytes per read, malformed/unknown commands are rejected, and the bridge cannot invoke an arbitrary shell.
