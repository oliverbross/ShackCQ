# Rotator integration and band policy

Supported driver families are native GS-232, DCU/ROTOR-EZ, EasyComm and SPID ROT1/ROT2; remote rotctld; and embedded Hamlib rotators. A microHAM ARCO must be configured in one of its published compatibility modes. ShackCQ does not claim or guess a proprietary ARCO protocol.

Profiles contain a hashed serial identity or explicit opt-in TCP endpoint, protocol, capability evidence/overrides, 0–450 degree capable limits, park position, calibration owner, forbidden sectors and presets. Band assignments may be scoped to a radio profile and select OFF, MANUAL, PROMPT, AUTO_SELECTED_TARGET or SATELLITE_SESSION policy.

Safety invariants:

- only one rotator profile owns movement authority;
- the radio and rotator runtimes share the same physical identity gate;
- profile restore and foreground entry never connect or move;
- automation arm and satellite tracking are session-only and clear on background/context change;
- manual movement clears automation;
- movement and park require a fresh review;
- STOP is available whenever a backend is connected and is routed without ordinary movement confirmation;
- stale position, missing limits, forbidden sectors, transmit policy conflicts and unknown capability state fail closed.

Target viewing is read-only. It does not create a movement command. Physical direction, wrap, cable clearance and heading offset ownership remain acceptance items for the operator.
