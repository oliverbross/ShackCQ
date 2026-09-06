# Remote Station Feature Crosswalk v6

| Requirement | Owner | Implementation | Evidence boundary |
|---|---|---|---|
| Protocol and media framing | shared C++ core | `remote.h`, `remote.cpp` | source + host tests |
| Identity, TLS, pairing, roles, revocation | station service + platform vault | `RemoteStationService` | deterministic/local TLS only |
| Android identity and pinning | Android Keystore + OkHttp | `RemoteIdentity`, fingerprint trust manager | source + JVM compile; live network pending |
| Session/writer/TX/rotator leases | shared authority | `SessionAuthority` | deterministic tests; physical acceptance pending |
| Headless service | Qt station owner | `shackcq-stationd` | local process/package gates |
| Remote Radio backend | Android platform owner | `RemoteStationBackend` | source + JVM tests; live station pending |
| Spectrum/waterfall | desktop Panadapter projection | derived bounded media channel | not raw I/Q |
| RX audio | desktop radio audio projection | PCM16 bounded media channel | no acoustic acceptance claim |
| TX audio/PTT/Tune | existing radio/TX owners only | rejected when owner acceptance absent | `UNAVAILABLE_PROTOCOL`/policy blocked |
| Rotator | existing rotator owner | state, stop, gated prepare | no physical movement claim |
| TCI server | station bridge | audited read + writer-gated safe set subset | fixture compatibility only |
| rigctld server | station bridge | audited Hamlib 4.7.2 subset | fixture compatibility only |
| UI/admin/Health | existing desktop Settings/Health and Android route | native panels, banner, Global Stop | visual/device review separate |
| Debug Lab | Android local fixture | `DebugRemoteLabV6` | always `DEMO · NO RADIO` |

The station never becomes a canonical log. Android retains its QSO database and Wavelog outbox.
