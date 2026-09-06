# SDRoxide Local Receiver Crosswalk v3

| Reviewed behavior | ShackCQ owner | Implementation | Boundary |
|---|---|---|---|
| Local receiver selection | `LocalReceiverController` | Two stable RX A/B identities | Never a physical TCI receiver. |
| NCO/passband | `LocalReceiverDsp` | Continuous phase NCO and complex FIR | Out-of-span fails closed. |
| SSB/CW/digital | `LocalReceiverDsp` | Signed sideband filters and CW pitch | Existing Digi decoder remains separate. |
| AM/SAM | `LocalReceiverDsp` | Envelope metrics and bounded PLL | Lock is measured, never assumed. |
| NFM/tone | `LocalReceiverDsp` | Discriminator, de-emphasis, Goertzel CTCSS, Golay-word DCS | Receive only. |
| WFM/RDS | `LocalReceiverDsp` | Source-rate gate, pilot blend, differential/CRC block path | No network lookup or unvalidated text. |
| Audio/DSP | `TciRxAudioController` | Existing two-input mixer and `NativeRxDsp` | One AudioTrack authority. |
| Spectrum interaction | `PanadapterController` | Local overlays and explicit Add/Move review | No implicit CAT. |
| Time-shift pre-roll | `ReceiveTimeShiftController` | Bounded 48 kHz demod-audio extension | No second time-shift owner. |
| Recording | `ReceiverRecordingStore` | PCM16 WAV plus JSON metadata | App-private; one active; quota/retention. |
| Scanner hit | `ReceiveOnlyScannerController` | Existing bank opt-in calls local recorder | Silent default prohibited. |
| Debug evidence | `DebugSdrLab` | Runtime-generated deterministic I/Q | `DEMO · NO RADIO`. |
