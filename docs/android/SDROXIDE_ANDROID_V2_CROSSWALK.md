# SDRoxide Android v2 Crosswalk

| Capability | ShackCQ owner | Status | Boundary |
|---|---|---|---|
| TCI safe setters/readback | `AndroidTciBackend` plus shared TCI codec | SOURCE_COMPLETE | Receive/control only; latest-write-wins; no reconnect replay |
| Receiver linking | `SdrOperationalV2` | SOURCE_COMPLETE | Explicit, at most one peer receiver |
| Dual RX audio | `TciRxAudioController` | SOURCE_COMPLETE | One Android audio owner; two bounded input queues |
| Panadapter v5/time review | `PanadapterController` plus `ReceiveTimeShiftController` | SOURCE_COMPLETE | Reduced display frames, not raw IQ |
| PSK31/RTTY skimmers | `WidebandSkimmerController` | SOURCE_COMPLETE | Four candidates per mode; candidate is not confirmed decode |
| Scan banks/priority/journal | `ReceiveOnlyScannerController` plus derived store | SOURCE_COMPLETE | Receive-only; active state never restores |
| Record on hit | time-shift capture authority | PARTIAL_PROTOCOL | IQ-display snapshot supported; audio unavailable without an owned source |
| Per-mode TX audio | `PerModeTxAudioController` | SOURCE_COMPLETE_CONFIG_ONLY | Physical send gate locked |
| TCI spots | none | UNAVAILABLE_PROTOCOL | No stable audited dialect contract |
| Diversity | none | UNAVAILABLE_PROTOCOL | No coherent receiver capability proof |

Desktop and iOS feature/UI claims are unchanged; shared codec changes are regression-tested only.
