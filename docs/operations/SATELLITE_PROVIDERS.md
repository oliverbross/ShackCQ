# Satellite providers and provenance

## Audited baselines

Wavelog 3.1.0 was reviewed at peeled commit `af3256140bd05403b7c4a421746c2ea653a4f04f` under its MIT licence. The review covered `Sattimers.php`, `Amsatstatus.php`, `Satellite.php`, `Satellite_model.php`, `Hamsat.php`, `Satpredict.php`, satellite/status/timer views and JavaScript, and API v2 `Qso_resource.php`. The native implementation preserves the useful behaviours—passes, flightpath, status matrix, timers, catalogue/transponders, and ADIF fields—without copying Wavelog prediction code or treating community status as transmit authority.

The orbital engine is dnwrnr/sgp4 at exact commit `661e057a5d369d5ee424676cf1d69cbead95ff2c`, vendored unchanged under `core/third_party/sgp4`. Its Apache-2.0 `LICENSE` and `NOTICE` are retained. ShackCQ's C++ C-ABI/JNI adapter is outside the vendored tree and supplies local propagation, observer geometry, pass boundaries, ground/sky samples, and Doppler.

## Network sources and cache policy

| Source | Request/purpose | Automatic TTL | Manual limit / truth rule |
|---|---|---:|---|
| CelesTrak GP | `GROUP=AMATEUR&FORMAT=CSV` elements | 6 h | no refresh more often than 2 h; conditional requests and last-good cache |
| SatNOGS DB | Amateur, alive transmitter catalogue | 24 h | bounded response; CC-BY-SA-4.0 attribution shown in product |
| AMSAT Status | bounded 24-hour v1 reports, grouped locally into counts/timeline/latest reporters | 15 min | reports are community observations, never operational/RF authority |
| DF2ET/TEVEL | optional timer adapter | 15 min | only structurally valid, functional, bounded-time rows are shown active |

CelesTrak's documented query form and usage policy are followed: https://celestrak.org/NORAD/documentation/gp-data-formats.php and https://celestrak.org/usage-policy.php. SatNOGS filters and fields follow its API schema and its attribution/licensing statement: https://db.satnogs.org/api/schema/docs/ and https://db.satnogs.org/about/. AMSAT status behaviour follows https://www.amsat.org/status/api/.

Every cache exposes `CURRENT`, `STALE`, `OFFLINE_CACHE`, `EMPTY`, or `ERROR`, plus fetch time and sanitized last error. HTML, oversized, empty, malformed, timed-out, or non-2xx responses never replace valid last-good data. Orbital elements older than fourteen days are rejected for normal live prediction. Manual TLEs are parsed by the pinned native engine before save and are visibly marked `MANUAL`; local transponders are marked `LOCAL OVERRIDE` and do not claim provider validation.

No data is fabricated. Missing frequencies remain absent, stale/offline coordinates are labelled predictions, inactive/unknown transmitter flags are displayed as such, timer failure does not block local passes, and no status/provider result authorizes transmission.
