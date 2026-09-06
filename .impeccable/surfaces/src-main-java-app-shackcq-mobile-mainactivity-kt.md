---
surface: android/app/src/main/java/app/shackcq/mobile/MainActivity.kt
status: active-android-surface-contract
scope: android
---

Audience and job: A KX3/KX2 operator beside the physical radio must confirm live CAT state, read both VFOs, tune, adjust core radio controls, invoke adjacent primary/secondary actions safely, and log a QSO without fabricated state.

Direction: A native Android interpretation of the Elecraft KX3 faceplate within the wider ShackCQ instrument language. The graphite chassis, amber LCD hierarchy, raised control language, off-white primary legends, yellow secondary legends, green healthy state, and red safety/transmit state remain deliberate. Android navigation, focus, insets, 48 dp touch targets, keyboard/pointer behaviour, and accessibility stay native.

Current surface truth: MainActivity also hosts Spots/Neural DX, logging, settings, CW macros, and spectrum-related presentation. The previous “no Panadapter, separate Spots, or Digital surface” constraint was stale. WSJT-X parsing exists in the shared core but is not a current top-level client claim.

Home contract: Android Home is the native OpenHamClock-inspired operations clock, not a duplicate web application. It uses the existing station and service settings, keeps a world activity map dominant, surrounds it with compact observed instrumentation, and routes deeper actions to DX, Portable, and Progress. Wide screens form one fixed three-column console; compact screens stack the same information without hiding provider-unavailable states.

Safety constraints: Preserve observed radio truth, per-action confirmation/arming for potentially transmitting controls, session-bounded CW macro arming, permanent Emergency RX/abort access, and no blind retry of transmit or edge-triggered CAT commands.

Evidence: Current Phase 0 validation proves Android unit tests and Debug assembly, not a physical Lenovo/KX3 acceptance pass. Any older tablet claim is historical unless linked to a dated evidence record.
