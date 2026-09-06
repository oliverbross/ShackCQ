# ShackCQ Windows Desktop Alpha v1

This branch builds the first Qt 6.11.2/QML Windows Alpha from frozen Sweep 2 source `d1e956d2c21eefc905a5ecab086a8f467b7a03c4`. It is a local-first, keyboard/pointer-first operational desktop with a resizable Flightline navigation shell, persistent schema-16 Logbook/Fast Entry/ADIF, Wavelog API-v2 sync, DX Cluster and shared Band Maps, local Intelligence, Hamlib radio/rotator, exact-route receive-only Panadapter, Settings, Health, About and bounded secondary foundations.

The executable starts inert: no provider/radio/audio connection, PTT/TUNE, Digi TX, QSO, rotator movement or automation is restored or started. Live/hardware/service layers are listed separately in `WINDOWS_LIVE_ACCEPTANCE.md`.

Build:

```text
cmake -S desktop -B build/desktop -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/desktop --parallel 2
ctest --test-dir build/desktop --output-on-failure
```

Windows CI additionally builds the pinned static Hamlib library, sets `SHACKCQ_REQUIRE_HAMLIB=ON`, runs the launch smoke, deploys Qt/QML, creates the portable ZIP and NSIS installer, measures/hashes both, and uploads exact-SHA artifacts. macOS CI compiles/tests the same source and packages an unsigned `.app` proof.

This is not Android parity, a signed release, a store submission, an authenticated-service claim or physical hardware acceptance.

## Successor

The Alpha is superseded for further desktop work by [`WINDOWS_FULL_PARITY_V1.md`](WINDOWS_FULL_PARITY_V1.md). The successor adds the full workspace shell, provider/data platform, domain stores, deterministic gallery and broader regressions, but still reports PARTIAL because production-equivalent native radio/rotator and several Android feature controllers remain incomplete.
