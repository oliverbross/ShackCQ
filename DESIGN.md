# ShackCQ Flightline design system

## Direction

ShackCQ is one coherent field instrument across SwiftUI, Compose, and the candidate Qt/QML desktop client. The visual language is shared; navigation, focus, dialogs, menus, keyboard, pointer, windowing, and accessibility follow each platform. Windows remains an Alpha foundation pending physical acceptance.

The instrument is a low-glare graphite chassis around an amber measurement surface. Off-white carries primary labels, yellow carries secondary/hold meaning, green signals healthy observed state, and red is reserved for safety and transmit state. Colour is always reinforced by text, shape, symbol, or state wording.

Dense radio information is intentional. Do not turn the operator console into a grid of decorative dashboard cards. Spectrum and waterfall are operational instrumentation where they are implemented, not ambient animation.

## Durable rules

- Live radio truth dominates hierarchy: dual VFO, mode, split/RIT/XIT, meters, gain, bandwidth, power, and connection state remain legible.
- Interpret the KX3 faceplate without copying its physical limitations. Keep the amber hierarchy, raised control language, off-white primary labels, and yellow secondary labels.
- Use platform-appropriate touch targets and accessibility APIs. Android targets at least 48 dp; Apple follows current Human Interface Guidelines and Dynamic Type/VoiceOver behaviour.
- Compact, portrait, landscape, multi-window, keyboard, and pointer layouts must reveal unavailable controls rather than silently clipping them.
- Potentially transmitting controls require explicit state, confirmation where appropriate, and an immediately available abort/Emergency RX path.
- No colour-only status, fabricated signal animation, placeholder QSO, or generated spectrum.
- The panadapter axis follows live CAT and the physical audio sample rate. Empty/offline input stays visibly offline.
- Android monitor, panadapter, EQ capture/playback, voice record/import/preview, and voice TX are mutually exclusive audio owners. Only the monitor may be paused and restored by the central coordinator; no other owner is preempted.
- Digi RX, DigiRig TX, and Flex digital TX participate in the same exclusive audio ownership model. A digital transmission acquires its route before PTT, arms for one attempt, and must return to explicit RX truth.
- Future desktop panels may resize or detach, but no desktop implementation is implied by this contract.
- Before public binary distribution, an About/Licences surface must make GPL and applicable third-party notices readily accessible.

## Current surface truth

Android implements the KX3-style console, the broader Neural DX workspace, Portable Chase, and POTA Activate. Apple implements a native iPad-focused navigation and radio/log/DX/panadapter flow. Their destination sets need not be forced into artificial parity; durable behaviour and evidence must remain clear.

Android Home is a native Flightline interpretation of the OpenHamClock operating overview. Expanded tablets use a fixed three-column console: compact DE/weather/band instrumentation, a dominant live world map with reporting paths, and a dense DX/PSK/portable/satellite activity rail. Compact devices preserve the same order in a vertical stack. It consumes the existing ShackCQ station, radio, cluster, Neural DX, portable, logging, and provider state; it must never create a parallel settings authority or imply live data where a provider is unavailable.

On Android, Panadapter is a first-class expanded destination and a segmented Radio subview on compact layouts so the existing six-item bottom navigation remains stable. The Flightline instrument keeps spectrum/waterfall dominant and unscrolled, offers a draggable split or either pane alone, and separates view gestures from explicit marker QSY actions. Setup and diagnostics may scroll; the live instrument does not.

Android Digi is a cycle-sequencer console. Expanded layouts place mode and route acquisition on the left, decoded traffic or the SSTV image in the dominant center, and a one-shot transmit sequencer on the right. Compact layouts preserve that order vertically. The truth rail always names radio, mode, RX, and TX state; no synthetic waterfall or fabricated decode is rendered.

