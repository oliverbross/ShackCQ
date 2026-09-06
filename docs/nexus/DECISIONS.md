# Nexus Digi v2 decisions

- The existing ShackCQ mode inventory is frozen; missing Nexus modes are excluded rather than imitated.
- FT8 and FT4 alone receive an automatic exchange sequencer. Other slotted modes remain explicit one-shot/manual workflows.
- `DigiController` remains the single Digi owner and delegates only to the existing audio, radio, QSO, Wavelog, Needs, portable, and satellite authorities.
- Waterfall taps change decoder audio offset, never radio dial frequency or PTT.
- Decode arrival may enrich or select a row but never enables TX, starts a QSO, logs, tunes, or keys PTT.
- Every Digi QSO mutation goes through `QsoMutationCoordinator`; Digi never calls Wavelog directly.
- WSJT-X UDP defaults to loopback and inbound traffic cannot enable TX, tune, change station identity, or log.
- SSTV ISS support is receive-only and uses the existing satellite controller and receive-review contract.
- Durable Digi session data is bounded and separate from the canonical QSO, Neural DX, HamClock, Groups.io, portable, and satellite stores.
- Physical tablet, radio, audio, Wavelog, and RF acceptance remains a separate evidence gate.
