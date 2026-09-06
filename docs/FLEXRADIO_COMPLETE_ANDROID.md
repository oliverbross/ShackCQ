# FlexRadio complete Android client

## Status

Phase 5 source implementation is complete on branch `feature/android-flexradio-complete-client`. Host tests, Android unit tests, all four Android Rust ABIs, JNI/C++ linkage, debug APK assembly, installation and launch on a Lenovo TB373FU pass.

Acceptance remains **STOPPED**, but the real-radio boundary has advanced. On 2026-08-18 the tablet connected through OpenVPN to a manually configured private IPv4 address on Flex TCP port 4992. The real radio returned protocol version `1.4.0.0` and nonzero client handles; ShackCQ's name/program/GUI/station registration completed, and the command channel remained available. Live testing fixed main-thread socket writes, the missing GUI-registration sequence, real `client_id` GUI-state parsing and the source-backed `display pan create` command. The radio then rejected new owned panadapter creation with `0x50000009` (`No foundation receiver available`), so VITA panadapter/waterfall, meters and RX audio could not be accepted in this session. SmartLink remains unavailable because its live broker directory is empty. No RF/TX command was sent.

## Developer configuration

The SmartLink OAuth client registration is supplied only through ignored `flex-developer.properties` or environment variables:

~~~properties
FLEX_SMARTLINK_CLIENT_ID=...
FLEX_SMARTLINK_AUTH_DOMAIN=...
FLEX_SMARTLINK_REDIRECT_URI=...
FLEX_SMARTLINK_SERVER=...
~~~

The current developer build uses the StationPilot/AetherSDR registration after explicit authorization from a developer of those projects. The registration value is not written to tracked source, documentation, logs, screenshots, or this report. It is a public OAuth client identifier, not the operator password; the operator still authenticates in the official Auth0 browser flow.

## Connection architecture

- LAN discovery listens on UDP 4992 and parses only valid Flex discovery VITA.
- A validated manual IPv4 address can be saved in Android Radio settings for routed LAN or VPN operation when UDP discovery cannot cross the tunnel. It uses the same plain LAN TCP/VITA path on Flex port 4992 and is not hard-coded into source.
- SmartLink uses random OAuth state, exact HTTPS redirect validation, an in-memory short-lived token and an Android-Keystore-encrypted refresh token.
- Broker registration precedes list/connect requests and uses validated public TLS.
- Direct-radio TLS is socket-scoped. The self-signed leaf must be valid and self-signed; its SHA-256 fingerprint is recorded on first connection.
- A later fingerprint mismatch stops before `wan validate` and requires explicit accept or reject in the cockpit.
- `wan validate` remains the first direct WAN command.
- A nonzero client handle is required before normal subscriptions or UDP registration.
- LAN sends `client udpport <port>` once.
- After a nonzero handle, ShackCQ sends the source-backed `name`, `client program`, `client gui <session UUID>` and `client station` sequence on an I/O dispatcher before subscriptions. A nonempty real `client_id` status identifies the registered GUI even when the radio omits a synthetic `gui=1` field.
- SmartLink uses an unconnected UDP socket and sends `client udp_register handle=0x...` every 50 ms until the first structurally valid Flex-OUI VITA packet or a 30-second timeout. It then sends `client ping handle=...` every five seconds.

## VITA stream core

The reusable GPL-3.0-only Rust core and Android runtime adapter implement:

- declared-length, class-ID, timestamps and trailer-aware VITA framing;
- bounded acceptance of Flex byte-exact Opus packets whose VITA word count rounds up by at most three bytes;
- Flex OUI and registered stream/class validation;
- per-stream sequence gap and duplicate accounting;
- coverage-based FFT and waterfall assembly that rejects duplicate/overlapping bins;
- radio-`ypixels` FFT pixel-to-dBm scaling;
- 36-byte Flex waterfall metadata and signed Q7.8 dB bins;
- meter definition/status parsing and unit-aware values;
- float stereo, reduced-bandwidth mono and Opus audio dispatch;
- bounded display sizes, waterfall history and audio queues;
- exact 24 kHz, stereo, 10 ms Opus TX packet construction.

The Compose renderer uses one Canvas per spectrum/waterfall instrument. It creates no per-bin composables and shows an explicit waiting surface until registered real VITA arrives.

## Ownership and multi-slice

The cockpit has two deliberate modes:

- **ATTACH** observes and controls an explicitly selected compatible GUI station/slice without claiming ownership.
- **SHACKCQ CLIENT** requests a radio-created panafall and initial slice, records returned IDs, and may remove only those recorded IDs.

Panadapter, waterfall, slice and stream removal builders fail closed for foreign IDs. Ownership state is cleared on disconnect and raw IDs are not reused across sessions. Slice limits come from radio capability status. TX-slice assignment requires a separate confirmation and is rejected during active TX.

## Receive controls and audio

Implemented controls include frequency, mode, filter, slice audio gain/pan/mute, AGC mode/threshold, RIT, XIT, RX antenna, lock, explicit TX-slice assignment, profiles, panafall geometry/FPS/dBm range and PC audio.

PC audio requests `remote_audio_rx compression=opus`, acquires `FLEX_RX_AUDIO` from ShackCQ's exclusive audio coordinator and plays 24 kHz stereo through `AudioTrack`. Float/reduced-bandwidth paths remain supported. The jitter buffer is bounded; sequence discontinuities are counted; the fallback concealment path is bounded and never invents meter or spectrum state.

## Controlled transmit