- Scene: field and station operation in mixed or low ambient light requires a dark, low-glare chassis with a high-contrast warm instrument face.
- Color strategy: restrained graphite application shell plus a committed amber radio region. Amber is measurement surface, not decorative accent.
- Core colors: graphite `#111519`, panel `#1B2228`, raised `#283139`, line `#4A555D`, ink `#F4F0E7`, muted `#A5ADB2`, amber `#E9A72B`, amber-dark `#201708`, hold `#F4C94E`, healthy `#42C77B`, danger `#E4544D`, split `#8F1D24`.
- Typography: Android system sans for navigation, forms, and actions; monospace only for frequency, CAT, meters, time, and tabular measurements.
- Shape: 8–12 dp instrument and panel radii; small state chips may be pill-shaped. Dense operational content uses regions and tables, not nested decorative cards.
- Controls: every paired control presents primary tap text and secondary yellow hold text. Minimum target 48 dp with clear pressed, disabled, armed, pending, and error states.
- Navigation: Material navigation rail/drawer on expanded width; navigation bar on compact width. The compact bar retains Home, Radio, Digi, Logbook, Presets, DX, and Settings. EQ and Portable are first-class expanded destinations; compact layouts reach EQ through Radio/Settings Audio and Portable Chase through Home.
- Portable Chase: wide tablets use programme/status controls, a filter rail, stable ranked activity, and a shared map/detail cockpit; compact layouts use On Air, Map, and Places destinations. Graphite surfaces, restrained provider marker colours, explicit independent freshness, and selection-before-tune keep the multi-program decision path readable without turning rows into bright cards.
- POTA Activate: Portable owns a CHASE/ACTIVATE mode switch rather than another top-level destination. Expanded tablets pair a fixed session/logger panel with progress and recent-QSO context; compact layouts use one predictable vertical surface. A slim, non-transmitting active-session strip appears on Radio, Portable, and Logbook.
- EQ Studio: a Flightline audio bench, not a music-player equalizer. Graphite instrument regions hold exact green radio readback, yellow local draft state, amber response/measurement plots, real waveform/spectrum data, source/baseline provenance, touch-safe eight-band controls, and a fixed apply-and-verify action. Compact layouts stack the same workflow without shrinking the controls into narrow faders.
- Motion: short Material fade-through/shared-axis transitions only where state or destination changes. No decorative entrance choreography.
- Safety: transmit state overrides ordinary color and hierarchy with redundant red text, full-width warning, and permanent emergency RX access.
- Tables: fixed semantic columns, alternating tonal rows, sticky context where possible, explicit empty/loading/error recovery, and 20–25 row paging rather than unbounded rendering.
- Logbook: a fixed-header, horizontally scrollable flightline table is the primary surface. Logbook and Filters are peer tabs; General and QSL filter groups use adaptive native fields, fixed bottom actions, row selection for quick filters, and explicit Local/Wavelog station provenance.
- Sync Hub: reached from Logbook or Settings rather than top-level navigation. Expanded layouts pair restrained provider cards with the outbox; compact layouts stack the same authority banner, configuration, filters, and item actions without horizontal overflow. Text and symbols reinforce every queue, attention, and accepted state.
- Responsive: expanded landscape restructures into an instrument console; compact layouts preserve every core action through vertical regions and horizontal control strips rather than scaling the Tab5 geometry.
- Radio console: interpret the physical Elecraft KX3 faceplate rather than arranging generic application cards. The amber LCD follows the original KX3 hierarchy: a compact seven-segment VFO A in the upper-right, a smaller VFO B below it, boxed A/B and mode/TX indicators at the edge, separate S/CWT and SWR/RF meters at upper-left, the filter trapezoid below, and dense ANT/ATU/RIT, AGC/PRE/ATTN/CWT/XFIL/BW truth between them. Compact 4 × 3 key banks flank the display and the twelve receive keys form one strip below; every key uses the real radio's raised rounded gray cap, beveled tonal face, bright edge, off-white primary legend, and yellow secondary legend. The lower-right tuning deck mirrors the three non-VFO rotary groups: AF/RF/MON, PBT I/WID plus PBT II/SHT and NORM, and KEYER/MIC/PWR, with live CAT values for each direct control. Tapping VFO A opens 160–10 m band recall, tapping the mode legend opens supported non-digital modes, and tapping BW opens the six Tab5-derived widths appropriate to the active mode. The VFO tracks horizontal drag smoothly in both directions and sends frequency changes continuously during rotation, while retaining Android semantics and touch targets.
- Transmit safety: there is no persistent `TX DISABLED` rail. Potentially transmitting controls retain their existing per-action confirmation and Emergency RX remains continuously available.

The Phase 0 documentation and licensing work changed no navigation or operator-facing UI.

## Qt desktop parity shell

The desktop uses 19 routed full-width workspaces, a searchable Ctrl+K palette, native system menus and a full-window Shack Display. Persistent global side navigation is omitted to preserve operating width; Navigate menus and the palette own workspace switching. Each major desktop module is an independent canvas panel with a drag title bar, edge/corner resizing, focus layering, bounded geometry and per-workspace persistence. View → Reset Workspace Layout restores authored defaults. Escape is both a focus escape and global Stop: it cancels provider/review work, leaves Shack mode and restores window focus. Desktop feature foundations use the same dark instrument palette but must label unavailable production actions explicitly; visual completeness never substitutes for a controller, capability snapshot or readback.

