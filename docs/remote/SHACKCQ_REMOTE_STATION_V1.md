# ShackCQ Remote Station v1

ShackCQ Remote Station is an encrypted LAN/VPN-first connection between a station service and native clients. It requires no ShackCQ cloud account.

The station owns radio/rotator access and projects typed state, RX audio, and derived spectrum. Android remains the canonical local logging/Wavelog client. Sessions are authenticated, bounded, generation-scoped, and read-only by default. One explicit writer lease controls safe setters. TX and rotator movement additionally require short leases plus existing physical authority; absent authority is a hard block.

Targets:

- `shackcq-stationd`: headless Qt service using the desktop configuration/vault/owners.
- `ShackCQDesktop`: minimal Settings and Health administration.
- Android: Remote Stations route, pairing, certificate pinning, backend integration, remote banner, media sinks, and Global Stop.

This source/build candidate does not prove real network quality, authenticated live service, audible audio, physical CAT/PTT/Tune/RF, or rotator movement.
