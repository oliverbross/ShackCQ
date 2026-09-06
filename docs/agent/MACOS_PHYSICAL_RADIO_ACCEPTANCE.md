# macOS receive-only physical acceptance

This procedure is owner-only and was not run overnight.

1. Download the exact-SHA unsigned ShackCQAgent-macOS-arm64 artifact from CI and verify it against SHA256SUMS.txt.
2. Install the unsigned development build without granting unrelated permissions.
3. In ShackCQ Cloud, sign in, keep registration closed, open Agents, choose the intended station, and generate a pairing code.
4. Discover the compiled radio ID and serial route with `ShackCQAgent --list-hamlib-models` and `ShackCQAgent --list-serial-ports`.
5. Persist and prove the receive-only KX3 connection with `ShackCQAgent --configure-hamlib MODEL_ID --radio-route /dev/cu... --radio-baud 38400 --test-radio-connection`.
6. Run `ShackCQAgent --pair-with-shackcq CODE --agent-name "KX3 shack"`, then `ShackCQAgent --foreground`.
7. Leave amplifier, keyer, PTT, TUNE, TX audio, and rotator disconnected.
8. Confirm the website reports Agent online and real KX3 frequency/mode/filter readback.
9. Change frequency, mode, and filter from the website one at a time; confirm the physical front panel and returned readback agree.
10. Change each value on the KX3 front panel and confirm the browser follows without issuing a command.
11. Recall a receive preset and confirm all three fields. Disconnect USB during a pending operation and verify OFFLINE/failure with no replay after reconnect.
12. Confirm no transmission, PTT, TUNE, keying, power, memory write, audio transmit, or rotator action occurred.

Do not run the RGO ONE sequence: the selected Hamlib release has no dedicated model.
