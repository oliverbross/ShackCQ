# dnwrnr/sgp4 provenance

- Repository: https://github.com/dnwrnr/sgp4
- Vendored commit: `661e057a5d369d5ee424676cf1d69cbead95ff2c`
- Licence: Apache-2.0; retained in `LICENSE`
- Language level used by ShackCQ: C++17

The files under `libsgp4/` are copied unchanged from that revision. ShackCQ-specific parsing, pass search, bounded sampling, Doppler, C ABI, and JNI code live outside the upstream directory in `core/src/satellite.cpp` and ShackCQ headers/bridges.

No source is fetched by CMake or Gradle.
