# Final integrated live acceptance

Do not mark an item passed without observing it on the protected tablet or named live service/radio. Stop immediately for unexpected PTT, TUNE or transmit audio.

1. Confirm `app.shackcq.mobile` exists; capture a hash-recorded private recovery backup; install only with `adb install -r` and never clear data.
2. Reopen the app and verify QSO, projection, Neural, Digi, Groups.io, Contest and Chaser migrations without destructive fallback.
3. Check every top-level destination on phone-width, tablet portrait and landscape; confirm DX Chaser is inside Digi and N1MM inside Contest/Health.
4. Exercise Wavelog authenticated sync, conflicts and deletes; verify one outbox delivery per canonical Contest QSO.
5. Exercise Groups.io account, archive, search, drafts, attachments and posting without exposing content in diagnostics.
6. Verify Home/HamClock/Neural live, cached, stale, degraded and unavailable truth; do not treat empirical outlook as probability.
7. Check Logbook, Log Intelligence, 100k paging behavior and exact cross-workspace selections.
8. Verify radio/audio receive operation, explicit receive-review and RX-unconfirmed latch behavior.
9. Verify Digi FT8/FT4 with explicit TX enable and operator start; test route loss and Stop without on-air transmission unless separately authorised.
10. Verify CW/voice keyer, physical F1-F12 modifiers, text-field suppression, repeat-CQ bound and Escape/Back Stop precedence.
11. Verify Contest Run/S&P, ESM, serials, score/rate, ADIF/Cabrillo and canonical edit/delete behavior.
12. Verify a live N1MM+ peer only after LAN opt-in and explicit arm; incoming traffic must not operate radio, Keyer, Digi or Chaser.
13. Verify DX Chaser Assist, Dry Run and Chase eligibility from exact live local decodes; provider/reference/history rows remain ineligible.
14. Verify Band Maps sources, layouts, filters, marks and exact handoffs; no selection sends CAT or starts TX.
15. Verify Portable, activation, Operations, Satellite/QO-100 and receive-only review.
16. Background/resume each new runtime; confirm no provider, network arm, contest session, Chaser session or TX state starts on restore.
17. Export, preview and restore configuration; confirm all safe sections round-trip and every arm/session restores off.
18. Trigger global Stop repeatedly; confirm Digi, Keyer, voice, repeat CQ, Chaser, pending ESM and N1MM runtime stop idempotently.
19. Export the support bundle and inspect it for metadata only—no credentials, QSO/message bodies, decodes, XML, callsigns, audio or private paths.
20. Record device model/serial alias, app version/SHA, UTC time, observed result and operator initials for every performed item.

## Secure Remote Station v6 additions

Before any live remote test, verify exact client/station SHAs, TLS certificate pin, device identity, approved role, session generation and lease owner. Test observe-only state and RX media before any write; then separately exercise local pre-emption, revocation and repeated Global Stop. Public-internet reachability, real WSJT-X/fldigi, voice/CW audio, physical PTT/Tune/RF and rotator motion require explicit owner-present acceptance and must not be inferred from the source, fixture, hosted or package gates.
