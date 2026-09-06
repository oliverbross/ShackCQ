# radio-station-pro behavioural audit

Reviewed owner-authorized source: commit `e3df89c4bec2eed3e56570a538ea881cf9b203be`, tree `42c23a547a90f9b0b798d4488cf61446b8e4e9a4`. The 17-file bounded source manifest hashes to `e93798268ed56d9bcda159c6b310283ca5c7d89fb9f208d50212ef9a5436fdf1`.

Reviewed present paths: every file under `rotator_core/` and `rotator_drivers/`, `rotor_api.py`, `rotor_math.py`, `rotor_config.json`, `backend/api/rotator_http_routes.py`, `backend/api/rotator_models.py`, `backend/api/rotator_ws.py`, `backend/api/rotor.py`, and `vue_rotor_globe_visualizer_modal_route.vue`. The named `backend/services/rotator_service.py`, rotator store/modules/templates, and `backend/tests/rotators/**` were absent at this pin and are recorded as absent rather than invented.

Preserved concepts: manager/driver separation, typed state/capabilities, serial and TCP profiles, switching, polling, az/el targets, move/stop/park contracts, configurable limits, per-band use/offsets, bidirectional selection, flip policy, presets, ARCO over compatibility protocols, remote Hamlib, satellite configuration, multiple profiles, and discovery metadata.

Improved: persistent TCP replaces per-command connections; exact protocol framing replaces regex extraction; unknown movement stays unknown; ranges preserve 450 degrees; multiple physical identities are arbitrated; target generation/dwell/deadband is explicit; automation arm is session-only; raw identifiers are hashed; existing ShackCQ ports replace UDP/provider listeners.

Rejected: Flask/HTTP/TypeScript architecture, separate WSJT-X/DX listeners, subprocess-launched `rotctld`, blind command retries, implicit restored automation, plaintext diagnostics, and the earlier 0-359 clamp.

No prior source was copied line-for-line. Behaviour was independently reimplemented in Kotlin against the reviewed source and current protocol authorities.