## Desktop TCI, multi-receiver, spectrum, and RF geography

- `DesktopRadioController` remains the sole radio authority. Hamlib and TCI are mutually exclusive backends; no receiver row or visualization creates a second controller.
- Active-control, listening, and transmit-compatibility roles are explicit. TCI attaches the bounded union of active and listening receivers; changing roles never tunes, logs, transmits, publishes, or rotates implicitly.
- TCI profiles restore inert by default. Capability and readback are authoritative, ambiguous writes are not replayed after reconnect, and unknown formats fail closed.
- The spectrum/waterfall stays an instrument: direct float I/Q, bounded worker queues and history, real traces only, explicit QSY, view-only passband without proven filter capability, and visible overflow/health truth.
- RF geography is derived evidence beneath canonical QSO, spot, Neural DX, Band Health, and provider owners. LIVE, HISTORICAL, and OUTLOOK remain visually and textually distinct; COARSE is never presented as exact.
- Flat and globe projections share one selected observation and provide action-free exploration. Any QSY, Logbook, DX, or Band Maps transition remains an explicit operator handoff.
- No runtime tile service, commercial key, WebView, private Qt API, or copied SDRoxide asset is part of the desktop map/globe.

## Desktop Flightline convergence

The desktop shell uses the same operational hierarchy as the tablet without copying tablet pixels. The tablet remains a fixed touch composition; Windows and macOS use a panel canvas with locked official layouts. One canonical command registry owns destination, sidebar, system-menu, shortcut and command-palette routing. A grouped, collapsible Flightline sidebar is the primary visible route; the native Navigate menu and command palette remain equivalent routes. Original 24×24 Flightline SVGs provide a coherent offline icon family in the sidebar, palette, Settings categories and workspace actions.

macOS uses the native global menu and the title “ShackCQ”. Windows attaches an Alt-accessible native Win32 File/Edit/View/Radio/Navigate/Tools/Window/Help menu to the window chrome; no application menu consumes the QML content area on either platform. Graphite/amber surfaces, system UI fonts, 8/12/16 px spacing, 34 px table rows, 36 px controls, written status, focus borders and a persistent Global Stop define the shared desktop system. UI convergence never changes a service owner or promotes a foundation to functional parity.

## Desktop Deep Convergence v2

Operate mode presents one locked official layout per workspace. Panel movement, resizing, stacking and ratio persistence are available only after the operator enters explicit **Edit Layout** mode. Reset restores the active workspace's official layout; Done or Escape returns it to safe operation.

Home, Radio, Digi, EQ and Panadapter use dedicated desktop cockpit compositions rather than generic equal-card grids. All remaining destinations retain their service-owner and capability boundaries inside the same official-layout contract. Unknown capabilities, absent providers and unavailable readback remain written, disabled and non-transmitting.

Functional owners are rooted in `DesktopApplication`: one radio, rotator, canonical QSO, shared spot, configuration/vault, provider/cache and feature-domain authority. QML consumes typed models and invokes reviewed actions; it owns no socket, database, device, audio session or worker. Global Stop is the single idempotent fan-out and invalidates active generations.

## Android SDR enhancement ownership

Android TCI is another managed radio backend, not a parallel radio owner. The existing Panadapter controller owns at most two TCI DSP contexts, and the existing audio-route controller grants one explicit `TCI_RX_AUDIO` output lease. Scanner, TCI streams, receiver audio, and speech all stop on the established lifecycle and Global Stop boundaries. RF map/globe views consume bounded evidence models and never own tuning.

## Secure Remote Station v6 ownership

`RemoteStationService` is an adapter around the existing desktop radio, media, Digi, Keyer, Voice and rotator authorities; it is not a second hardware owner. Protocol v1 uses TLS 1.3, signed one-time challenges, certificate pinning, bounded control/media frames, generation checks and at most eight sessions. Android, SwiftUI, and Qt clients project observed state and receive media into their existing layouts. Adaptive Opus is the default RX transport, PCM16 is a LAN/debug fallback, and raw I/Q is host-disabled, one-client, bounded, and never backpressures the radio owner. Observer/operator/admin roles plus separate writer, TX and rotator leases are fail-closed, short-lived and invalidated by disconnect, revocation, local pre-emption or Global Stop.
