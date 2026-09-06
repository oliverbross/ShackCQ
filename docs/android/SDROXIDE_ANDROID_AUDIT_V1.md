# SDRoxide Android Audit v1

Reviewed upstream: SDRoxide `v1.5.3`, commit `a680935b10f33768a499435e8bd37f779fa640ae`, tree `4697195080495da4a727b14234b85af89c10ecda`.

The audit selected interoperability and interaction concepts only: TCI lifecycle, multi-receiver routing, bounded spectrum/waterfall behavior, scanner controls, receiver DSP controls, and RF-path visualisation.

Rejected from incorporation:

- upstream source, shaders, icons, screenshots, map textures, recordings, and test IQ;
- whole-project vendoring, submodules, build-time fetching, or Rust UI ownership;
- TX paths and automatic reconnect/stream restore;
- neural audio models and large packaged data.

ShackCQ implementations are independent Kotlin/Compose and C++ code under existing GPL-3.0-only project ownership.
