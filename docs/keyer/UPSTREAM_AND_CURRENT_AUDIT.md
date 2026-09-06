# Keyer Hotkeys v2 — upstream and current-authority audit

## Frozen inputs and licensing boundary

- ShackCQ base: `b4f12e17fa87df16d2094b518ae187553e370be5`, GPL-3.0-only.
- HamLedger: `https://github.com/valibali/hamledger` at `24f3ed0ef6b533f5a422d5d7cce3b24a58887bbc`. Its `LICENSE` is AGPL-3.0 text plus a Commons Clause/non-commercial restriction. Only `LICENSE`, `src/components/VoiceKeyerHotkeys.vue`, `VoiceKeyerHotkeysPanel.vue`, `contest/VoiceKeyerPage.vue`, and `src/utils/voiceKeyerQueue.ts` were reviewed for visible behaviour. No code, CSS, labels, state shapes, fixtures, component structures, or PTT sequences were copied, translated, or adapted.
- Not1MM: `https://github.com/mbridak/not1mm` at `95b49e30fe7f374057fd708307c02fbdb892a81a`, GPL-3.0. Its action mapping, voice keying, function-key/ESM references, and Run/Search-and-Pounce flow were reviewed; no source or fixture was reused.
- TLF: `https://github.com/Tlf/tlf` at `348edf6d9730b68a02d5d32959ad718fe1504f60`, GPL-2.0. Its manual and keyboard/message paths were reviewed for Run/Search-and-Pounce, F-key labels, auto-CQ, and Escape/back-out behaviour. GPL-2.0-only C source was not copied or adapted.
- Elecraft K3S/K3/KX3/KX2 Programmer's Reference, Rev. G5 (2019-02-20), documents a 0–24-character `KY` payload and `KS008`–`KS050`. This work retains the 24-character boundary and adds no WPM CAT command because the application has no integrated acknowledgement-aware WPM owner.

Independently retained behaviours are assignable function keys, explicit operating roles, visible active/pending indication, immediate Stop, logical composition, and a queue. Rejected behaviours include global/background listeners, an unbounded FIFO, UI-owned PTT/serial, skipping bad clips, file-path persistence, hidden assignments, auto-resume, and any upstream rule that weakens ShackCQ safety.

## ShackCQ files reviewed

`docs/VOICE_MACROS_ANDROID.md`; `CwMacroRules.kt`; `VoiceMacroRules.kt`; `VoiceMacroAudioController.kt`; `VoiceMacroStore.kt`; `VoiceMacroTransmitController.kt`; `AppController.kt`; `MainActivity.kt`; `AudioOwnership.kt`; `AudioMonitorController.kt`; `FinalConvergenceContracts.kt`; `ConfigurationRecovery.kt`; and focused CW, voice, audio ownership, convergence, and recovery tests.

## Current authority and migration

- `AppController` owns six sanitized CW labels/texts, six voice labels, legacy repeat interval, and memory-only arming.
- `VoiceMacroStore` owns six canonical app-private WAV slots. Profiles store only slot numbers.
- `VoiceMacroAudioController` remains record/import/tablet-preview owner, including no-PTT composite-plan preview.
- `VoiceMacroTransmitController` remains the only Elecraft voice transmit owner, using central audio ownership, verified routing, fresh TQ preflight, one CAT TX, and verified RX cleanup.
- `UsbRadioTransport` remains CAT serialization owner. Keyer code opens no CAT connection.
- `OperatingContextAuthority` remains the sole context/generation owner and was not edited.
- First keyer load creates General CW/Voice profiles from all six legacy entries without overwriting them or copying WAV data. Migration is versioned/idempotent and retains a separate last-good document.

Retained: six physical slots/macros, sanitization, 24-character limit, explicit arming, foreground/mode/route checks, private audio, RX recovery. Enhanced: typed intents/templates, logical voice plans, bounded queue, editable profiles/roles, explicit same-mode General fallback, optional foreground hotkeys, accessible strip, bounded Repeat CQ, and transactional stable-settings recovery. Deferred: Apple parity, WinKeyer, Digi, contest scoring/session/ESM, N1MM integration, live hardware validation, and unsupported WPM control.
