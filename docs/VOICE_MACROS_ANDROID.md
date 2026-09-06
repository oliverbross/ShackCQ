# Android SSB voice macros

Keyer v2 adds logical composite plans that reference the same six private slots. Every clip is validated before PTT, plans are capped at 12 references/45 seconds, and playback uses the existing single `VoiceMacroTransmitController` TX/RX lifecycle. The legacy one-slot API is a one-item adapter. Configuration recovery exports slot references, never WAV bytes or paths. See `docs/keyer/KEYER_HOTKEYS_V2.md`.

ShackCQ for Android provides six private, tablet-local voice slots for USB and LSB operation on an Elecraft KX3/KX2. A slot can be recorded with the built-in microphone or imported from an uncompressed PCM WAV, previewed only through the built-in speaker, and sent through an explicitly selected DigiRig USB audio output.

## Signal and control architecture

The transmit path has one PTT owner:

```text
tablet canonical PCM -> left USB channel -> DigiRig audio -> KX3/KX2 MIC
tablet digital zero  -> right USB channel
ShackCQ CAT         -> TX; / TQ; / RX; verification
```

V1 deliberately uses Elecraft CAT PTT. It does not use DigiRig RTS, DTR, CM108 GPIO, VOX, a right-channel tone, or an automatic fallback. The selected serial port is opened at 38,400 8N1; supported RTS and DTR lines are immediately driven inactive and checked before CAT is accepted. If their inactive state cannot be established, connection fails closed.

Elecraft defines `TX;` as entering transmit like PTT/XMIT, `RX;` as terminating transmit, and `TQ0;`/`TQ1;` as the compact receive/transmit status response. ShackCQ therefore uses a fresh-response sequence:

1. require exact USB or LSB mode, foreground state, CAT, a valid recording, and a unique selected USB output;
2. acquire the exclusive `VOICE_TX` audio lease, pausing only a running receive monitor through the central coordinator; reject every other owner before PTT;
3. require a fresh `TQ0;` without taking ownership of an already-transmitting radio;
4. preload and validate the complete macro;
5. start with digital silence and verify the actual Android route is the selected USB sink;
6. send `TX;`, require fresh `TQ1;`, add 175 ms lead silence, then stream speech on left with right held at zero;
7. add 125 ms trailing silence, flush audio, send `RX;`, and require fresh `TQ0;`;
8. on Stop, backgrounding, route/focus loss, CAT error, watchdog, or exception, halt non-zero audio, make up to two RX/verification attempts from non-cancellable cleanup, and release the lease exactly once.

If RX still cannot be confirmed, ShackCQ shows a persistent warning instructing the operator to use the radio's physical RX/XMIT control or remove PTT.

## Supported hardware arrangements

- KXUSB or another compatible Android USB serial adapter for CAT, plus DigiRig for USB audio.
- DigiRig Mobile's CP210x serial interface for CAT and its USB audio interface, only when the DigiRig revision, solder configuration, and Elecraft serial cable provide the RS-232 levels expected by the KX3/KX2.

The DigiRig audio cable connects its radio-audio side to the Elecraft PHONES and MIC sockets. CAT is a separate serial connection even when both functions are inside one DigiRig enclosure. DigiRig Mobile revisions before the RS-232 converter was introduced may support audio/PTT wiring but not KX CAT through the serial jack; confirm the exact hardware revision and cable documentation.

## Selecting devices

Settings -> Safety lists every supported serial port with driver family, reported manufacturer/product, VID:PID, serial number when permission exposes it, and port index. Choose the intended adapter, then use **Save CAT adapter**; the screen marks a changed choice as unsaved until that explicit action succeeds. Settings -> Audio separately selects:

- RX monitor USB input;
- voice macro TX USB output;
- fixed recording input: built-in tablet microphone;
- fixed preview output: built-in tablet speaker.

ShackCQ first uses one exact persisted stable signature, otherwise auto-selects only when exactly one eligible candidate exists. Multiple candidates or duplicate saved identities require an explicit choice. Transient Android device IDs are used only to remember the current attachment/session. Rescanning is event-driven through Android audio-device callbacks, with manual Rescan actions available.

Changing CAT or voice TX selection disconnects/aborts the active operation and clears all transmit arms. A selected route disappearing or becoming ambiguous fails closed.

The receive monitor, panadapter, EQ capture/playback, voice record/import/preview, and voice TX are mutually exclusive. Only a running receive monitor may be deliberately paused and restored; active non-monitor work is never stolen or queued for automatic restart.

## Record, import, preview, and storage

Settings -> Macros -> Voice contains six independent, single-line slots: label and waveform at left, duration in the middle, and Record/Stop, Preview, Import, and Delete actions at right. Labels are trimmed, control characters are removed, and the visible length is limited to 11 characters. Blank labels fall back to `M1` through `M6`.

Record uses `AudioRecord`, prefers `UNPROCESSED` when Android reports support, requests the built-in microphone, verifies `routedDevice`, and stops at 30 seconds. Import uses Android's document picker and accepts RIFF/WAVE PCM format 1, 16-bit mono or stereo, 8–96 kHz. Unknown WAV chunks are skipped; compressed, floating-point, malformed, empty, and excessive files are rejected.

