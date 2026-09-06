# Tablet Acceptance Sweep 1 live checklist

Candidate identity must match the exact pushed fix/tablet-acceptance-sweep-1 SHA and locally hashed APK. This checklist proves presentation/process survival only; it does not prove authenticated services, audio, CAT, PTT, TUNE, transmit, on-air or RF behavior.

## Protected device gate

- [ ] Confirm Lenovo model, serial and Android version.
- [ ] Confirm app.shackcq.mobile is installed before any state-affecting action.
- [ ] Record current version, UID, signing/APK information and app-data size.
- [ ] Create the fresh external protected backup, hash manifest and restoration notes.
- [ ] Install only with adb install -r; stop on signature or replace failure.
- [ ] Confirm UID, private-data presence and schema 16 after install.
- [ ] Launch and hold process/focus for at least 120 seconds.

## Passive presentation captures

- [ ] Home has no Operations/Groups rows, no build clutter, no blank spans, and an enhanced Shack entry.
- [ ] Settings has no integrated summary card; EQ and Contest visibility controls restore safely; About shows exact build/schema/provider metadata.
- [ ] Contest Setup, Logging, Review and Network are readable and usable without triggering a network/radio action.
- [ ] Band Maps multi-horizontal, multi-vertical, grid and single views show axes, segments, range, dense stacks and selected-frequency context.
- [ ] Log Intelligence changes with the selected local/Wavelog station authority; LAST YEAR, charts, heatmap and valid-grid map are visible.
- [ ] Presets and DX layouts do not overlap/truncate essential tablet metadata.
- [ ] Portable On Air, Map and Places show adaptive layouts and coordinate-anchored labels.
- [ ] Passive SOTA cluster spots, when network-available, show cluster.sota.org.uk:7300 provenance and catalogue-enriched summit placement; do not enter credentials.
- [ ] POTA/SOTA reviewed pages open with minimum policy; unreviewed redirect requires external confirmation.
- [ ] Activation Planner shows POTA/SOTA provider truth, cross-border radius results and explicit WWFF unavailability.
- [ ] Groups.io cached/list/thread rows show the stored server date/time or TIME UNKNOWN; do not send, draft or authenticate for this smoke.

## Stop conditions

- [ ] Stop on UID change, missing private data, schema-open failure, crash loop, signature mismatch, destructive migration, unexpected credential prompt, or any transmit/radio command.
- [ ] Save sanitized screenshots/logs only under the external Sweep 1 evidence directory.