Flex transmit defaults disabled on every process start. Enabling requires the exact phrase:

~~~text
ENABLE FLEX TRANSMIT FOR THIS SESSION
~~~

Eligibility also requires a live connection, station callsign, confirmed TX slice, valid frequency/mode, TX antenna and observed ready interlock.

One serialized controller owns states `DISABLED`, `READY`, `ARMED`, `KEYING`, `TRANSMITTING`, `TUNING`, `STOPPING` and `FAULT`. It is the only production path that issues `xmit`, `transmit tune` or CWX commands.

- Live microphone: `remote_audio_tx compression=opus`, 24 kHz stereo 10 ms Opus frames, exact unpadded VITA packets and a bounded 200 ms queue.
- Voice macros: existing private 48 kHz mono recordings are downsampled to 24 kHz, packetized through the same Opus/VITA path, run once and return to RX. There is no automatic repeat.
- CW: explicit CWX text, `cwx clear` cleanup and no arbitrary command UI.
- MOX/PTT and TUNE: explicit buttons, maximum-duration watchdogs and permanent Stop/RX access.
- Owners: `FLEX_MIC_TX`, `FLEX_VOICE_TX`, `FLEX_CW_TX` and `FLEX_TUNE`.
- Cleanup: microphone/encoder/stream release, CWX clear, TUNE off and MOX off on normal stop, failure, cancellation, backgrounding and disconnect.
- Network loss cannot confirm RX; the gate clears and `RX UNCONFIRMED` remains visible. Reconnect never automatically re-keys.

The audited production TX command surface is limited to TX-slice assignment, MOX, TUNE, power/tune power, mic/processor/TX filter/VOX/monitor, remote audio stream ownership, CWX and ATU. There is no generic arbitrary Flex command field.

## Build and test

Verified on 2026-08-18:

~~~sh
cd rust/shackcq-flex
cargo fmt -- --check
cargo test

cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug

cmake -S core -B core/build-phase5 -DCMAKE_BUILD_TYPE=Debug
cmake --build core/build-phase5 --parallel
ctest --test-dir core/build-phase5 --output-on-failure
~~~

Results:

- Rust: 14 passed, 0 failed.
- Android/JVM: 165 passed, including focused manual-IP, GUI-registration, real client-status, VITA, ownership, command-injection and TX-state tests.
- Rust Android targets: `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64` built and linked.
- CTest: 1 passed, 0 failed.
- Debug APK: assembled, installed and launched on Lenovo `TB373FU`.
- Tablet evidence: [`screenshots/phase5/flex-offline-cockpit.png`](screenshots/phase5/flex-offline-cockpit.png) and [`screenshots/phase5/flex-tx-safety-offline.png`](screenshots/phase5/flex-tx-safety-offline.png) show the no-radio state, zero live packets/meters and fail-closed TX controls from that installed build.
- Live directory evidence: [`screenshots/phase5/smartlink-empty-directory.png`](screenshots/phase5/smartlink-empty-directory.png) shows the authenticated build after the bounded broker wait, with no radios and the new explicit `REFRESH SMARTLINK` action.
- SmartLink was not retested during this VPN/LAN acceptance because the service was unavailable. Earlier authentication/refresh evidence is retained separately and is not evidence of current service availability.
- Live SmartLink directory: unavailable for this acceptance. No credentials, tokens, radio identifiers or broker values were recorded.
- OpenVPN/LAN route: passed from the Lenovo; the tunnel was active and the real radio answered across the routed private subnet.
- Manual IPv4 setting: passed on device, persisted across `adb install -r`, and appeared as a dedicated `MANUAL LAN / VPN` target.
- Real LAN command channel: passed; version, nonzero handle and GUI/station registration were observed.
- Owned panafall: stopped by the radio's real `0x50000009` foundation-receiver capacity response. No foreign display or slice was removed.
- VITA, meters and RX audio: not validated because an owned display/stream could not be allocated.
- TX: not safely run; ShackCQ remained `TX · DISABLED` and sent no MOX, TUNE, CWX, microphone or voice-macro command.

## Physical validation still required

With a real FLEX-8400, validate separately:

1. Repeat the VPN/LAN session when the radio reports an available foundation receiver, or attach explicitly to an owner-approved existing GUI station/slice, then validate owned pan/waterfall and VITA.
2. Validate real meters, Opus RX audio, receive controls, disconnect/reconnect and cleanup over the VPN route.
3. Confirm the radio is logged into the same SmartLink account and currently registered with the SmartLink service, then repeat directory discovery and connect when the Flex service is restored.
4. Exercise first-use TOFU and a controlled mismatch accept/reject fixture for SmartLink.
5. Only with a dummy load or legal clear frequency/antenna at minimum power: microphone, one voice macro, CWX, TUNE and interruption/watchdog cleanup.
6. Confirm RX at the radio after every TX test.

Until those steps exist as evidence, current SmartLink operation is **not tested during the service outage**, direct VPN/LAN command connection is **passed**, owned display/VITA/audio are **stopped at radio foundation-receiver availability**, and every RF/TX result remains **not safely run**.

## Provenance

Nexus and AetherSDR commits, file-level adaptations, licence, exclusions and endorsement disclaimers are recorded in [`rust/shackcq-flex/UPSTREAM.md`](../rust/shackcq-flex/UPSTREAM.md). ShackCQ is GPL-3.0-only. No Nexus DAX orchestration, Qt UI, assets or unconditional `transmit set dax=1` path were imported.
