# Linux Agent installation

Use the exact-SHA ShackCQAgent Linux CI artifact and verify SHA256SUMS.txt.
Install the required Qt runtime and a working Secret Service/keyring; Hamlib
4.7.2 is statically linked into the Agent. Discover the model and route with
`--list-hamlib-models` and `--list-serial-ports`, persist and test them with
`--configure-hamlib MODEL_ID --radio-route ROUTE --radio-baud 38400
--test-radio-connection`, pair with `--pair-with-shackcq CODE`, then run
`ShackCQAgent --foreground` as the desktop user.

Do not run as root. The credential is held by Secret Service. A headless host still needs an unlocked user keyring; absence of one is a hard pairing failure, never a plaintext fallback.
