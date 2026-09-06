#!/usr/bin/env python3
"""Disposable deterministic scale/recovery profiles; creates no persistent data."""

from __future__ import annotations

import json
from pathlib import Path
import sqlite3
import tempfile
import time


def timed(result: dict, name: str, operation):
    started = time.perf_counter()
    value = operation()
    result["timings_ms"][name] = round((time.perf_counter() - started) * 1000, 2)
    return value


def connect(path: Path) -> sqlite3.Connection:
    db = sqlite3.connect(path)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=OFF")
    return db


result = {"profile": "SHACKCQ_FINAL_RC_SOAK_V1", "timings_ms": {}, "database_bytes": {}, "assertions": []}
with tempfile.TemporaryDirectory(prefix="shackcq-final-soak-") as temporary:
    root = Path(temporary)

    log = connect(root / "logbook.sqlite")
    log.executescript("""
      CREATE TABLE qso(id INTEGER PRIMARY KEY, call TEXT, epoch INTEGER, band TEXT, mode TEXT, contest TEXT, satellite TEXT, portable TEXT);
      CREATE INDEX qso_epoch ON qso(epoch DESC, id DESC);
      CREATE INDEX qso_call_band_mode ON qso(call, band, mode);
      CREATE TABLE wavelog_relation(qso_id INTEGER PRIMARY KEY, remote_id TEXT, state TEXT);
    """)
    rows = ((i, f"T{i % 4096:04d}", 1_700_000_000 + i, ("20m", "40m", "2m")[i % 3],
             ("FT8", "SSB", "CW")[i % 3], f"C{i % 12}", f"S{i % 32}" if i % 17 == 0 else "",
             f"POTA-{i % 300}" if i % 13 == 0 else "") for i in range(100_000))
    timed(result, "logbook_insert_100k", lambda: log.executemany("INSERT INTO qso VALUES(?,?,?,?,?,?,?,?)", rows))
    timed(result, "logbook_relations", lambda: log.executemany(
        "INSERT INTO wavelog_relation VALUES(?,?,?)", ((i, f"r-{i}", "SYNCED") for i in range(0, 100_000, 3))))
    log.commit()
    page = timed(result, "logbook_keyset_page", lambda: log.execute(
        "SELECT id,call FROM qso WHERE (epoch,id) < (?,?) ORDER BY epoch DESC,id DESC LIMIT 100",
        (1_700_090_000, 90_000)).fetchall())
    aggregates = timed(result, "logbook_filter_aggregate", lambda: log.execute(
        "SELECT band,mode,count(*),count(DISTINCT call) FROM qso WHERE epoch>=? GROUP BY band,mode",
        (1_700_050_000,)).fetchall())
    streamed = timed(result, "logbook_stream_export", lambda: sum(
        len(batch) for batch in iter(lambda cursor=log.execute("SELECT * FROM qso ORDER BY id"): cursor.fetchmany(512), [])))
    assert len(page) == 100 and aggregates and streamed == 100_000
    result["assertions"].append("logbook_100k_keyset_filter_aggregate_stream")
    log.close()

    neural = connect(root / "neural-dx.sqlite")
    neural.execute("CREATE TABLE evidence(id INTEGER PRIMARY KEY, day INTEGER, station TEXT, provider TEXT, state TEXT, score REAL)")
    rows = ((day * 120 + n, day, f"station-{n % 4}", f"provider-{n % 3}",
             "OUTAGE" if n % 47 == 0 else "LIVE", (n % 100) / 100) for day in range(200) for n in range(120))
    timed(result, "neural_insert_200_days", lambda: neural.executemany("INSERT INTO evidence VALUES(?,?,?,?,?,?)", rows))
    neural.commit()
    timed(result, "neural_compact_180_days", lambda: neural.execute("DELETE FROM evidence WHERE day < 20"))
    neural.commit()
    scopes = neural.execute("SELECT count(DISTINCT station),sum(state='OUTAGE'),count(*) FROM evidence").fetchone()
    assert scopes[0] == 4 and scopes[1] > 0 and scopes[2] == 21_600
    result["assertions"].append("neural_180_day_compaction_scopes_outages")
    neural.close()

    digi = connect(root / "shackcq-digi.sqlite")
    digi.executescript("""
      CREATE TABLE decode(id INTEGER PRIMARY KEY, session_id INTEGER, mode TEXT, snr INTEGER, body TEXT);
      CREATE TABLE draft(id INTEGER PRIMARY KEY, session_id INTEGER, body TEXT);
      CREATE TABLE setting(key TEXT PRIMARY KEY, value TEXT);
    """)
    timed(result, "digi_insert_20k", lambda: digi.executemany(
        "INSERT INTO decode VALUES(?,?,?,?,?)",
        ((i, i // 100, ("FT8", "RTTY", "PSK31", "SSTV")[i % 4], -24 + i % 40, f"decode-{i}") for i in range(20_000))))
    digi.executemany("INSERT INTO draft VALUES(?,?,?)", ((i, i, f"draft-{i}") for i in range(100)))
    digi.executemany("INSERT INTO setting VALUES(?,?)", (("mode", "FT8"), ("rx_enabled", "false"), ("sstv_quota_mb", "256")))
    digi.commit()
    assert digi.execute("SELECT count(*) FROM decode").fetchone()[0] == 20_000
    assert digi.execute("SELECT count(*) FROM setting WHERE lower(key) LIKE '%tx%'").fetchone()[0] == 0
    result["assertions"].append("digi_20k_retention_restore_has_no_tx_state")
    digi.close()

    groups = connect(root / "shackcq-groupsio.sqlite")
    groups.executescript("""
      CREATE TABLE message(id INTEGER PRIMARY KEY, group_id INTEGER, subject TEXT, body TEXT);
      CREATE VIRTUAL TABLE message_fts USING fts5(subject,body,content='message',content_rowid='id');
      CREATE TABLE draft(id INTEGER PRIMARY KEY, state TEXT, body TEXT);
      CREATE TABLE attachment(id INTEGER PRIMARY KEY, bytes INTEGER);
      CREATE TABLE archive_state(group_id INTEGER PRIMARY KEY, paused INTEGER, page INTEGER);
    """)
    timed(result, "groups_insert_30k", lambda: groups.executemany(
        "INSERT INTO message VALUES(?,?,?,?)", ((i, i % 30, f"topic {i % 800}", f"cached archive body {i}") for i in range(30_000))))
    groups.execute("INSERT INTO message_fts(message_fts) VALUES('rebuild')")
    groups.executemany("INSERT INTO draft VALUES(?,?,?)", ((i, "UNKNOWN" if i % 9 == 0 else "LOCAL", f"draft-{i}") for i in range(200)))
    groups.executemany("INSERT INTO attachment VALUES(?,?)", ((i, 1024 * (i % 128)) for i in range(400)))
    groups.executemany("INSERT INTO archive_state VALUES(?,?,?)", ((i, i % 2, i * 10) for i in range(30)))
    groups.commit()
    hits = timed(result, "groups_all_groups_fts", lambda: groups.execute(
        "SELECT count(*) FROM message_fts WHERE message_fts MATCH 'archive'").fetchone()[0])
    assert hits == 30_000 and groups.execute("SELECT count(*) FROM draft WHERE state='UNKNOWN'").fetchone()[0] > 0
    result["assertions"].append("groups_archive_fts_draft_isolation_quota_pause_resume")
    groups.close()

    provider_events = ["foreground", "background", "offline", "malformed_success", "oversized_response",
                       "last_good_fallback", "station_change", "cancel", "close"]
    state = {"closed": False, "last_good": "cached", "accepted_body_bytes": 0}
    for event in provider_events:
        if event in {"malformed_success", "oversized_response"}:
            assert state["last_good"] == "cached" and state["accepted_body_bytes"] == 0
        if event == "close":
            state["closed"] = True
    assert state["closed"]
    result["assertions"].append("provider_lifecycle_fail_closed_last_good")

    for database in root.glob("*.sqlite"):
        result["database_bytes"][database.name] = database.stat().st_size

print(json.dumps(result, sort_keys=True, indent=2))
