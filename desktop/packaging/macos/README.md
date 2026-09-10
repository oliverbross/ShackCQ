# ShackCQ Agent for macOS

`ShackCQAgent.app` is the user-facing macOS Agent. Its setup window covers
ShackCQ pairing, radio model and connection selection, a receive-only connection
test, profile removal, Agent start/stop, and live health monitoring. The bundled
`shackcq-stationd` executable remains the single service and radio authority;
users do not need Terminal for normal setup or operation.

The cross-platform workflow creates both a ZIP whose top-level entry is
`ShackCQAgent.app` and a drag-installable DMG containing the app plus an
Applications shortcut. `package-agent.sh` rejects incomplete bundles and can
optionally apply hardened-runtime Developer ID signing and notarization through
an existing Keychain profile. Credentials remain in macOS Keychain and are
never added to either distribution artifact.

Automated packaging and UI smoke tests do not constitute physical-radio or
authenticated ShackCQ acceptance. Setup does not enable PTT, TUNE, or transmit.
