# Android Local Log Sync Hub

Phase 4A adds direct QSO delivery for operators who use the Android local log as their logging authority.

## Authority and entry points

- Local authority may deliver to independently enabled QRZ Logbook, Club Log, and eQSL.cc destinations.
- Wavelog authority remains the sole two-way authority. Switching to Wavelog pauses every direct queue; returning to Local does not resume it without an explicit operator action.
- Open the hub from **Logbook → Sync Hub** or **Settings → Log → Open log services**. It is not a top-level navigation destination.
- Every provider defaults off. Only future operator-created Radio and POTA Activate QSOs auto-enqueue. ADIF imports and Wavelog merges do not.
- Historical delivery requires a UTC range/profile preview, destination selection, and confirmation.

## Official interfaces

| Provider | Interface | ShackCQ behaviour |
| --- | --- | --- |
| QRZ Logbook | POST https://logbook.qrz.com/api | STATUS is the read-only test; INSERT sends one record; OPTION=REPLACE is never sent; station callsign must match the configured logbook. |
| Club Log | POST https://clublog.org/realtime.php | Exactly one future QSO at normal operator pace. A 403 blocks all further traffic until credentials change and the operator resumes. |
| Club Log catch-up | POST https://clublog.org/putlogs.php | One multipart ADIF batch per confirmed catch-up; clear=1 is never sent; acceptance means submitted for later Club Log processing. |
| eQSL.cc | POST https://www.eqsl.cc/qslcard/ImportADIF.cfm | Official multipart Filename, EQSL_USER, and EQSL_PSWD fields; success is parsed from the documented result page rather than HTTP 200 alone. |

References: [QRZ Logbook API](https://www.qrz.com/docs/logbook/QRZLogbookAPI.html), [Club Log real-time](https://clublog.freshdesk.com/support/solutions/articles/54906-how-to-upload-qsos-in-real-time), [Club Log batch](https://clublog.freshdesk.com/support/solutions/articles/54905-how-to-upload-batches-of-qsos-directly-into-club-log), [Club Log IP bans](https://clublog.freshdesk.com/support/solutions/articles/3000110752-ip-address-bans), and [eQSL real-time interface](https://www.eqsl.cc/qslcard/ImportADIF.txt).

Club Log requires an application password and app API key. ShackCQ does not ship or borrow an app key; self-built installations may enter their own. QRZ requires a callsign-specific logbook key and suitable subscription.

## Storage and queue behaviour

Credentials are AES-GCM encrypted by an Android Keystore key before app-private preferences are written. Secrets are excluded from delivery rows, provider messages, diagnostics, and screenshots.

SQLite schema version 7 adds one qso_delivery row per QSO/provider. It stores queue state, UTC attempt timing, bounded sanitized provider text, payload SHA-256, HTTP status, and remote ID. The QSO row remains authoritative and one-record ADIF is regenerated at send time.

Queues run only while the app process is active: after an operator save, when the Sync Hub opens, on **Sync now**, after active-process connectivity recovery, and on the existing one-minute foreground cadence. Transient retry delays are 1 minute, 5 minutes, 15 minutes, 1 hour, and 6 hours, then manual only. Providers run independently with one request in flight per provider.

Accepted, duplicate, modified, batch-submitted, rejected, authentication-blocked, profile-required, paused, retry, and local-changed states remain durable. Acceptance updates only the matching qrzSent, clublogSent, or eqslSent compatibility flag; received/confirmation fields are untouched. Editing an accepted local QSO marks it LOCAL_CHANGED and requires an explicit **Queue updated copy** action.

Portable or different-grid eQSL records require a configured QTH nickname. Without one they remain PROFILE_REQUIRED for correction, manual ADIF handling, or later retry.

## Validation

- Local unit tests cover authority/origin gating, parsers, QRZ no-replace behaviour, Club Log response and batch rules, eQSL profile/response rules, retry cap, redaction, and sent/received flag isolation.
- Android instrumentation coverage exercises schema migration, durable/provider-independent round-trip, interrupted/edit/delete state, and compatibility flags.
- Final gate: ./gradlew :app:testDebugUnitTest :app:assembleDebug.
- Authenticated uploads are not claimed without genuine credentials and a real unsent QSO. No fake QSO is created for smoke testing.

Lenovo smoke captures:

- [Local authority](screenshots/sync-hub-local.png)
- [Wavelog authority paused](screenshots/sync-hub-wavelog-paused.png)
- [Provider configuration](screenshots/sync-hub-provider-detail.png)

The TB373FU preservation install, 11 connected instrumentation tests, Local/Wavelog/Local authority switch, masked provider surfaces, Portable Chase, POTA Activate, and SOTA approval block passed without creating a QSO, uploading a log, or transmitting. Authenticated provider delivery and historical submission were not run because no genuine configured destination and operator-selected real QSO were available.

Remote confirmation import, LoTW signing, SOTA live, iPadOS parity, and Nexus reuse remain out of scope.
