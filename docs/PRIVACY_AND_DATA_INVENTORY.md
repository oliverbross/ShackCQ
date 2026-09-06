# Privacy and Data Inventory

## Desktop Alpha and cross-platform fixtures

Windows stores configuration, SQLite data, logs and support bundles in platform-local application directories; credentials use the platform vault and are excluded from configuration export, fixtures and support bundles. Shared fixtures contain synthetic QSO/configuration/Wavelog semantics only. Android and Windows database files are not treated as interchangeable.

## Sweep 2 private caches and settings

SCP.DB, IOTA JSON and user-selected lawful portable CSV files are stored only in app-private storage with source metadata and SHA-256. None is bundled or uploaded. Contest schema 2 stores temporary event QSOs until explicit merge. Groups.io auto-download runs only while its foreground screen is active; preview excerpts are transient Compose state and no message body enters diagnostics.

## Sweep 1 Portable and browser data

Portable SOTA may open a receive-only TCP session to cluster.sota.org.uk:7300 while the Portable workspace is foregrounded. The login value is read from the existing user-configured DX-cluster username; it is not hard-coded, copied into reports, or used to post spots. Parsed rows are bounded, expire after one hour, and are enriched from the existing app-private SOTA catalogue cache for map coordinates. POTA/SOTA in-app pages enable JavaScript and DOM storage only on exact reviewed HTTPS hosts; unreviewed redirects lose that privilege and require external-browser confirmation. Third-party cookies, file/content access, and mixed content remain disabled.

| Data | Storage owner | Export / support-bundle rule |
|---|---|---|
| QSO records and projections | SQLite, schema 13/2 | Never in configuration or support ZIP |
| Neural observations/cache | SQLite, schema 5 | Counts/bytes only |
| Digi sessions | SQLite, schema 2 | Counts/status only; no decoded content |
| Groups.io messages/drafts | SQLite, schema 2 | Counts/status only; no bodies or attachments |
| Contest sessions/serial links/derived score | `shackcq-contest.sqlite`, schema 1 | Operational DB excluded from backup/support; safe defaults only in configuration bundle |
| N1MM LAN metadata/claims | Contest store plus volatile network controller | Counts/sanitized status only; no raw XML, IP, QSO or exchange payload |
| Keyer profiles/hotkeys/voice references | Keyer preferences and private voice store | Stable definitions exportable; no audio bytes/path, queue, resolved QSO text or arm |
| DX Chaser sessions/attempts/rarity | `shackcq-dxchaser.sqlite`, schema 1 | Operational DB excluded from backup/support; no QSO truth/provider body/decode transcript |
| Wavelog token / Groups.io API key / callbook passwords | Keystore/Keychain/private credential stores | Never exported |
| Station profile and safe preferences | Shared preferences | Configuration bundle only, previewed before restore |
| Radio/TX runtime state | Volatile/controller state | Never exported; restore always disarms |
| Diagnostics | Generated support ZIP | Build, schemas, byte counts, statuses and upstream pins only |

Configuration hashes detect accidental or malicious mutation but are not signatures of trust. Import remains a deliberate local operator action.

## Network and lifecycle

- Wavelog uses the operator-configured HTTPS root; its token remains in the platform credential store.
- Groups.io uses its documented HTTPS API only after enablement/authentication; Home reads the local database and does not refresh on recomposition.
- DX cluster/RBN, PSK Reporter, DX News, solar/weather and satellite catalogue/status providers retain their existing bounded controllers, endpoint attribution, response limits, last-good rules and close/cancellation lifecycle.
- Background/foreground and connectivity changes flow through the owning controller. A malformed or oversized success response cannot replace last-good data.
- External provider/source links pass through the existing secure URL/browser policy; links do not confer transmit or authentication authority.
- Background stops Keyer/repeat CQ, pauses Contest, closes N1MM and stops Chaser; none of those active states restore after process recreation or configuration import.

## Operator controls

Local log ADIF export/import, configuration export/import and the sanitized support ZIP require deliberate operator actions. Existing cache-clear actions remove only re-fetchable provider/archive data; QSO records, drafts/outboxes and credential stores are not cleared by those controls. Data deletion remains in each owning workspace and is never triggered by navigation, a provider response or configuration restore.

## Radio and rotator data

- Radio and rotator USB identities are persisted as SHA-256 hashes; raw serial numbers are not placed in diagnostics or ordinary recovery exports.
- Hamlib model number, bounded framing, poll cadence and read-only policy are configuration data. Authentication values are not part of radio profiles.
- Rotator profiles may contain private LAN endpoints. Ordinary recovery export excludes those endpoints; restoring configuration clears connection, movement, automation-arm and tracking state.
- Diagnostics retain bounded, sanitized errors and capability/settings digests. Raw CAT frames, serial numbers, authenticated endpoints, QSO data and audio are excluded.
- QMX UAC routing is accepted only when the route proves the expected stable-device digest; microphone fallback is prohibited.

Sweep 3 adds safe alert/display profile definitions, global Contest defaults, muted Groups.io alert group identifiers, cluster counters/history-request metadata and rotator profile documents. It adds no raw cluster lines, Groups.io message bodies, credentials, private WWFF directory, CAT frames, RF payload or screen content to support bundles. Debug Groups.io alert injection is synthetic and is not persisted.

## Native lifecycle hardening data boundary

