# RGO ONE Later Live Acceptance

This checklist is a future hardware contract. Nothing here was executed by this source task.
Before starting, record radio generation, firmware, installed options, cable/adapter identity,
power supply, dummy load, test operator, date, and a hash of captured CAT traffic.

## Phase 1 - read-only

- Verify USB enumeration and stable reconnect identity without changing radio settings.
- Record model and firmware; V6 must return model `006` before V6-only polling begins.
- Compare VFO A/B, RX/TX VFO, mode, split, fine tune, RIT/XIT, AGC, S-meter, and firmware
  between radio display and ShackCQ.
- Toggle documented module menus on the radio and verify ATU/NB/AF truth does not infer
  absent hardware from an off value.
- Disconnect/reconnect repeatedly and confirm stale truth, one close, one poll owner, and
  no duplicate command bursts.
- If TTL is tested, first capture the exact framing configuration; verify each documented baud.

Stop on identity mismatch, unexplained unsolicited traffic, framing uncertainty, or any write.

## Phase 2 - safe setters

- With explicit operator review, test VFO A/B frequency, RX/TX VFO, mode, fine tune,
  AF/RF gain where evidenced, RIT/XIT toggles/nudges, and AGC.
- Compare every write with a readback and physical display.
- Verify duplicate safe setters coalesce and failures do not create queued retries.
- Verify no preset recall performs a memory write.

Stop on any unexpected TX indication, frequency outside the selected band, module mismatch,
or loss of central safety context.

## Phase 3 - reviewed edge/transmit

Requires a dummy load, power limit, local physical supervision, explicit central TX authority,
and a tested immediate RX/stop path.

- One PTT command, confirm RF and immediate RX return.
- One tune/ATU command at reviewed power, confirm bounded duration and RX return.
- Verify no blind retry after timeout or disconnect.
- Test memory write only with separate explicit owner authorization and a disposable channel;
  read before, write once, read after, and restore deliberately.

No firmware unlock or firmware flashing is ever part of ShackCQ acceptance.

## Evidence separation

Record source/build, emulator/device, USB transport, physical UI, physical radio, audio,
TX/dummy-load, and RF results separately. An APK build or serial transcript does not prove
physical display, audio quality, transmit safety, or RF performance.
