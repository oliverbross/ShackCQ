# Linux Agent installation

Use the exact-SHA ShackCQAgent Linux CI artifact and verify SHA256SUMS.txt. Install the required Qt runtime, Hamlib runtime, and a working Secret Service/keyring. Pair with ShackCQAgent --pair-with-shackcq CODE, then run ShackCQAgent --foreground as the desktop user.

Do not run as root. The credential is held by Secret Service. A headless host still needs an unlocked user keyring; absence of one is a hard pairing failure, never a plaintext fallback.
