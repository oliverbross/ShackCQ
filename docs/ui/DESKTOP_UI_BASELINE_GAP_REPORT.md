# Desktop UI Baseline Gap Report

Exact base: `ce1b99f21161c022fbfc97a78345cbe8a1ae2bd3`. Baseline artifacts are private build evidence, not committed application assets.

| Source | Evidence | Count / profile | Boundary |
|---|---|---|---|
| Android tablet | `build/evidence/tablet-reference-ce1b99f-20260825T213846Z/` | 41 × 2944×1840 | New unlocked reference; may contain private operator data |
| macOS base | `build/evidence/macos-ui-before-ce1b99f/` | 38 each at 1920×1080, 2560×1440, 150% plus local Settings launch | Hosted deterministic gallery and local process/UI proof, not physical service proof |
| Windows base | `build/evidence/windows-ui-before-ce1b99f/` | 38 each at 1920×1080, 2560×1440, 150% | Hosted deterministic gallery, not physical Windows hardware proof |

## Findings and convergence response

| Area | Base gap | Tablet signal | Convergence response |
|---|---|---|---|
| Hierarchy | Flat grey shell, workspace label and duplicate disconnected chips compete | Stable destination and status/safety hierarchy | One adaptive header, full-width workspace, written RADIO/CLUSTER state and persistent Global Stop |
| Navigation | Text-only 226 px list consumed operating width | Recognisable destinations | Native Navigate menus and command palette expose all 19 destinations without permanent global side chrome |
| Menus | Windows-style QML menu also appeared inside the macOS window | Tablet actions remain local to workspace | One C++ command registry; native macOS global menu; native Win32 File/Edit/View/Radio/Navigate/Tools/Window/Help menu |
| Commands | Menu, rail, palette and shortcuts duplicated routing | Consistent action meaning | Stable command IDs route every system menu, shortcut and palette action through `Desktop.invokeCommand()` |
| Platform identity | macOS title said “ShackCQ Windows Desktop” | Product identity is simply ShackCQ | macOS title/app menu “ShackCQ”; Windows title names current destination |
| Iconography | No icons and no provenance story | Icons make destination scanning immediate | Original repo-owned 24×24, 1.8 px SVG family; no emoji/font dependency/third-party licence |
| Responsiveness | 1280 px minimum and always-expanded rail | Tablet composition is constrained but clear | 1180×720 safe minimum, zero-width global rail, compact header below 1360, explicit minimum content pane |
| Accessibility | Sparse accessible names; colour-heavy status | Status is written and repeated in context | Accessible action names, focus borders, written state plus status dots, shortcut guide |
| Workspace reachability | Presets omitted from the 38-frame gallery | Presets is a first-class tablet destination | Presets added; deterministic full gallery now 39 frames/profile |
| Safety | STOP existed but Escape contract was inconsistent | Safety is always visible and stateful | `radio.stop` is the canonical visible/menu/palette/Escape action; no transmit authority added |
| Content truth | Deterministic gallery and foundation controls could look live | Tablet distinguishes current, stale, unavailable and gated | Normal startup remains local/private; demo data remains isolated; unavailable commands are present but disabled |

## Workspace-level baseline summary

The existing desktop already contained service-backed or truthfully bounded pages for all tablet domains. The convergence therefore preserves owners and data contracts while changing shell composition, reachability, density, focus, and platform conventions. No screenshot is used as backend proof and no parity status is promoted solely because the surface is polished.
