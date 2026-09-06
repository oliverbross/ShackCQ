# ShackCQ Agent architecture v1

ShackCQAgent is a user-facing build of the existing stationd_main.cpp process. It does not introduce a second radio owner. DesktopRadioController remains the single local radio/Hamlib owner, while CloudAgentClient adds one outbound secure WebSocket connection to ShackCQ Cloud.

The process contains four bounded layers: operating-system credential vault, outbound TLS WebSocket, protocol validator/generation gate, and the existing radio controller. No inbound Internet listener, arbitrary proxy, raw CAT tunnel, offline command queue, TX audio path, keying path, PTT, TUNE, amplifier, or rotator movement is exposed by the cloud client.

One active cloud socket exists per Agent credential. A replacement socket invalidates the previous generation. Commands are accepted only for the authenticated Agent and stable radio device ID, only for the current generation, and only from the compiled receive-control allowlist. Every success requires Hamlib readback.

The legacy shackcq-stationd target is retained for compatibility; packages expose ShackCQAgent.