The lifecycle repair adds no persisted operator data. `NativeHandleOwner` and lifecycle generations contain only process-local numeric state. The schema-16 regression uses an isolated temporary database and deletes it after close. Package scans found no evidence directory, protected backup, test SQLite file, credential store, rigctl/rigctld executable or prohibited P.533 payload. Protected-tablet evidence records hashes, counts, UID and schema only; it must never print credential values or raw private records.

## Windows parity data boundary

The desktop adds five feature stores under the application database directory and validated provider cache files under the application cache directory. Stores contain Neural evidence/outlook, Digi decodes/drafts, Groups.io offline archive/index, Contest staging state and DX Chaser state; credentials remain alias-only in the platform vault. Deterministic demo/gallery mode uses an isolated temporary root. Support bundles and logs must not include database rows, provider response bodies, credentials, raw CAT frames or private fixtures.

## Desktop Flightline visual evidence boundary

The tablet reference directory can contain callsigns, configured provider labels and other operator-visible data. It remains under ignored `build/evidence` storage; documentation records only filenames, dimensions, navigation and sanitized layout observations. Desktop galleries use the existing isolated demo root and fake loopback TCI server. The command registry, SVG icons, menu definitions and UI tests add no operator data, credential, provider body, audio, CAT frame or hardware identifier.

## Desktop functional-owner additions

The Groups.io database stores bounded offline membership/topic/message/draft/outbox records but never bearer tokens; config stores only a credential alias. Digi audio is bounded in memory and not included in support bundles. Native CAT/rotator frames, provider response bodies, operator location, TLE source text and voice clips are excluded from diagnostics/support export. Demo and fake-service evidence is synthetic.

## Multiplatform RC1 privacy boundary

RC1 source archives, SPDX SBOMs, manifests, SHA ledgers and hosted packages contain no private evidence directory, device backup, database, credential value, provider response body, raw CAT frame or operator audio. Configuration export resets all transmission, connection, motion, session and pending-command state. Android backup rules also exclude protected stores. Authenticated live acceptance uses separate, owner-controlled evidence.

## Android SDR data

TCI profiles store display name, host, port, TLS choice, preferred I/Q rate, initial receiver, and RX route preference in app-private settings; streams never restore active. Raw TCI frames, I/Q, and RX audio are processed in bounded memory and are neither logged nor persisted. RF observations are capped and support output excludes raw payloads, credentials, private endpoints, exact private coordinates, raw audio, and raw I/Q. TTS uses installed Android system voices and bundles no voice data.

## Android SDR operational v2 derived data

`shackcq-sdr-operational-v2-derived.db` is schema 1 and separate from the canonical QSO database. It stores a bounded scanner journal and at most 256 signal bookmarks containing metadata plus reduced display traces. It stores no QSO, credential, raw CAT frame, raw IQ stream, or raw audio. Scan-bank, mixer, time-shift length, journal retention, capture bounds, and per-mode audio levels use bounded preferences. Active connection, streams, scanner, skimmers, recording, pending writes, and playback do not restore.

## Android local receiver v3 data

Raw live I/Q and demodulated audio remain only in bounded memory unless the operator explicitly starts a recording. `shackcq-local-sdr-v3.db` schema 1 stores recording metadata and relative app-private filenames, never audio blobs or QSO rows. PCM16 WAV and bounded JSON sidecars live under app-private files with a 250 MB maximum default cap, atomic finalisation, orphan cleanup and delete support. Support output excludes recordings, raw I/Q, RDS RadioText, operator notes and raw station metadata. Safe mode/filter/DSP preferences may restore; listening, offsets, recording, scanner capture, SAM acquisition and RDS sessions do not.

## Android SDR Workbench v4 data

Explicit raw I/Q captures live under app-private `files/sdr/iq-captures` as capped float32 data plus metadata. Spectrum Survey schema 2 stores derived aggregate counts/levels only in `shackcq-spectrum-survey.sqlite`; it has no raw sample, audio, decoded conversation, RadioText, QSO or credential column. Capture, replay, tracker, monitor, scanner and TCI stream state never restore active. Safe caps/retention/calibration preferences may restore.

Support bundles exclude raw I/Q, IQ/audio recordings, decoded conversations, RDS RadioText, operator notes, private paths, raw TCI payloads and private station metadata. Only bounded sanitized state, counts, sizes, latency and truth labels may be included.

## Android TCI TX v5 data

Profiles may store conservative TX limits, per-mode level preferences, monitor preference, an acceptance enum, and a sanitized endpoint/reported-device identity binding. They never store current PTT/Tune/stream state or fake acceptance. TX-audio samples and raw TCI frames are transient bounded memory only and are excluded from logs, support bundles, config export and backups intended for support. Health may expose sanitized counts, ages, queue depth, RMS/peak/clipping, latency, interlock and availability labels.

## Secure Remote Station v6 data

Station TLS/signing private keys and paired public-key records are stored through the existing desktop credential-vault boundary. Android keeps its P-256 device key non-exportable in Android Keystore and stores only bounded station metadata and certificate fingerprints. Pairing offers, challenges, sessions and leases are transient. Support/Health output is limited to sanitized identities, roles, counts, ages, latency, sequence and drop metrics; it excludes private keys, credentials, raw control frames, audio/IQ payloads and private paths.

SwiftUI stores its P-256 private key and device identifier in Apple Keychain. Qt stores client identity through Windows Credential Manager, macOS Keychain, or Linux Secret Service and refuses persistence when the platform store is unavailable. Opus/PCM audio and raw I/Q are transient bounded memory; no payload is persisted or included in backups, logs, support bundles, packages, or release assets.
