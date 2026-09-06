# ShackCQ Final Convergence

Baseline: frozen `origin/main` `cd9994bcef97006380dab84de63504cc9ce47a95`. The convergence branch owns integration only; domain controllers remain authoritative.

The Android app now creates one generation-stamped `OperatingContextSnapshot` from station identity, activation, radio, network, QSO, Wavelog and provider truth. Typed `WorkspaceAction` values are resolved by one router. A route may select a workspace and prepare receive review, but cannot key PTT, tune, transmit, post, or log.

Android Settings exposes configuration-only export, hash verification, per-section preview/selection, mapping tasks, transactional rollback, and a disarmed post-restore state. Credentials, QSO/message bodies, caches and transmit-armed state are excluded.

System Health reports exact schema versions, database sizes, source ages/status and safe recovery actions. Its support ZIP is diagnostic metadata only and is scrubbed of credential-like keys and user content.

Apple retains adaptive navigation and now exposes Home, Neural DX, Log Intelligence, Sync and Operations backed by the existing radio, logbook, Wavelog, DX and Groups.io models. Satellite-pass prediction remains an explicit Apple platform gap; no synthetic pass or RF assertion is shown.

Evidence boundary: source and host-build gates are recorded here. Device UI, authenticated services, live RF, CAT/PTT and release distribution require the separate live checklist.
