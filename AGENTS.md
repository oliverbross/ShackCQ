# ShackCQ working rules

- Read the task scope and nearest instructions before editing. Preserve unrelated owner work and use a feature branch or isolated worktree.
- Apple and Android are active first-class clients. SwiftUI and Compose remain native UIs; a platform-specific task does not require unrelated edits to the other client.
- Put behaviour in shared C++ only when it is genuinely platform-neutral. Preserve the working KX3/KX2 protocol, bindings, and storage paths.
- State exactly which clients were implemented and validated. Distinguish source review, automated tests, build, simulator/emulator, physical device, physical radio, physical audio, and authenticated service evidence.
- Run only focused builds/tests required by the changed scope. Do not require unavailable hardware, sign-in, credentials, SDK licence acceptance, or profile creation merely to claim a source build.
- Never add fake radio/service/spectrum/QSO state. Transmit-capable actions must be explicit, bounded, operator-initiated, abortable, and never blindly retried.
- Preserve app-private/local-first storage and existing user data. Avoid speculative abstractions, broad refactors, and schema changes without an authorised migration.
- This repository is GPL-3.0-only. Preserve file-level copyright, SPDX identifiers, licences, and notices.
- Never paste or adapt external code without recording source URL, immutable upstream commit, original path, licence, copyright notice, modification note, dependency impact, and applicable NOTICE entries.
- Do not import Nexus or another upstream unless the task authorises the selected component and integration strategy. Never imply upstream endorsement.
- Flex, desktop, QMX, portable-programme, and Nexus-reuse work is not authorised unless the task explicitly says so.
- Do not merge, deploy, release, distribute, submit to a store, or publish without owner authorisation.

### Simplicity

- Do not introduce abstractions unless the current task requires them.
- Do not add dependencies unless they clearly reduce complexity.
- Do not add boilerplate, generic helpers, or extensibility nobody asked for.
- Prefer deletion over addition.
- Prefer boring code over clever code.
- When two approaches are equally small, choose the safer one for edge cases.
- Avoid private helper methods for short logic. Keep simple conditions inline when local context makes them easier to read.

### Runtime behavior

- Runtime validation belongs at trust boundaries. Don't re-validate trusted domain values.
- Avoid defensive noise: deep type checks, catch-all fallbacks, and runtime guards must protect a real invariant.

### Simplicity

- Do not introduce abstractions unless the current task requires them.
- Do not add dependencies unless they clearly reduce complexity.
- Do not add boilerplate, generic helpers, or extensibility nobody asked for.
- Prefer deletion over addition.
- Prefer boring code over clever code.
- When two approaches are equally small, choose the safer one for edge cases.
- Avoid private helper methods for short logic. Keep simple conditions inline when local context makes them easier to read.

### Runtime behavior

- Runtime validation belongs at trust boundaries. Don't re-validate trusted domain values.
- Avoid defensive noise: deep type checks, catch-all fallbacks, and runtime guards must protect a real invariant.
