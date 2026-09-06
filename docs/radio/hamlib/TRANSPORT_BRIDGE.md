# Transport bridge

## Serial ownership

Android owns USB discovery, permission, open, framing, control lines, cancellation, and disconnect through `HamlibSerialTransportPort`. Hamlib never opens `/dev/bus/usb`, `/dev/tty*`, or `/dev/serial/by-id` on Android.

The native session creates a private Unix socket pair. A narrowly patched Hamlib pathname prefix, `shackcq-fd:`, duplicates the already-created native descriptor into Hamlib's normal serial I/O path. Kotlin runs exactly two bounded bridge pumps on `Dispatchers.IO`: Hamlib-to-Android and Android-to-Hamlib. Transfers are capped at 16 KiB in Kotlin and 64 KiB in JNI. Pending serial I/O is cancelled before teardown, both pumps are joined, the Android port disconnects, and only then is the native session destroyed.

The stable device identity is stored in settings; a raw OS device handle, USB permission token, or transient pathname is not.

## Network

Network profiles contain a bounded host, port, timeout, and an explicit `enabled` flag that defaults false. The controller refuses a disabled network profile. Host validation occurs again at JNI. Credentials are neither accepted nor logged by this v1 API.

## Cancellation and reconnect

Every connection receives a monotonic generation. Polls and queued commands from an old generation are ignored. Disconnect cancels the single poll loop and the bridge before closing native state. Reconnect creates a new session and never restores PTT, TUNE, or a transmit arm state.
