# Hamlib licence and source packaging

## Immutable source

ShackCQ vendors the official generated Hamlib 4.7.2 release archive under
`core/third_party/hamlib`. The release archive SHA-256 is
`ae1fcf2dbc80ea0786ea8f047b09399c3f7737d1930442f61a031708ed33e88f`.
The signed tag peels to commit `40f63488fe0bd751b147f48d62fd217bf53713a0`
and tree `56a42afe2ace9dd1b43729168bb73ca46a812848`.

The complete generated source release is retained to make corresponding source
and offline rebuilding straightforward. Normal Gradle builds make no network
request. Only `libhamlib` and the 37 radio backends configured for Android are
linked. Utilities and daemons (`rigctl`, `rigctld`, and peers), tests, language
bindings, rotator backends, amplifier backends, documentation generators, and
examples are not linked or packaged.

`README.android` is retained from the same immutable tag because the generated
release archive omits it. `SOURCE_MANIFEST.json` records every vendored file,
size, and SHA-256.

## Licence audit

Hamlib's library core and radio backend code is generally licensed
LGPL-2.1-or-later. `COPYING.LIB`, file-level copyright notices, `AUTHORS`,
`README`, and relevant backend notices are preserved. Some utilities and test
sources carry GPL-2.0-or-later or other compatible notices; they remain in the
corresponding-source bundle but are not linked into the application library.
ShackCQ is GPL-3.0-only, so the linked LGPL-2.1-or-later library and backend
code is distribution-compatible while retaining the upstream LGPL terms.

The audit does not replace upstream file-level notices. Files without an SPDX
line retain their original copyright/licence prologue and are covered by the
top-level licence notices. The watcher treats changes to any licence digest or
source header distribution as `LICENCE` review work.

## ShackCQ modifications

Two narrow Android integration changes are maintained in the vendored tree:

- `src/iofunc.c`: accepts a private `shackcq-fd:<n>` descriptor reference,
  duplicates it, and closes only the duplicate. This connects Hamlib to an
  application-owned socket pair without opening Android USB or a PTY.
- `src/microham.c`: disables POSIX `/dev` glob discovery on Android. Direct
  device discovery conflicts with the single application USB owner and Android
  does not provide the desktop `/dev/serial/by-id` contract.

Both files retain their original LGPL notices. The changes are described in
`TRANSPORT_BRIDGE.md` and included in the source manifest hashes.

## Reproducible update

Updates are explicit and review-only:

1. download a named release archive;
2. verify its separately reviewed SHA-256;
3. resolve and record tag object, peeled commit, and tree;
4. audit licence and Android/transport deltas;
5. replace the vendor tree only after written selection;
6. reapply or retire the two documented patches;
7. run `python3 scripts/check_hamlib_upstream.py --write-manifest`;
8. run the full native, Android, package-size, and safety validation.

The weekly watcher never performs these update steps automatically.
