# Android native and long-lived resource ownership audit

Date: 2026-08-25
Source anchor: `8582c0250188f62d683e10c156e7261a07b3dd6c`
Branch: `fix/android-native-lifecycle-hardening-v1`

## Audit rule

Every pointer-like JNI value has one Kotlin owner. A checked call holds the owner lock, retirement clears the visible handle before destruction, and close is idempotent. Every asynchronous publisher carries an owner or lifecycle generation and rejects completion after retirement. Stateless JNI calls are identified explicitly and do not pretend to own a context.

## Native ownership inventory

| Resource family | Construction | Call owner | Retirement / close | Late-publication rule | Verdict |
|---|---|---|---|---|---|
| Base CAT parser `shackcq_context` | `MainActivity.ShackCQApp` | `NativeHandleOwner` remembered by the application graph | Compose disposal closes once | Every feed/state call uses the checked lease | Hardened |
| Feature/DX `shackcq_feature_context` | `FeatureNativeSession` | `FeatureNativeSession` only | Controller cancels jobs, retires the session, then destroys once | CTY, worked-log, cluster, solar and snapshot work reject retired generation | Hardened; fixes the observed tablet UAF |
| Flex `shackcq_flex_context` | `FlexRadioController` | controller-owned `NativeHandleOwner` | socket/read/reconnect jobs cancel before owner close | read-loop publication checks owner generation and closed state | Hardened |
| Digi `shackcq_digi_context` | `DigiController` | controller-owned `NativeHandleOwner` | configuration replacement retires old context; stop/close are idempotent | RX, slot, PSK and SSTV completion check current generation | Hardened |
| Panadapter `shackcq_panadapter_context` | `PanadapterController` | controller-owned `NativeHandleOwner` | capture/replay stop before owner close | capture, replay, snapshot and route callbacks reject stale generation | Hardened |
| Embedded Hamlib radio `Session` | `HamlibSession` | private checked owner; transport bridge receives no raw escape | session/controller close bounded and idempotent | poller and bridge jobs cancel before destruction | Hardened |
| Embedded Hamlib rotator `RotatorSession` | `NativeHamlibRotatorPort` | active session contains checked owner | active entry removed before native close; bridge/poll jobs cancel | no poll/action can lease a retired session | Hardened |
| Satellite propagation | `NativeSatellite` | stateless calls; no retained native context | no native close required | provider, calculation and selection generations reject stale results | Safe, generation hardened |
| HamClock propagation adapter | `HamClockNativePropagation` | stateless bounded call | no native close required | existing controller generation and bounded result contract | Unchanged safe owner |

All handle-taking JNI functions in `native_bridge.cpp` reject zero before dereference. Array entry points bound sizes, sample rates and dimensions and return neutral values on malformed input. Hamlib entry points already validate null sessions, port configuration, transfer sizes and writable capability.

## Android resource inventory

| Owner | Long-lived resources | Scope and close contract | Verdict |
|---|---|---|---|
| `AudioMonitorController` | `AudioRecord`, `AudioTrack`, AGC, audio focus, device callback, two worker threads | Application controller; stop retires generation before release; close unregisters once | Hardened |
| `DigiController` | `AudioRecord`, RX/TX jobs, slot/PSK jobs, TX `AudioTrack`, SSTV state | Application controller; background/route loss/close stop RX and TX; TX job owns track cleanup | Hardened |
| `DigiRawRecorder` | WAV output stream and temporary recording file | Digi owner; stop/close share one cleanup path; cancellation/error closes stream | Unchanged safe owner |
| `VoiceMacroTransmitController` and audio helper | queue/repeat job, recording stream, voice `AudioTrack`, radio/audio lease | Keyer/application owner; all stop causes converge on receive cleanup; background cannot restart | Unchanged safe owner |
| `FlexRadioController`, `FlexStreamSession`, `FlexAudio`, `FlexMicTx` | TCP/UDP sockets, reader jobs, audio track/record resources | Flex controller owns session; socket close unblocks reads; child owners close from controller | Hardened controller; child owners unchanged safe |
| `FeatureController` | cluster socket, solar/CTY/worked jobs | Application controller; jobs and socket retire before native session | Hardened |
| Home Map | one remembered `MapView`, style/camera callbacks | visible workspace instance; lifecycle observer and callbacks removed on disposal | Hardened |
| Neural DX Map | one remembered `MapView` | visible workspace instance; disposal retires generation | Hardened |
| Operations Map | one remembered `MapView`, camera/marker listeners | visible workspace instance; listeners removed and generation retired | Hardened |
| Portable SOTA Map | one remembered `MapView`, marker listener | visible workspace instance; marker listener removed on disposal | Hardened |
| Portable POTA Map | one remembered `MapView`, marker listener | visible workspace instance; marker listener removed on disposal | Hardened |
| Progress / Log Intelligence Map | one remembered `MapView` | visible workspace instance; style/camera callbacks generation-gated | Hardened |
| Satellite Map | one remembered `MapView` | visible workspace instance; style and marker callbacks generation-gated | Hardened |
| `InAppBrowser` | secure `WebView`, clients and download listener | stop loading, detach clients/listener, remove parent, destroy once | Hardened |
| Flex SmartLink inspection | secure `WebView` | same deterministic destroy sequence; no JavaScript bridge | Hardened |
| QSO database | schema-16 WAL database, projection/reference/meta tables | application/shared owner; cursors use `use`; temporary test DBs delete after close | Safe; schema-16 reopen fixture added |
| Neural, Digi, Groups.io, Contest and DX Chaser stores | private SQLite helpers | feature/application owners, not map/browser screens; temporary fixtures delete databases | Unchanged safe owners; existing reopen/process-restoration fixtures retained |
| N1MM and bounded provider clients | sockets, executors and controller scopes | explicit start, background stop, idempotent close | Unchanged safe owners |

## Controller and shutdown findings

- Raw synchronous USB adapters used `runBlocking(Dispatchers.IO)` in `close()`. Managed radio and rotator owners now perform the real disconnect in their suspend lifecycle before the synchronous adapter becomes inert.
- Feature, Digi, Flex, Panadapter and Hamlib previously exposed raw handle fields or allowed unchecked timing gaps. They now use the small audited handle owner.
- Satellite, map and browser callbacks could arrive after a route, style, observer or view change. Separate lifecycle generations now reject those completions.
- Audio monitor worker callbacks could publish after stop/release and close could unregister more than once. Stop now retires publication first and close is once-only.

## Explicitly unchanged boundaries

No controller is duplicated, no radio profile gains unsupported commands, no CAT/PTT/TUNE or rotator movement is automatic, and restored state remains disconnected/inert. No WebView JavaScript bridge, file access, content access or third-party-cookie authority was added. No database destructive fallback, schema downgrade or screen-scoped production database was introduced.
