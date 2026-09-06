# Windows Agent installation

Use the exact-SHA ShackCQAgent-Windows-x64 CI artifact and verify SHA256SUMS.txt. Extract it to a per-user directory. Pair once with ShackCQAgent.exe --pair-with-shackcq CODE, then start ShackCQAgent.exe --foreground.

The credential is held by Windows Credential Manager. The unsigned package is for owner acceptance only; signing and installer reputation remain pending. Uninstalling files does not intentionally delete operator configuration or vault credentials.
