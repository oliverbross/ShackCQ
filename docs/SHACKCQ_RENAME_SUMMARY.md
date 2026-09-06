# ShackCQ rename summary

Date: 2026-09-06

## Completed scope

- Replaced 3,124 tracked `RigWeave`, `rigweave`, and `RIGWEAVE` occurrences across 724 text files.
- Renamed 520 tracked paths, including Android packages, Apple targets and schemes, C/C++ headers and namespaces, QML modules, Rust crates, workflows, documentation, and branding assets.
- Set the Android application identity to `app.shackcq.mobile` and the visible product name to `ShackCQ`.
- Renamed the Apple project, targets, schemes, source tree, entitlements, bundle identifiers, and asset catalog to ShackCQ.
- Renamed desktop products to `ShackCQDesktop` and `shackcq-stationd`, including package and CI artifact names.
- Renamed first-party C ABI symbols, CMake options, environment variables, database files, protocols, preference stores, and the Rust `shackcq-flex` crate.
- Updated documentation, comments, examples, product metadata, repository links, the `shackcq.com` product URL, and `support@shackcq.com`.
- Replaced Android, iOS, macOS, Windows, and shared master icons with the supplied ShackCQ visual identity.
- Preserved import compatibility for signed pre-rebrand configuration bundles while exporting the new `SHACKCQ_CONFIGURATION_BUNDLE` format.

## Verification

- Case-insensitive tracked-source and tracked-path audits report zero legacy product-name matches.
- First-party `rw_` and `RW_` ABI identifier audits report zero matches; unrelated third-party identifiers were left unchanged.
- `git diff --check` passes.
- Apple plist files and both updated asset-catalog JSON manifests pass syntax validation.
- Android and Apple rebuilds were intentionally not run; the requested build gate is the ShackCQ Web repository.

## Data boundary

Changing the Android application ID and Apple bundle identifier is a platform identity change. Existing installed application sandboxes are not automatically shared with the newly identified app; export/import or a release migration plan is required before distributing the renamed binaries to existing users.
