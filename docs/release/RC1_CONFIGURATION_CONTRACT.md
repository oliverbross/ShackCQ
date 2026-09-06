# RC1 Configuration Contract

The signed format contract is `SHACKCQ_CONFIGURATION_BUNDLE`, schema 1, with deterministic canonical JSON. Imports validate signature, schema, limits and section ownership before any mutation. Unknown future sections are retained for explicit review; they are never silently dropped or applied.

Safe export/import always excludes or resets:

- credentials and vault material;
- PTT/TUNE and TCI TX authority;
- Digi arm and active transmission;
- Keyer queue and repeat CQ;
- active Contest and N1MM arm;
- DX Chaser session;
- radio connection;
- rotator connection, motion and arm;
- pending commands.

Connections restore inert and disconnected. Explicit Connect plus capability/readback is required. Secret values remain in Android Keystore, Apple Keychain or the platform vault and are referenced only by alias. Size, count and nesting limits are fail-closed. Import failure leaves the prior configuration intact.
