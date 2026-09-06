# Android Spectrum Survey

Spectrum Survey stores derived aggregates only in app-private `shackcq-spectrum-survey.sqlite`, schema 2. The primary key combines time bucket, band, frequency bucket, mode, source and receiver. Rows contain sample/occupied counts, bounded median estimators, peak level, median noise, signal count and scanner-hit count. Raw I/Q, audio, decoded conversations, RadioText, QSO rows and operator notes are never stored.

Defaults are 30-day retention, 15-minute buckets, 1 kHz frequency buckets, 250,000 rows and 64 MiB. Configurable hard bounds are 7/30/90 days, 1–60 minutes, 10,000–500,000 rows and 8–128 MiB. Indexed time/frequency queries drive heatmap, band comparison, daily occupancy and scanner-activity views. Compaction enforces retention, row and byte caps; `PRAGMA quick_check` is exposed through Health.

Schema 1 upgrades transactionally to schema 2 metadata. Unknown migrations and future-schema downgrades fail closed. Debug Lab generates a 30-day in-memory survey for UI evidence and does not contaminate persistent operator history.

Survey rows are historical observations. They are labelled and must never be described as current RF or calibrated dBm without a user calibration.
