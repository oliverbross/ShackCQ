# ShackCQ Agent Digi operator guide

These commands do not install a service, open audio, connect a radio, or transmit unless their explicit action says so.

```sh
ShackCQAgent --list-audio-devices
ShackCQAgent --configure-digi-audio modem-1 --digi-input INPUT_ID --digi-output OUTPUT_ID --digi-sample-rate 48000
ShackCQAgent --status
ShackCQAgent --digi-stop
ShackCQAgent --disable-digi-tx
```

Pair/connect the Agent using the existing documented `--pair` and Hamlib profile commands. In the web Digi page, take control and select **Start RX**. The profile ID must match the locally configured profile; the browser cannot substitute a device.

Future supervised dummy-load acceptance is deliberately separate and was not run by this programme:

```sh
ShackCQAgent --authorize-digi-tx KX3-DUMMY-LOAD-VERIFIED
```

Run it only after the owner verifies the exact selected radio, audio route, RF load and local recovery procedure. The hosted service must also have its separate flag enabled. Production remains disabled. Use **STOP / DISARM** in the browser or `ShackCQAgent --digi-stop` locally; if RX readback is uncertain, correct the local radio state and obtain fresh receive evidence before trying again.

Do not enable either TX gate on this review branch. The independent review found that STOP/watchdog execution is not yet isolated from potentially blocking encoder/Hamlib work, authoritative UTC quality is not wired, and real-device audio conversion is not accepted. The current slotted-mode arm path intentionally returns `CLOCK_QUALITY_UNVERIFIED`.
