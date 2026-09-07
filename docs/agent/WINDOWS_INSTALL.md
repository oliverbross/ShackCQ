# Windows Agent installation

Use the exact-SHA ShackCQAgent-Windows-x64 CI artifact and verify SHA256SUMS.txt.
Extract it to a per-user directory. Run `ShackCQAgent.exe --list-hamlib-models`
and `--list-serial-ports`, then persist the selected radio with
`ShackCQAgent.exe --configure-hamlib MODEL_ID --radio-route COM3 --radio-baud
38400 --test-radio-connection`. Pair once with `--pair-with-shackcq CODE`,
then start `ShackCQAgent.exe --foreground`.

The credential is held by Windows Credential Manager. The unsigned package is for owner acceptance only; signing and installer reputation remain pending. Uninstalling files does not intentionally delete operator configuration or vault credentials.
