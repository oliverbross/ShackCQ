# Windows Provider and Data Matrix

## Provider contract

The registry contains 17 reviewed provider entries. Every entry starts disabled and exposes explicit manual refresh only. Requests require HTTPS, follow at most three redirects, allow one in-flight reply per provider, apply a per-provider cooldown and byte limit, validate exact content types, parse JSON before cache acceptance, honour bounded numeric `Retry-After`, retain last-good cache, and sanitize errors.

States are `DISABLED`, `IDLE`, `LOADING`, `CURRENT`, `EMPTY`, `OFFLINE_CACHE` and `ERROR`. A provider failure cannot erase last-good data or enable a control action.

Fake-response coverage includes success, validated empty, malformed JSON, oversized response, unexpected content type, network timeout/error, cached fallback, `Retry-After`, and HTTP 304 validation.

## Data stores

| Store | File | Schema | Purpose | Scale fixture |
|---|---|---:|---|---:|
| Canonical QSO | `shackcq-desktop.sqlite` | 16 | QSO/projection/sync authority | existing 100k QSO test |
| Neural DX | `neural-dx.sqlite` | 5 | empirical evidence/outlook | 2,880 rows / 180 days |
| Digi | `shackcq-digi.sqlite` | 2 | decode and TX-draft state | 20,000 rows |
| Groups.io | `shackcq-groupsio.sqlite` | 2 | offline messages and FTS5 | 30,000 rows |
| Contest | `shackcq-contest.sqlite` | 2 | staging log/session state | 10,000 rows |
| DX Chaser | `shackcq-dxchaser.sqlite` | 1 | opportunity/session state | 20,000 rows |

Every store enables foreign keys, WAL and a bounded busy timeout. A database whose `user_version` is newer than supported is rejected; it is never downgraded or recreated silently.

Credentials are not stored in these databases or provider caches. Cache filenames use provider keys and validated response bytes only.
