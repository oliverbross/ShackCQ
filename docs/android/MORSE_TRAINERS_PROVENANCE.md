# Android Morse trainers provenance

RigWeave's Android Morse destination is native Kotlin/Jetpack Compose code. It ports trainer behaviour from Oliver Bross's MorseTrainerPro project and interoperates with his M32 Pocket firmware; it does not embed the website, JavaScript runtime, web assets, analytics, accounts, or hosted data.

## MorseTrainerPro reference

- Source: <https://github.com/oliverbross/MorseTrainerPro>
- Inspected tree: `fec23ca14aff61aa1064f2635c6dd38e888501f5`
- Behaviour baseline for the relevant trainer files: `329a391fad0972b9fdb5d0d411116a6e19e5f8a8`
- Original paths: `morse-machine.js`, `callsign-training.js`, `js/unified-tx-practice-engine.js`, `js/unified-tx-practice-input.js`, and `js/tx-trainer-morserino.js`
- Licence: MIT, Copyright 2025 Oliver Bross

RigWeave modifications: the trainer state and scoring are expressed as testable Kotlin domain objects; audio is generated locally with Android `AudioTrack`; web accounts, SRS APIs, analytics, QRM synthesis, browser storage, and web-only navigation are omitted. The Callsign trainer uses locally generated practice calls rather than copying the hosted callsign database. The UI follows RigWeave's Flightline design system.

## M32 Pocket interoperability reference

- Source: <https://github.com/oliverbross/MTP-M32-pocket>
- Inspected commit: `be2d4a23f5939f5cab412286489e0e328ddbf309`
- Reference: `Software/src/Version 6 and newer/M32ProtocolOut.h` and the protocol/menu implementation beside it

RigWeave opens a user-selected USB serial interface at 115,200 baud, enables protocol output, sets the training speed, temporarily enables keyed-character serial output, discovers and starts the executable CW Keyer menu when reported, and falls back to the compatible menu-1 activation sequence. Decoded paddle characters can therefore enter the active Android trainer over USB; Android keyboard/HID input remains available. On disconnect RigWeave restores the prior Serial Output value. It does not flash firmware, key a radio, or claim physical-device acceptance.

## Dependency and notice impact

No new dependency, binary, dataset, font, image, or service is added. The existing `usb-serial-for-android` dependency is reused. MorseTrainerPro's MIT attribution is retained in `NOTICE`; RigWeave's resulting Kotlin source remains GPL-3.0-only.
