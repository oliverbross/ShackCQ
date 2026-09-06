# Final Schema and Storage Matrix — ShackCQ 0.1.0 RC1

| Store | Version | Migration/restore rule | Secrets |
|---|---:|---|---|
| Android QSO/config database | 16 | Forward migrations; protected in-place upgrade only; no downgrade or destructive reset | Android Keystore aliases only |
| Apple QSO SQLite | current schema owned by QSOStore | Additive/open validation; local QSO IDs retained | Keychain only |
| Desktop QSO SQLite | 16 | Projection verification and bounded migrations | Never in database |
| Desktop configuration JSON | current | Unknown-section preview; active/TX/movement state restores disconnected/disarmed | Aliases only |
| Remote host profile | 1 | Pairing devices validate public keys/roles; raw I/Q, TX, movement never auto-arm | Host key/certificate in OS vault |
| Remote client profiles | 1 | Endpoint and 64-hex certificate pin validation; reconnect is explicit | P-256 private key in platform vault |
| Android control surfaces | 1 | Bounded mapping restore; immutable Global Stop fallback | None |

Backups and release assets exclude credentials, private keys, QSO databases, recordings, and transient sessions.

