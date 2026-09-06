# Radio profile and backend matrix

| Profile | Backend | Default transport | Write posture | Specialized surfaces |
|---|---|---|---|---|
| Elecraft KX3 | Native Elecraft | USB serial 38,400 | Existing explicit safety | KX controls, EQ, physical-I/Q panadapter |
| Elecraft KX2 | Native Elecraft | USB serial 38,400 | Existing explicit safety | KX controls and EQ |
| FlexRadio | Native Flex | LAN | Existing session gates | Flex cockpit and VITA panafall |
| QMX | Native QMX | Composite USB 115,200 | Safe sets only; TX unavailable until integrated route | Generic central controls; reviewed QMX state/controller |
| QMX+ | Native QMX | Composite USB 115,200 | Same as QMX | Same as QMX; model determined from evidence |
| RGO ONE V6 | Native RGO ONE | USB CAT 57,600 | Safe sets; model ID 006 proof required | Generic central controls; reviewed RGO state/controller |
| RGO ONE legacy/unknown | Native RGO ONE | USB CAT 57,600 | Read-only | Conservative evidence only |
| Compiled Hamlib model | Embedded Hamlib | USB serial | Read-only by default | Searchable generic capability UI |
| rigctld/flrig/TCI model | Network Hamlib | Explicit LAN endpoint | Read-only by default; endpoint opt-in | Searchable generic capability UI |
| Unknown/future stored ID | Safe unknown | None | Read-only and disconnected | No connect path |

Native profiles are preferred when ShackCQ has reviewed model-specific semantics. QMX via generic Hamlib remains optional and does not gain native QMX I/Q or Digi behavior. RGO ONE does not gain a guessed Hamlib mapping.