Both paths downmix safely, resample deterministically to 48 kHz mono PCM16, trim conservative leading/trailing silence with 120 ms padding, reject silence-only content, apply 5 ms fades, and peak-normalize to about -6 dBFS. There is no EQ, compression, noise suppression, limiting, pitch processing, or per-slot gain.

Canonical files are atomically replaced under private app storage:

```text
filesDir/voice-macros/slot-0.wav
...
filesDir/voice-macros/slot-5.wav
```

Abandoned temporary files are removed at startup. WAV audio is never placed in SharedPreferences or logged. The current JSON settings recovery contains scalar preferences and labels only; it does **not** contain voice recordings.

Preview keys no radio command. It writes 100 ms of zero to a built-in-speaker `AudioTrack`, verifies the actual routed device, and only then writes speech. An unknown or changed route stops preview before further non-zero audio.

On the Radio screen, recorded slots appear as the macro strip immediately above the log area only while the observed radio mode is exactly USB or LSB. Empty slots are omitted, and the strip is hidden when no voice recording is configured; CW/CW-R continues to show only configured CW macros in the same position.

## Arming and automatic disarm

Voice macro arming is memory-only. The first eligible tap shows the label, duration, selected USB output, and `CAT PTT -> DigiRig USB audio -> KX3 MIC`. Confirming arms and sends that slot. Later taps can send immediately only in the same connected, unchanged exact USB/LSB mode.

Voice automatically returns to safe on CAT disconnect/reconnect, USB <-> LSB or any other mode change, app background/stop, serial or TX-route selection/removal, focus loss, Stop/Force RX, CAT timeout/malformed response, recording/playback/routing failure, USB detach, controller close, or activity destruction.

## TX level and calibration

Settings -> Audio -> Voice macro TX level scales PCM sent to DigiRig and defaults to 20%. It does not change Android system volume, KX3 RF power, MIC gain, compression, or TX EQ.

For first RF acceptance, use a dummy load and minimum safe RF power. Start with a low ShackCQ level, then adjust the app level and KX3 MIC gain conservatively. Elecraft's KX3 manual advises keeping audio-data ALC to no more than roughly four or five bars; avoid overdrive.

## Operator-controlled physical acceptance

Do not perform this checklist into an antenna as an unattended software test.

1. Connect the correct DigiRig Elecraft KX audio cable to PHONES and MIC. Connect/select KXUSB separately, or use a correctly RS-232-configured DigiRig and Elecraft CAT cable.
2. Attach a dummy load and select minimum safe RF power.
3. Explicitly select the CAT adapter and DigiRig voice TX output.
4. Connect CAT; confirm the radio does not key and diagnostics report RTS/DTR inactive.
5. Record `test one two three` from the tablet microphone.
6. Preview and confirm audio comes only from the tablet speaker.
7. Put the real radio in USB or LSB.
8. Tap the macro and confirm the first-use arm-and-send dialog.
9. Observe diagnostics and the radio for `TQ0 -> TX -> TQ1 -> speech -> RX -> TQ0`.
10. Adjust app level and KX3 MIC gain for clean ALC without overdrive.
11. During a second dummy-load transmission, press Stop; audio must cease and RX must confirm.
12. During a test, unplug DigiRig USB audio; ShackCQ must stop speech, request RX, disarm, and show route loss.
13. Repeat with app backgrounding and a radio-mode change; both must abort, release, and disarm.

Record each item as PASS, FAIL, or NOT RUN. An APK build, route enumeration, or `AudioTrack.write()` is not evidence of RF transmission or confirmed physical RX return.

## Troubleshooting

- **Selection required:** two candidates share the same role or the saved identity matches zero/multiple devices. Rescan and select the exact current attachment.
- **RTS/DTR could not be confirmed inactive:** disconnect the adapter and correct its driver/control-line support before CAT use.
- **Built-in microphone/speaker route refused:** disconnect conflicting audio accessories or correct Android routing; ShackCQ will not use an unknown default.
- **Fresh TQ response missing:** verify 38,400 8N1, the selected adapter, cable seating, and KX3/KX2 CAT configuration.
- **RX unconfirmed:** immediately use physical RX/XMIT or remove PTT, then inspect CAT and cable state before another attempt.
- **Low/distorted audio:** verify the DigiRig output selection, left/mono path, correct MIC cable, conservative app level, and KX3 MIC/ALC setup.

## Technical references

- [Elecraft KX3/KX2 Programmer's Reference, Rev G5](https://ftp.elecraft.com/KX3/Manuals%20Downloads/K3S%26K3%26KX3%26KX2%20Pgmrs%20Ref%2C%20G5.pdf)
- [Elecraft KX3 Owner's Manual, Rev C5](https://ftp.elecraft.com/KX3/Manuals%20Downloads/E740163%20KX3%20Owner%27s%20man%20Rev%20C5.pdf)
- [Getting Started with DigiRig Mobile](https://digirig.net/getting-started-with-digirig-mobile/)
- [DigiRig Elecraft KX connection guidance](https://forum.digirig.net/t/elecraft-kx3-connections/131)
- [DigiRig Mobile revision 1.5/KX3 RS-232 limitation](https://forum.digirig.net/t/is-it-possible-to-interface-rev-1-5-with-the-elecraft-kx3/3480)
