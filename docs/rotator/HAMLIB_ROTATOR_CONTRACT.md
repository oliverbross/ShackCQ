# Hamlib rotator contract

The parallel Hamlib platform selected official generated Hamlib 4.7.2, signed tag peeled commit `40f63488fe0bd751b147f48d62fd217bf53713a0`, tree `56a42afe2ace9dd1b43729168bb73ca46a812848`, archive SHA-256 `ae1fcf2dbc80ea0786ea8f047b09399c3f7737d1930442f61a031708ed33e88f`.

The exact `rotlist.h` contains 61 model macros across 28 backend families. Reviewed families include EasyComm I/II/III, Rotor-EZ/RotorCard/DCU/ERC/RT-21, GS-232A/B/generic and az/el variants, SPID ROT1/ROT2/MD-01, RC2800, ARS, IF-100, Prosistel, Celestron/SkyWatcher, Meade, iOptron, SatEL, Radant, and GRBL serial/network. Model enumeration remains dynamic through `RotatorHamlibPort`; ShackCQ does not duplicate the registry.

`RotatorHamlibPort` exposes enumerate, capabilities, open, close, poll, setPosition, stop and park. The later integration binds this to the embedded Hamlib branch. This branch edits or vendors no Hamlib source and starts no daemon.

Remote `rotctld` is a separate persistent bounded TCP client, default-disabled at `127.0.0.1:4533`. It uses extended responses and exact `RPRT`, cancellation through transport close, no internet discovery, no raw console, and no movement/park retry.
