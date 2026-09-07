# macOS packaging proof

The cross-platform workflow builds the same Qt/QML source, installs unsigned app bundles, and applies macdeployqt without signing or notarization. ShackCQAgent.app is the receive-only cloud Agent package and stores its credential in macOS Keychain. Signing, notarization, and physical-radio acceptance remain owner gates.
